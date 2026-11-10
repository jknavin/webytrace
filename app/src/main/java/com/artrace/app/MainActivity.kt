package com.artrace.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.artrace.app.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO, TIMELAPSE }

    private lateinit var b: ActivityMainBinding
    private val main = Handler(Looper.getMainLooper())

    // worker: time-lapse encoding. io: decoding picked images.
    private val worker = Executors.newSingleThreadExecutor()
    private val io = Executors.newSingleThreadExecutor()

    // camera
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var torchOn = false
    private var starting = false

    // state
    private var mode = Mode.PHOTO
    private var recording: Recording? = null
    private var recSeconds = 0L
    private var lastUri: Uri? = null
    private var lastMime = "image/*"

    // time-lapse
    private val intervals = intArrayOf(1, 2, 5, 10, 30, 60)
    private var intervalIdx = 1
    private var timelapseOn = false
    private var tlFrames = 0
    @Volatile private var tlFailed = false
    private var encoder: TimelapseEncoder? = null // worker thread only
    private var tlUri: Uri? = null // worker thread only

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasPerm(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            toast("Camera permission is needed. Enable it in Settings > Apps > AR Trace.")
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadOverlay(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        b.previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        setupOverlayControls()
        setupCaptureControls()
        updateIntervalText()
        styleModeButtons()
        updateUi()

        if (!hasPerm(Manifest.permission.CAMERA)) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    override fun onStart() {
        super.onStart()
        if (cameraProvider == null && hasPerm(Manifest.permission.CAMERA)) startCamera()
    }

    override fun onStop() {
        super.onStop()
        // The camera stops when the app is hidden, so save whatever is in progress.
        stopTimelapse()
        recording?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
        io.shutdown()
    }

    // ------------------------------------------------------------------ overlay

    private fun setupOverlayControls() {
        b.btnPick.setOnClickListener { pickImage.launch("image/*") }
        b.btnClear.setOnClickListener { b.overlayView.clearImage() }
        b.btnLock.setOnClickListener {
            val v = !b.overlayView.locked
            b.overlayView.locked = v
            b.btnLock.text = if (v) "🔒 Locked" else "🔓 Lock"
            setActive(b.btnLock, v)
        }
        b.btnMirror.setOnClickListener { b.overlayView.flip() }
        b.btnOutline.setOnClickListener {
            val v = !b.overlayView.outlineMode
            b.overlayView.outlineMode = v
            setActive(b.btnOutline, v)
        }
        b.btnReset.setOnClickListener { b.overlayView.resetTransform() }
        b.opacitySlider.addOnChangeListener { _, value, _ -> b.overlayView.imageAlpha = value }
        b.overlayView.imageAlpha = b.opacitySlider.value

        b.btnTorch.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            torchOn = !torchOn
            cam.cameraControl.enableTorch(torchOn)
            setActive(b.btnTorch, torchOn)
        }
        b.btnSwitch.setOnClickListener {
            val old = lensFacing
            lensFacing = if (old == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            if (!bindCamera()) {
                lensFacing = old
                bindCamera()
            }
        }
    }

    private fun loadOverlay(uri: Uri) {
        io.execute {
            val bmp = try {
                decodeBitmap(uri)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (bmp == null) toast("Couldn't open that image") else b.overlayView.setImage(bmp)
            }
        }
    }

    private fun decodeBitmap(uri: Uri): Bitmap? {
        val maxDim = 1600
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val orientation = try {
            contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }
        }
        val scale = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) m.postScale(scale, scale)
        if (!m.isIdentity) {
            val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (t !== bmp) bmp.recycle()
            bmp = t
        }
        return bmp
    }

    // ------------------------------------------------------------------ camera

    private fun startCamera() {
        if (starting) return
        starting = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            starting = false
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (e: Exception) {
                toast("Couldn't start the camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun newPreview(): Preview =
        Preview.Builder().build().also { it.setSurfaceProvider(b.previewView.surfaceProvider) }

    /** Returns true if the camera is running afterwards. */
    private fun bindCamera(): Boolean {
        val provider = cameraProvider ?: return false
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        provider.unbindAll()
        camera = null
        torchOn = false
        setActive(b.btnTorch, false)

        // Preferred: preview + photo + video
        try {
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            val vc = VideoCapture.withOutput(recorder)
            camera = provider.bindToLifecycle(this, selector, newPreview(), ic, vc)
            imageCapture = ic
            videoCapture = vc
        } catch (e: Exception) {
            // Fallback for older phones: preview + photo only
            try {
                provider.unbindAll()
                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                camera = provider.bindToLifecycle(this, selector, newPreview(), ic)
                imageCapture = ic
                videoCapture = null
                toast("This phone can't record video and take photos together. Video is disabled.")
            } catch (e2: Exception) {
                toast("Camera error: ${e2.message}")
                return false
            }
        }

        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        b.btnTorch.visibility = if (hasFlash) View.VISIBLE else View.GONE
        return true
    }

    // ------------------------------------------------------------------ capture

    private fun setupCaptureControls() {
        b.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                mode = when (checkedId) {
                    R.id.btnModeVideo -> Mode.VIDEO
                    R.id.btnModeTimelapse -> Mode.TIMELAPSE
                    else -> Mode.PHOTO
                }
                styleModeButtons()
                updateUi()
            }
        }
        b.btnInterval.setOnClickListener {
            if (timelapseOn) return@setOnClickListener
            intervalIdx = (intervalIdx + 1) % intervals.size
            updateIntervalText()
        }
        b.captureButton.setOnClickListener {
            when (mode) {
                Mode.PHOTO -> takePhoto()
                Mode.VIDEO -> if (recording == null) startRecording() else stopRecording()
                Mode.TIMELAPSE -> if (timelapseOn) stopTimelapse() else startTimelapse()
            }
        }
        b.btnOpenLast.setOnClickListener {
            val uri = lastUri
            if (uri == null) {
                toast("Nothing saved yet in this session")
                return@setOnClickListener
            }
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, lastMime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (e: ActivityNotFoundException) {
                toast("No app found to open it")
            }
        }
    }

    private fun takePhoto() {
        val ic = imageCapture
        if (ic == null) {
            toast("Camera not ready")
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ARTrace_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ARTrace")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        ic.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lastUri = output.savedUri
                    lastMime = "image/jpeg"
                    toast("Photo saved to Pictures/ARTrace")
                }

                override fun onError(exception: ImageCaptureException) {
                    toast("Photo failed: ${exception.message}")
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture
        if (vc == null) {
            toast("Video recording isn't available on this phone")
            return
        }
        // Record to a private temp file first (always valid), then copy to the gallery.
        val tmp = File(cacheDir, "rec_${stamp()}.mp4")
        val output = FileOutputOptions.Builder(tmp).build()

        var pending = vc.output.prepareRecording(this, output)
        if (hasPerm(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()

        recSeconds = 0
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Status -> {
                    recSeconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                    updateStatus()
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    if (!event.hasError()) {
                        saveVideoToGallery(tmp)
                    } else {
                        tmp.delete()
                        toast("Recording failed: ${videoErrorName(event.error)} (code ${event.error})")
                    }
                    recSeconds = 0
                    updateUi()
                }
            }
        }
        updateUi()
    }

    private fun stopRecording() {
        recording?.stop()
    }

    /** Copies a finished recording from the temp file into Movies/ARTrace. */
    private fun saveVideoToGallery(file: File) {
        io.execute {
            var uri: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "ARTrace_${stamp()}.mp4")
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ARTrace")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Couldn't create the video file")
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("Couldn't open the video file")
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(uri, done, null, null)
                val saved = uri
                runOnUiThread {
                    lastUri = saved
                    lastMime = "video/mp4"
                    toast("Video saved to Movies/ARTrace")
                }
            } catch (e: Exception) {
                if (uri != null) {
                    try { contentResolver.delete(uri, null, null) } catch (ignored: Exception) {}
                }
                runOnUiThread { toast("Couldn't save video: ${e.message}") }
            } finally {
                file.delete()
            }
        }
    }

    private fun videoErrorName(code: Int): String = when (code) {
        1 -> "unknown error"
        2 -> "file size limit reached"
        3 -> "not enough storage"
        4 -> "camera became inactive"
        5 -> "invalid output options"
        6 -> "encoding failed"
        7 -> "recorder error"
        8 -> "no valid data recorded (stopped too quickly?)"
        9 -> "duration limit reached"
        else -> "error"
    }

    // ------------------------------------------------------------------ time-lapse

    private val tlTick = object : Runnable {
        override fun run() {
            if (!timelapseOn) return
            val bmp = b.previewView.bitmap
            if (bmp != null) {
                tlFrames++
                worker.execute { encodeFrame(bmp) }
                updateStatus()
            }
            main.postDelayed(this, intervals[intervalIdx] * 1000L)
        }
    }

    private fun startTimelapse() {
        if (camera == null) {
            toast("Camera not ready")
            return
        }
        tlFrames = 0
        tlFailed = false
        timelapseOn = true
        updateUi()
        main.post(tlTick)
    }

    private fun stopTimelapse() {
        if (!timelapseOn) return
        timelapseOn = false
        main.removeCallbacks(tlTick)
        val frames = tlFrames
        worker.execute {
            val enc = encoder
            val uri = tlUri
            encoder = null
            tlUri = null
            if (enc != null && uri != null) {
                val ok = try {
                    enc.finish()
                } catch (e: Exception) {
                    false
                }
                if (ok) {
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    contentResolver.update(uri, done, null, null)
                    runOnUiThread {
                        lastUri = uri
                        lastMime = "video/mp4"
                        toast("Time-lapse saved to Movies/ARTrace ($frames frames)")
                    }
                } else {
                    try { contentResolver.delete(uri, null, null) } catch (e: Exception) {}
                    runOnUiThread { toast("Time-lapse wasn't saved (no frames or encoder error)") }
                }
            }
        }
        updateUi()
    }

    /** Runs on the worker thread. Takes ownership of [bmp]. */
    private fun encodeFrame(bmp: Bitmap) {
        try {
            if (tlFailed) return
            if (encoder == null) {
                var w = bmp.width
                var h = bmp.height
                val scale = 1280f / maxOf(w, h)
                if (scale < 1f) {
                    w = (w * scale).toInt()
                    h = (h * scale).toInt()
                }
                w = maxOf(16, w and 1.inv())
                h = maxOf(16, h and 1.inv())

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "ARTrace_Timelapse_${stamp()}.mp4")
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ARTrace")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Couldn't create the video file")
                val pfd = contentResolver.openFileDescriptor(uri, "rw")
                if (pfd == null) {
                    contentResolver.delete(uri, null, null)
                    throw IllegalStateException("Couldn't open the video file")
                }
                tlUri = uri
                try {
                    encoder = TimelapseEncoder(pfd, w, h, 30)
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    tlUri = null
                    throw e
                }
            }
            encoder?.addFrame(bmp)
        } catch (e: Exception) {
            tlFailed = true
            runOnUiThread {
                toast("Time-lapse error: ${e.message}")
                stopTimelapse()
            }
        } finally {
            bmp.recycle()
        }
    }

    // ------------------------------------------------------------------ ui helpers

    private fun updateUi() {
        val busy = recording != null || timelapseOn
        b.captureButton.setBackgroundResource(
            when {
                busy -> R.drawable.shutter_stop
                mode == Mode.PHOTO -> R.drawable.shutter_photo
                else -> R.drawable.shutter_rec
            }
        )
        b.btnInterval.visibility = if (mode == Mode.TIMELAPSE) View.VISIBLE else View.GONE
        b.btnInterval.isEnabled = !timelapseOn
        b.btnModePhoto.isEnabled = !busy
        b.btnModeVideo.isEnabled = !busy
        b.btnModeTimelapse.isEnabled = !busy
        b.btnSwitch.isEnabled = !busy
        b.statusText.visibility = if (busy) View.VISIBLE else View.GONE
        updateStatus()
    }

    private fun updateStatus() {
        b.statusText.text = when {
            recording != null -> "● REC  " + formatTime(recSeconds)
            timelapseOn -> "● TIME-LAPSE  $tlFrames frames · every ${intervalLabel()}"
            else -> ""
        }
    }

    private fun updateIntervalText() {
        b.btnInterval.text = "⏱ Every " + intervalLabel()
    }

    private fun intervalLabel(): String {
        val n = intervals[intervalIdx]
        return if (n >= 60) "${n / 60} min" else "${n}s"
    }

    private fun formatTime(totalSeconds: Long): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun styleModeButtons() {
        setActive(b.btnModePhoto, b.modeGroup.checkedButtonId == R.id.btnModePhoto)
        setActive(b.btnModeVideo, b.modeGroup.checkedButtonId == R.id.btnModeVideo)
        setActive(b.btnModeTimelapse, b.modeGroup.checkedButtonId == R.id.btnModeTimelapse)
    }

    private fun setActive(btn: MaterialButton, active: Boolean) {
        btn.backgroundTintList = ColorStateList.valueOf(
            if (active) 0xFF00C853.toInt() else 0xCC303030.toInt()
        )
        btn.setTextColor(if (active) Color.BLACK else Color.WHITE)
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
