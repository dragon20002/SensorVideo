package kr.ac.hansung.sensorvideo

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
import android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION
import android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW
import android.hardware.camera2.CameraDevice.TEMPLATE_RECORD
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import java.io.File
import java.io.IOException
import java.lang.Long.signum
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

private const val TAG = "debug"
private const val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
private const val SENSOR_ORIENTATION_INVERSE_DEGREES = 270
private val DEFAULT_ORIENTATIONS = SparseIntArray().apply {
    append(Surface.ROTATION_0, 90)
    append(Surface.ROTATION_90, 0)
    append(Surface.ROTATION_180, 270)
    append(Surface.ROTATION_270, 180)
}
private val INVERSE_ORIENTATIONS = SparseIntArray().apply {
    append(Surface.ROTATION_0, 270)
    append(Surface.ROTATION_90, 180)
    append(Surface.ROTATION_180, 90)
    append(Surface.ROTATION_270, 0)
}


class RecorderManager(
    private val manager: CameraManager, private val textureView: AutoFitTextureView,
    var rotation: Int
) {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var previewSize: Size
    private lateinit var videoSize: Size
    var dirpath: String = DEFAULT_DIR_PATH
    var videoSizeIndex: Int = 0
    var videoFps: Int = 24
    var isRecordingVideo = false
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraOpenCloseLock = Semaphore(1)
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private var sensorOrientation = 0

    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@RecorderManager.cameraDevice = cameraDevice
            startPreview()
            configureTransform(textureView.width, textureView.height)
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@RecorderManager.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@RecorderManager.cameraDevice = null
        }

    }

    /**
     * Output file for video
     */
    private var nextVideoAbsolutePath: String? = null

    private var mediaRecorder: MediaRecorder? = null

    /**
     * Starts a background thread and its [Handler].
     */
    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }

    /**
     * Tries to open a [CameraDevice]. The result is listened by [stateCallback].
     */
    @SuppressLint("MissingPermission")
    fun openCamera(width: Int, height: Int) {
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            val cameraId = manager.cameraIdList[0]

            // Choose the sizes for camera preview and video recording
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw RuntimeException("Cannot get available preview/video sizes")
            @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
            sensorOrientation = characteristics.get(SENSOR_ORIENTATION)
            videoSize = map.getOutputSizes(MediaRecorder::class.java)[videoSizeIndex]
            previewSize = chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                width, height, videoSize
            )

            textureView.setAspectRatio(previewSize.width, previewSize.height)
            textureView.setAspectRatio(previewSize.height, previewSize.width) //portrait
            configureTransform(width, height)
            mediaRecorder = MediaRecorder()
            manager.openCamera(cameraId, stateCallback, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Close the [CameraDevice].
     */
    fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            closePreviewSession()
            cameraDevice?.close()
            cameraDevice = null
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Start the camera preview.
     */
    private fun startPreview() {
        if (cameraDevice == null || !textureView.isAvailable) return

        try {
            closePreviewSession()
            val texture = textureView.surfaceTexture
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(TEMPLATE_PREVIEW)

            val previewSurface = Surface(texture)
            previewRequestBuilder.addTarget(previewSurface)

            cameraDevice?.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        updatePreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    /**
     * Update the camera preview. [startPreview] needs to be called in advance.
     */
    private fun updatePreview() {
        if (cameraDevice == null) return

        try {
            setUpCaptureRequestBuilder(previewRequestBuilder)
            HandlerThread("CameraPreview").start()
            captureSession?.setRepeatingRequest(
                previewRequestBuilder.build(),
                null, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    private fun setUpCaptureRequestBuilder(builder: CaptureRequest.Builder?) {
        builder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `textureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `textureView` is fixed.
     *
     * @param viewWidth  The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            with(matrix) {
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        }
        textureView.setTransform(matrix)
    }

    @Throws(IOException::class)
    private fun setUpMediaRecorder() {
        if (nextVideoAbsolutePath.isNullOrEmpty()) {
            nextVideoAbsolutePath = getOutputMediaFile()?.absolutePath
        }

        when (sensorOrientation) {
            SENSOR_ORIENTATION_DEFAULT_DEGREES ->
                mediaRecorder?.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation))
            SENSOR_ORIENTATION_INVERSE_DEGREES ->
                mediaRecorder?.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation))
        }

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(nextVideoAbsolutePath)
            setVideoEncodingBitRate(10000000)
            setCaptureRate(videoFps.toDouble())
            setVideoFrameRate(videoFps)
            setVideoSize(videoSize.width, videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
    }

    fun startRecordingVideo(callback: (success: Boolean) -> Unit) {
        if (cameraDevice == null || !textureView.isAvailable) return

        try {
            closePreviewSession()
            setUpMediaRecorder()
            val texture = textureView.surfaceTexture.apply {
                setDefaultBufferSize(previewSize.width, previewSize.height)
            }

            // Set up Surface for camera preview and MediaRecorder
            val previewSurface = Surface(texture)
            val recorderSurface = mediaRecorder!!.surface
            val surfaces = ArrayList<Surface>().apply {
                add(previewSurface)
                add(recorderSurface)
            }
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recorderSurface)
            }

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            cameraDevice?.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        captureSession = cameraCaptureSession
                        updatePreview()
                        isRecordingVideo = true
                        mediaRecorder?.start()
                        callback(true)
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        callback(false)
                    }
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: IOException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun closePreviewSession() {
        captureSession?.close()
        captureSession = null
    }

    fun stopRecordingVideo() {
        isRecordingVideo = false
        nextVideoAbsolutePath = null

        startPreview()

        mediaRecorder?.apply {
            stop()
            reset()
        }
    }

//    /**
//     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
//     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
//     *
//     * @param choices The list of available sizes
//     * @return The video size
//     */
//    private fun chooseVideoSize(choices: Array<Size>) = choices.firstOrNull {
//        it.width == it.height * 4 / 3 && it.width <= 1080
//    } ?: choices[choices.size - 1]

    /**
     * Given [choices] of [Size]s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal [Size], or an arbitrary one if none were big enough
     */
    private fun chooseOptimalSize(
        choices: Array<Size>,
        width: Int,
        height: Int,
        aspectRatio: Size
    ): Size {

        // Collect the supported resolutions that are at least as big as the preview Surface
        val w = aspectRatio.width
        val h = aspectRatio.height
        val bigEnough = choices.filter {
            it.height == it.width * h / w && it.width >= width && it.height >= height
        }

        // Pick the smallest of those, assuming we found any
        return if (bigEnough.isNotEmpty()) {
            Collections.min(bigEnough, CompareSizesByArea())
        } else {
            choices[0]
        }
    }

    class CompareSizesByArea : Comparator<Size> {

        // We cast here to ensure the multiplications won't overflow
        override fun compare(lhs: Size, rhs: Size) =
            signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
    }


    private fun getOutputMediaFile(): File? {
        val mediaStorageDir = File(dirpath)

        mediaStorageDir.apply {
            if (!exists()) {
                if (!mkdirs()) {
                    Log.d("debug", "failed to create directory")
                    return null
                }
            }
        }

        // Create a media file name
        val timeStamp = SimpleDateFormat("yyyy.MM.dd HH.mm.ss", Locale.KOREAN).format(Date())
        return File("${mediaStorageDir.path}${File.separator}VID_$timeStamp.mp4")
    }

}