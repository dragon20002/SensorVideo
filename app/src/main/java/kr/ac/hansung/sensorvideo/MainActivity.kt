package kr.ac.hansung.sensorvideo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import pub.devrel.easypermissions.EasyPermissions
import java.util.concurrent.TimeUnit

private const val RATIONALE = "센서 데이터 저장, 카메라 사용 권한을 요청합니다."
private const val REQ_PERMISSION = 9

class MainActivity : AppCompatActivity(), EasyPermissions.PermissionCallbacks {
    private lateinit var mSensorValuesManager: SensorValuesManager
    private var mFileManager: FileManager = FileManager()
    private var mDisposable: Disposable? = null

    private lateinit var mRecorderManager: RecorderManager
    private val mSurfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            startCamera()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            mRecorderManager.configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    }
    private lateinit var mCameraFrame: AutoFitTextureView
    private lateinit var mTxtFilename: TextView
    private lateinit var mTxtTime: TextView
    private lateinit var mTxtAcc: TextView
    private lateinit var mTxtMag: TextView
    private lateinit var mTxtGyro: TextView
    private lateinit var mBtnSettings: ImageButton
    private lateinit var mBtnRecord: ImageButton

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.main_activity)

        mCameraFrame = findViewById(R.id.camera_frame)
        mTxtFilename = findViewById(R.id.text_filename)
        mTxtTime = findViewById(R.id.text_time)
        mTxtAcc = findViewById(R.id.text_acc)
        mTxtMag = findViewById(R.id.text_mag)
        mTxtGyro = findViewById(R.id.text_gyro)
        mBtnSettings = findViewById(R.id.button_settings)
        mBtnRecord = findViewById(R.id.button_record)
        val progressCircular: ProgressBar = findViewById(R.id.progress_circular)

        mSensorValuesManager = SensorValuesManager.getInstance(this)
        mRecorderManager = RecorderManager(
            getSystemService(Context.CAMERA_SERVICE) as CameraManager,
            mCameraFrame,
            windowManager.defaultDisplay.rotation
        )

        /* Event Listener */
        mBtnSettings.setOnClickListener { startActivity(settingsIntent()) }

        mBtnRecord.setOnClickListener {
            if (!mRecorderManager.isRecordingVideo) {
                progressCircular.visibility = View.VISIBLE

                mRecorderManager.startRecordingVideo { success ->
                    runOnUiThread {
                        progressCircular.visibility = View.GONE
                        if (success) {
                            startWriteFile()
                            mBtnRecord.setImageResource(R.drawable.ic_save_black_48dp)
                        }
                    }
                }
            } else {
                mBtnRecord.setImageResource(R.drawable.ic_camera_black_48dp)
                stopWriteFile()
                mRecorderManager.stopRecordingVideo()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!EasyPermissions.hasPermissions(
                this,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        ) {
            EasyPermissions.requestPermissions(
                this,
                RATIONALE,
                REQ_PERMISSION,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            startCamera()
        }
    }

    override fun onPause() {
        if (mRecorderManager.isRecordingVideo) {
            mBtnRecord.setImageResource(R.drawable.ic_camera_black_48dp)
            stopWriteFile()
            mRecorderManager.stopRecordingVideo()
        }
        mRecorderManager.closeCamera()
        mRecorderManager.stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        mSensorValuesManager.unregisterListener()
        mFileManager.closePrintWriter()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    override fun onPermissionsGranted(requestCode: Int, perms: List<String>) {
        if (requestCode == REQ_PERMISSION && perms.size == 3) {
            startCamera()
        }
    }

    override fun onPermissionsDenied(requestCode: Int, perms: List<String>) {
        finish()
    }


    private fun startCamera() {
        if (mCameraFrame.isAvailable) {
            mRecorderManager.apply {
                getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).apply {
                    this.getString(PREF_DIR_PATH, DEFAULT_DIR_PATH)?.let { dirpath = it }
                    videoSizeIndex = getInt(PREF_VIDEO_SIZE_INDEX, 0)
                    videoFps = getInt(PREF_VIDEO_FPS, 24)
                }

                rotation = windowManager.defaultDisplay.rotation
                startBackgroundThread()
                openCamera(mCameraFrame.width, mCameraFrame.height)
            }
        } else {
            mCameraFrame.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startWriteFile() {
        mSensorValuesManager.registerListener()
        val printWriter = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_DIR_PATH, DEFAULT_DIR_PATH)?.let {
                mFileManager.openPrintWriter(it)
            }
        mDisposable = Observable.interval(10, TimeUnit.MILLISECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeOn(Schedulers.io())
            .subscribe({
                val time: String = it.let { time -> "${time / 100f}" }
                val acc: String = mSensorValuesManager.acc.let { acc -> "${acc[0]} ${acc[1]} ${acc[2]}" }
                val mag: String = mSensorValuesManager.mag.let { mag -> "${mag[0]} ${mag[1]} ${mag[2]}" }
                val gyro: String = mSensorValuesManager.gyro.let { gyro -> "${gyro[0]} ${gyro[1]} ${gyro[2]}" }

                printWriter?.printf("$time $acc $mag $gyro\r\n")

                mTxtTime.text = "$time sec"
                mTxtAcc.text = "$acc acc"
                mTxtMag.text = "$mag mag"
                mTxtGyro.text = "$gyro gyro"
            }, { it.printStackTrace() })

        mTxtFilename.text = mFileManager.dataFilename
        mTxtTime.text = "0.0 sec"
    }

    private fun stopWriteFile() {
        mDisposable?.dispose()
        mDisposable = null
        mSensorValuesManager.unregisterListener()
        mFileManager.closePrintWriter()

        mTxtFilename.text = ""
        mTxtTime.text = ""
        mTxtAcc.text = ""
        mTxtMag.text = ""
        mTxtGyro.text = ""
    }

}
