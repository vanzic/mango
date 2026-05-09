package com.vixcy.mango

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var textureView: TextureView
    private lateinit var vStatusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvDurationValue: TextView
    private lateinit var tvFileSizeEstimate: TextView
    private lateinit var seekDuration: SeekBar
    private lateinit var btnToggleRecording: TextView
    private lateinit var chip720p: TextView
    private lateinit var chip1080p: TextView
    private lateinit var chip4k: TextView
    private lateinit var chip24fps: TextView
    private lateinit var chip30fps: TextView
    private lateinit var chip60fps: TextView
    private lateinit var chipAspect16_9: TextView
    private lateinit var chipAspect4_3: TextView
    private lateinit var chipAspect1_1: TextView

    // Camera
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler

    // State
    private var isRecording = false
    private var selectedWidth = 1280
    private var selectedHeight = 720
    private var selectedBitrate = 2_000_000
    private var selectedFps = 30
    private var selectedDurationMin = 10
    private var aspectRatioW = 16f
    private var aspectRatioH = 9f

    companion object {
        private const val PERM_REQUEST = 101
        private const val PREFS = "mango_prefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        bindViews()
        requestPermissions()
        restoreState()
        setupQualityChips()
        setupFpsChips()
        setupAspectRatioChips()
        setupDurationSlider()
        setupToggleButton()
    }

    private fun bindViews() {
        textureView          = findViewById(R.id.textureView)
        vStatusDot           = findViewById(R.id.vStatusDot)
        tvStatus             = findViewById(R.id.tvStatus)
        tvDurationValue      = findViewById(R.id.tvDurationValue)
        tvFileSizeEstimate   = findViewById(R.id.tvFileSizeEstimate)
        seekDuration         = findViewById(R.id.seekDuration)
        btnToggleRecording   = findViewById(R.id.btnToggleRecording)
        chip720p             = findViewById(R.id.chip720p)
        chip1080p            = findViewById(R.id.chip1080p)
        chip4k               = findViewById(R.id.chip4k)
        chip24fps            = findViewById(R.id.chip24fps)
        chip30fps            = findViewById(R.id.chip30fps)
        chip60fps            = findViewById(R.id.chip60fps)
        chipAspect16_9       = findViewById(R.id.chipAspect16_9)
        chipAspect4_3        = findViewById(R.id.chipAspect4_3)
        chipAspect1_1        = findViewById(R.id.chipAspect1_1)
    }

    private fun restoreState() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        isRecording = prefs.getBoolean("recording_active", false)
        if (isRecording) setRecordingUI(true)
    }

    // ── Quality Chips ──────────────────────────────────────────────────────────

    private fun setupQualityChips() {
        selectQuality(chip720p, 1280, 720, 2_000_000)
        addChipTouchFeedback(chip720p)  { selectQuality(chip720p,  1280, 720,  2_000_000) }
        addChipTouchFeedback(chip1080p) { selectQuality(chip1080p, 1920, 1080, 8_000_000) }
        addChipTouchFeedback(chip4k)    { selectQuality(chip4k,    3840, 2160, 40_000_000) }
    }

    private fun selectQuality(chip: TextView, w: Int, h: Int, bitrate: Int) {
        if (selectedWidth == w && selectedHeight == h && selectedBitrate == bitrate) return
        selectedWidth   = w
        selectedHeight  = h
        selectedBitrate = bitrate
        listOf(chip720p, chip1080p, chip4k).forEach { setChipInactive(it) }
        setChipActive(chip)
        updateSliderMax()
        updateFileSizeEstimate()
        if (!isRecording) closePreviewCamera { openPreviewCamera() }
    }

    // ── FPS Chips ──────────────────────────────────────────────────────────────

    private fun setupFpsChips() {
        selectFps(chip30fps, 30)
        addChipTouchFeedback(chip24fps) { selectFps(chip24fps, 24) }
        addChipTouchFeedback(chip30fps) { selectFps(chip30fps, 30) }
        addChipTouchFeedback(chip60fps) { selectFps(chip60fps, 60) }
    }

    private fun selectFps(chip: TextView, fps: Int) {
        if (selectedFps == fps) return
        selectedFps = fps
        listOf(chip24fps, chip30fps, chip60fps).forEach { setChipInactive(it) }
        setChipActive(chip)
        updateFileSizeEstimate()
        if (!isRecording) closePreviewCamera { openPreviewCamera() }
    }

    // ── Aspect Ratio Chips ─────────────────────────────────────────────────────

    private fun setupAspectRatioChips() {
        selectAspectRatio(chipAspect16_9, 16f, 9f)
        addChipTouchFeedback(chipAspect16_9) { selectAspectRatio(chipAspect16_9, 16f, 9f) }
        addChipTouchFeedback(chipAspect4_3)  { selectAspectRatio(chipAspect4_3,  4f,  3f) }
        addChipTouchFeedback(chipAspect1_1)  { selectAspectRatio(chipAspect1_1,  1f,  1f) }
    }

    private fun selectAspectRatio(chip: TextView, w: Float, h: Float) {
        if (aspectRatioW == w && aspectRatioH == h) return
        aspectRatioW = w
        aspectRatioH = h
        listOf(chipAspect16_9, chipAspect4_3, chipAspect1_1).forEach { setChipInactive(it) }
        setChipActive(chip)
        applyAspectRatioToPreview()
    }

    // ── Duration Slider ────────────────────────────────────────────────────────

    @Suppress("InlinedApi")
    private fun setupDurationSlider() {
        seekDuration.progress = 10
        tvDurationValue.text = "10 min"
        seekDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val mins = maxOf(1, progress)
                selectedDurationMin = mins
                tvDurationValue.text = "$mins min"
                updateFileSizeEstimate()
                if (fromUser) {
                    sb.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                sb.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                sb.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        })
    }

    private fun updateSliderMax() {
        val maxMin = when (selectedBitrate) {
            2_000_000  -> 133
            8_000_000  -> 33
            40_000_000 -> 6
            else       -> 33
        }
        seekDuration.max = maxMin
        if (seekDuration.progress > maxMin) seekDuration.progress = maxMin
        updateFileSizeEstimate()
    }

    private fun updateFileSizeEstimate() {
        val fpsFactor   = selectedFps / 30.0
        val durationSec = selectedDurationMin * 60
        val sizeMb      = (selectedBitrate * fpsFactor * durationSec) / 8 / 1_000_000
        tvFileSizeEstimate.text = "≈ ${sizeMb.toInt()} MB / clip"
    }

    // ── Toggle Button ──────────────────────────────────────────────────────────

    @Suppress("InlinedApi")
    private fun setupToggleButton() {
        btnToggleRecording.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
                    AnimationUtils.animateScalePress(
                        v,
                        targetScale = 0.94f,
                        stiffness = AnimationUtils.SPRING_STIFFNESS_PRIMARY,
                        damping = AnimationUtils.SPRING_DAMPING_PRIMARY
                    )
                }
                MotionEvent.ACTION_UP -> {
                    if (isRecording) stopRecording() else startRecording()
                }
                MotionEvent.ACTION_CANCEL -> {
                    AnimationUtils.animateScaleRelease(
                        v,
                        stiffness = AnimationUtils.SPRING_STIFFNESS_PRIMARY,
                        damping = AnimationUtils.SPRING_DAMPING_PRIMARY
                    )
                }
            }
            true
        }
    }

    private fun startRecording() {
        closePreviewCamera {
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra("width",     selectedWidth)
                putExtra("height",    selectedHeight)
                putExtra("bitrate",   selectedBitrate)
                putExtra("fps",       selectedFps)
                putExtra("chunk_ms",  selectedDurationMin * 60 * 1000L)
            }
            startForegroundService(intent)
            textureView.visibility = View.INVISIBLE
            setRecordingUI(true)
        }
    }

    private fun stopRecording() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        }
        startService(intent)
        textureView.visibility = View.VISIBLE
        setRecordingUI(false)
        openPreviewCamera()
    }

    // ── UI State ───────────────────────────────────────────────────────────────

    private fun setRecordingUI(recording: Boolean) {
        isRecording = recording
        if (recording) {
            btnToggleRecording.text = "Stop Recording"
            btnToggleRecording.setBackgroundColor(getColor(R.color.mango_active))
            vStatusDot.setBackgroundColor(getColor(R.color.mango_active))
            tvStatus.text = "REC"
            startStatusDotBreathing()
            setControlsEnabled(false)
        } else {
            btnToggleRecording.text = "Start Recording"
            btnToggleRecording.setBackgroundColor(getColor(R.color.mango_idle))
            vStatusDot.setBackgroundColor(getColor(R.color.mango_dot_ready))
            tvStatus.text = "IDLE"
            stopStatusDotBreathing()
            setControlsEnabled(true)
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.38f
        listOf(chip720p, chip1080p, chip4k,
            chip24fps, chip30fps, chip60fps,
            chipAspect16_9, chipAspect4_3, chipAspect1_1,
            seekDuration).forEach {
            it.isEnabled = enabled
            it.alpha     = alpha
        }
    }

    private fun setChipActive(chip: TextView) {
        chip.setBackgroundColor(getColor(R.color.mango_accent))
        chip.setTextColor(getColor(R.color.mango_text_primary))
        AnimationUtils.animateScaleRelease(
            chip,
            stiffness = AnimationUtils.SPRING_STIFFNESS_SECONDARY,
            damping = AnimationUtils.SPRING_DAMPING_SECONDARY
        )
        chip.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun setChipInactive(chip: TextView) {
        chip.setBackgroundColor(getColor(R.color.mango_surface_elevated))
        chip.setTextColor(getColor(R.color.mango_text_secondary))
    }

    // ── Touch Feedback for Chips ───────────────────────────────────────────────

    @Suppress("InlinedApi")
    private fun addChipTouchFeedback(chip: TextView, onClicked: () -> Unit) {
        chip.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
                    AnimationUtils.animateScalePress(
                        v,
                        targetScale = 0.93f,
                        stiffness = AnimationUtils.SPRING_STIFFNESS_SECONDARY,
                        damping = AnimationUtils.SPRING_DAMPING_SECONDARY
                    )
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onClicked()
                }
            }
            true
        }
    }

    // ── Status Dot Breathing Animation ─────────────────────────────────────────

    private var statusDotAnimator: ObjectAnimator? = null

    private fun startStatusDotBreathing() {
        if (statusDotAnimator != null) return
        statusDotAnimator = ObjectAnimator.ofFloat(vStatusDot, "alpha", 0.5f, 1.0f).apply {
            duration = 1200  // 1200ms ≈ slow breathing
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopStatusDotBreathing() {
        statusDotAnimator?.cancel()
        statusDotAnimator = null
        vStatusDot.alpha = 1.0f
    }

    // ── Camera Preview ─────────────────────────────────────────────────────────

    private fun openPreviewCamera() {
        if (!hasPermissions()) return
        cameraThread = HandlerThread("MangoCamera").also { it.start() }
        cameraHandler = Handler(cameraThread.looper)

        val manager  = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.first { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

        if (textureView.isAvailable) {
            openCamera(manager, cameraId)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    openCamera(manager, cameraId)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }

    private fun openCamera(manager: CameraManager, cameraId: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreviewSession(camera)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, cameraHandler)
    }

    private fun startPreviewSession(camera: CameraDevice) {
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(selectedWidth, selectedHeight)
        val surface = Surface(texture)

        camera.createCaptureSession(listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        .apply {
                            addTarget(surface)
                            set(
                                android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                android.util.Range(selectedFps, selectedFps)
                            )
                        }
                        .build()
                    session.setRepeatingRequest(request, null, cameraHandler)
                    runOnUiThread { applyAspectRatioToPreview() }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, cameraHandler)
    }

    private fun closePreviewCamera(onClosed: () -> Unit) {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        if (::cameraThread.isInitialized) cameraThread.quitSafely()
        onClosed()
    }

    // ── Preview Transform ──────────────────────────────────────────────────────

    private fun applyAspectRatioToPreview() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return

        val sensorOrientation = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        if (viewW == 0f || viewH == 0f) return

        // Device is landscape. Sensor is 90°, so buffer is portrait-shaped (h > w).
        // TextureView renders the buffer as-is (portrait), then we rotate -90°.
        // We need to scale the buffer so that AFTER rotation it matches our target aspect.

        // Target: how the final rotated preview should look in landscape view space
        val targetAspect = aspectRatioW / aspectRatioH  // e.g. 16/9 = 1.77 (wide)

        // Buffer dimensions (portrait-shaped for 90° sensor)
        val bufW = selectedWidth.toFloat()   // e.g. 1280 (this becomes height after rotation)
        val bufH = selectedHeight.toFloat()  // e.g. 720  (this becomes width after rotation)

        // After -90° rotation in view space:
        // rendered width  = bufH scaled to view
        // rendered height = bufW scaled to view
        // Default scale just to fit buffer in view before rotation:
        // TextureView fills its own size, so buffer is stretched to viewW x viewH before rotation.
        // We need to counteract that stretch and apply correct aspect.

        // Without any matrix, TextureView stretches buffer to fill (viewW x viewH).
        // After rotation the content appears as (viewH x viewW) effective.
        // We need to scale so effective content = targetAspect.

        val matrix = android.graphics.Matrix()
        val cx = viewW / 2f
        val cy = viewH / 2f

        val rotate = when (sensorOrientation) {
            90  -> -90f
            270 ->  90f
            180 -> 180f
            else -> 0f
        }

        if (sensorOrientation == 90 || sensorOrientation == 270) {
            // After rotation, natural content aspect in view = viewH/viewW (portrait in landscape view)
            // We want targetAspect (landscape ratio)
            // Scale X and Y in pre-rotation space to achieve this

            // To get targetAspect after -90° rotation:
            // effective_w = scaleY * viewH  (Y axis becomes width after rotation)
            // effective_h = scaleX * viewW  (X axis becomes height after rotation)
            // effective_w / effective_h = targetAspect
            // → scaleY * viewH / (scaleX * viewW) = targetAspect

            // Fit to view: make effective_w = viewW (fill width)
            // scaleY * viewH = viewW  → scaleY = viewW / viewH
            // scaleX * viewW = viewW / targetAspect → scaleX = 1 / targetAspect

            val scaleX = 1f / targetAspect
            val scaleY = viewW / viewH

            matrix.postScale(scaleX, scaleY, cx, cy)
        }

        matrix.postRotate(rotate, cx, cy)
        textureView.setTransform(matrix)
    }

    // ── Permissions ────────────────────────────────────────────────────────────

    private fun requestPermissions() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (!hasPermissions()) ActivityCompat.requestPermissions(this, perms, PERM_REQUEST)
    }

    private fun hasPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (!isRecording) openPreviewCamera()
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        isRecording = prefs.getBoolean("recording_active", false)
        setRecordingUI(isRecording)
        if (!isRecording && hasPermissions()) openPreviewCamera()
    }

    override fun onPause() {
        super.onPause()
        if (!isRecording) closePreviewCamera {}
    }
}