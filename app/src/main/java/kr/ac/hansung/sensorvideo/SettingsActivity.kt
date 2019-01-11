package kr.ac.hansung.sensorvideo

import RealPathUtil
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.view.MenuItem
import android.widget.*
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

private const val REQ_SELECT_DIR: Int = 1
val DEFAULT_DIR_PATH: String = Environment.getExternalStorageState() + "/sensor video"
const val PREF_NAME: String = "settings"
const val PREF_DIR_PATH: String = "dir_path"
const val PREF_VIDEO_SIZE_INDEX: String = "video_size_index"

class SettingsActivity : AppCompatActivity() {

    private lateinit var mTxtDirPath: TextView
    private lateinit var mBtnDirPath: ImageButton
    private lateinit var mSpnVideoSize: Spinner

    private lateinit var videoSizeArray: Array<Size>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)

        /* View */
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        mTxtDirPath = findViewById(R.id.text_dir_path)
        mBtnDirPath = findViewById(R.id.button_dir_path)
        mSpnVideoSize = findViewById(R.id.spinner_video_size)

        /* Get Available Video Size List */
        val manager: CameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList[0]
        // Choose the sizes for camera preview and video recording
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw RuntimeException("Cannot get available preview/video sizes")
        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        videoSizeArray = map.getOutputSizes(MediaRecorder::class.java)
        val spnDataSet: Array<String> = Array(videoSizeArray.size) {
            "${videoSizeArray[it].width} x ${videoSizeArray[it].height}"
        }
        mSpnVideoSize.adapter =
            ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, spnDataSet)

        /* Get User Preference */
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).apply {
            val dirPath: String? = getString(PREF_DIR_PATH, DEFAULT_DIR_PATH)
            val videoSizeIndex: Int? = getInt(PREF_VIDEO_SIZE_INDEX, 0)

            dirPath?.let { mTxtDirPath.text = it }
            if (videoSizeArray.isNotEmpty()) {
                videoSizeIndex?.let { mSpnVideoSize.setSelection(it) }
            }
        }

        /* Event Listener */
        mBtnDirPath.setOnClickListener { showFileChooser() }

    }

    override fun onBackPressed() {
        val dirPath: String? = if (mTxtDirPath.text.isNotEmpty()) {
            mTxtDirPath.text.toString()
        } else null
        val videoSizeIndex: Int? = if (videoSizeArray.isNotEmpty()) {
            mSpnVideoSize.selectedItemPosition
        } else null

        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().apply {
            dirPath?.let { putString(PREF_DIR_PATH, it) }
            videoSizeIndex?.let { putInt(PREF_VIDEO_SIZE_INDEX, it) }
            apply()
        }

        setResult(RESULT_OK)
        super.onBackPressed()
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        return when (item?.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_SELECT_DIR) {
            if (resultCode == Activity.RESULT_OK) {
                data?.data?.let {
                    Log.i("debug", "uri=$it")
                    Log.i("debug", "uri.path=${it.path}")
                    RealPathUtil.getRealPath(this@SettingsActivity, it)
                }?.let {
                    Log.i("debug", "real-path=$it")
                    it
                }?.let {
                    val mediaStorageDir = File(it)
                    try {
                        RandomAccessFile("${mediaStorageDir.path}${File.separator}0", "rw")
                        mTxtDirPath.text = it
                        File("${mediaStorageDir.path}${File.separator}0").delete()
                    } catch (e: FileNotFoundException) {
                        Toast.makeText(this, "해당 경로에 대한 권한이 부족합니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, "경로 선택이 취소되었습니다.", Toast.LENGTH_SHORT).show()
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun showFileChooser() {
        val intent: Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        }

        try {
            startActivityForResult(Intent.createChooser(intent, "경로 선택"), REQ_SELECT_DIR)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "파일 매니저를 설치해주세요.", Toast.LENGTH_SHORT).show()
        }
    }

}


fun Context.settingsIntent(): Intent {
    return Intent(this, SettingsActivity::class.java)
}
