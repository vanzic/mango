package com.vixcy.mango

import android.Manifest
import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.*
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.view.ViewGroup.MarginLayoutParams
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.work.WorkInfo
import androidx.work.WorkManager

class MainActivity : AppCompatActivity() {

    // ── UI — camera area ───────────────────────────────────────────────────────
    private lateinit var textureView: TextureView
    private lateinit var dimOverlay: View

    // ── Camera-overlay pills ───────────────────────────────────────────────────
    // Top-left: status pill (always visible — Ready / Rec)
    private lateinit var statusOverlayPill: LinearLayout
    private lateinit var vOverlayStatusDot: View
    private lateinit var tvOverlayStatus: TextView
    private var overlayDotAnimators: List<Animator>? = null

    // Bottom-left: upload countdown / Uploading… / Uploaded
    private lateinit var uploadOverlayPill: LinearLayout
    private lateinit var vUploadOverlayDot: View
    private lateinit var tvUploadOverlayStatus: TextView
    private var uploadOverlayDotAnimators: List<Animator>? = null
    // True while WorkManager shows RUNNING/ENQUEUED — suppresses countdown updates
    private var isCurrentlyUploading = false

    // ── Bottom panel ──────────────────────────────────────────────────────────
    private lateinit var bottomPanel: LinearLayout
    private lateinit var tvSummaryQuality: TextView
    private lateinit var tvSummaryFps: TextView
    private lateinit var tvSummaryDuration: TextView

    // ── Record button (inner circle View + outer FrameLayout tap target) ──────
    private lateinit var recordButtonArea: FrameLayout
    private lateinit var btnToggleRecording: View   // inner coloured circle

    // ── Settings ──────────────────────────────────────────────────────────────
    private lateinit var btnSettings: ImageView
    private lateinit var settingsSheet: LinearLayout
    private var settingsOpen = false

    // ── Settings-sheet controls ───────────────────────────────────────────────
    private lateinit var tvLabelQuality: TextView
    private lateinit var tvLabelFrameRate: TextView
    private lateinit var tvLabelDuration: TextView
    private lateinit var tvDurationValue: TextView
    private lateinit var tvFileSizeEstimate: TextView
    private lateinit var tvFileSizeEstimateMain: TextView
    private lateinit var seekDuration: SeekBar

    // ── Upload state guards ────────────────────────────────────────────────────
    private var wasUploadActive = false
    private val uploadChipHandler = Handler(Looper.getMainLooper())
    private val uploadChipHideRunnable = Runnable { hideUploadOverlay() }

    // ── Segmented controls ────────────────────────────────────────────────────
    private lateinit var qualityControl: SegmentedControl
    private lateinit var fpsControl: SegmentedControl

    // ── Recording timer ────────────────────────────────────────────────────────
    // Fires every second while recording; updates "upload in MM:SS" countdown.
    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingStartMs = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isCurrentlyUploading) {
                val totalElapsedMs  = SystemClock.elapsedRealtime() - recordingStartMs
                val chunkDurationMs = selectedDurationMin * 60 * 1000L
                val chunkElapsedMs  = totalElapsedMs % chunkDurationMs
                val uploadInMs      = chunkDurationMs - chunkElapsedMs
                val uploadInSec     = ((uploadInMs + 999) / 1000).toInt().coerceAtLeast(0)
                tvUploadOverlayStatus.text =
                    "upload in  %02d:%02d".format(uploadInSec / 60, uploadInSec % 60)
            }
            timerHandler.postDelayed(this, 1000)
        }
    }

    // ── Camera ─────────────────────────────────────────────────────────────────
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var sensorOrientation = 90
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler

    // ── State ──────────────────────────────────────────────────────────────────
    private var isRecording = false
    private var baseVideoHeight  = 720
    private var selectedWidth    = 1280
    private var selectedHeight   = 720
    private var selectedBitrate  = 2_000_000
    private var selectedFps      = 30
    private var selectedDurationMin = 10
    private var lastEstimatedSizeMb = -1
    private var hasAnimatedFirstAppearance = false

    // ── Record button GradientDrawable — full circle (29dp radius = 58dp ÷ 2) ─
    private val btnRecordDrawable by lazy {
        GradientDrawable().apply {
            cornerRadius = 29f * resources.displayMetrics.density
            setColor(getColor(R.color.mango_accent))
        }
    }
    private var btnColorAnimator: ValueAnimator? = null

    // ── File size ValueAnimator (cancellation guard) ──────────────────────────
    private var fileSizeAnimator: ValueAnimator? = null

    companion object {
        private const val PERM_REQUEST   = 101
        private const val PREFS          = "mango_prefs"
        private const val BREATHING_REC_MS = 900L
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        bindViews()
        setupEdgeToEdge()
        requestPermissions()
        computeAndUpdateDimensions()
        restoreState()
        setupQualityControl()
        setupFpsControl()
        setupDurationSlider()
        setupRecordButton()
        setupSettingsButton()
        observeUploadState()

        // Hide settings sheet below panel (measured but off-screen)
        settingsSheet.doOnLayout { sheet ->
            sheet.translationY = sheet.height.toFloat()
            sheet.visibility = View.INVISIBLE
        }

        // Initial state — no animation on first render
        updateFileSizeEstimate(animate = false)
        updateSummaryChips()

        // Staggered entry after layout
        if (!hasAnimatedFirstAppearance) {
            hasAnimatedFirstAppearance = true
            window.decorView.post {
                AnimationUtils.animateStaggeredEntry(listOf(
                    statusOverlayPill,
                    tvSummaryQuality,
                    tvSummaryFps,
                    tvSummaryDuration,
                    recordButtonArea,
                    btnSettings
                ))
            }
        }
    }

    private fun bindViews() {
        textureView          = findViewById(R.id.textureView)
        dimOverlay           = findViewById(R.id.dimOverlay)

        statusOverlayPill    = findViewById(R.id.statusOverlayPill)
        vOverlayStatusDot    = findViewById(R.id.vOverlayStatusDot)
        tvOverlayStatus      = findViewById(R.id.tvOverlayStatus)

        uploadOverlayPill    = findViewById(R.id.uploadOverlayPill)
        vUploadOverlayDot    = findViewById(R.id.vUploadOverlayDot)
        tvUploadOverlayStatus = findViewById(R.id.tvUploadOverlayStatus)

        bottomPanel          = findViewById(R.id.bottomPanel)
        tvSummaryQuality     = findViewById(R.id.tvSummaryQuality)
        tvSummaryFps         = findViewById(R.id.tvSummaryFps)
        tvSummaryDuration    = findViewById(R.id.tvSummaryDuration)

        recordButtonArea     = findViewById(R.id.recordButtonArea)
        btnToggleRecording   = findViewById(R.id.btnToggleRecording)

        btnSettings          = findViewById(R.id.btnSettings)
        settingsSheet        = findViewById(R.id.settingsSheet)

        tvLabelQuality       = findViewById(R.id.tvLabelQuality)
        tvLabelFrameRate     = findViewById(R.id.tvLabelFrameRate)
        tvLabelDuration      = findViewById(R.id.tvLabelDuration)
        tvDurationValue      = findViewById(R.id.tvDurationValue)
        tvFileSizeEstimate   = findViewById(R.id.tvFileSizeEstimate)
        tvFileSizeEstimateMain = findViewById(R.id.tvFileSizeEstimateMain)
        seekDuration         = findViewById(R.id.seekDuration)

        // Programmatic circle shape — color animates accent ↔ active
        btnToggleRecording.background = btnRecordDrawable
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Status pill top margin: system bar height + 24dp base margin
            // Increased from 16dp to 24dp to provide better clearance from the notch/status bar.
            statusOverlayPill.updateLayoutParams<MarginLayoutParams> {
                topMargin = systemBars.top + (24 * resources.displayMetrics.density).toInt()
            }

            // Bottom panel padding: keep original 16dp top, but add system bar bottom to original 32dp
            bottomPanel.updatePadding(
                bottom = systemBars.bottom + (32 * resources.displayMetrics.density).toInt()
            )

            insets
        }
    }

    private fun restoreState() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        isRecording = prefs.getBoolean("recording_active", false)
        if (isRecording) {
            textureView.visibility = View.INVISIBLE
            textureView.alpha = 0f
            uploadOverlayPill.alpha = 1f
        } else {
            textureView.visibility = View.VISIBLE
            textureView.alpha = 1f
            uploadOverlayPill.alpha = 0f
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Summary chips
    // ══════════════════════════════════════════════════════════════════════════

    private fun updateSummaryChips() {
        tvSummaryQuality.text  = when (baseVideoHeight) { 720 -> "720p"; 1080 -> "1080p"; else -> "4K" }
        tvSummaryFps.text      = "$selectedFps fps"
        tvSummaryDuration.text = "$selectedDurationMin min"
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Settings panel
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupSettingsButton() {
        btnSettings.setOnClickListener {
            AnimationUtils.hapticFeedback(it, AnimationUtils.HapticWeight.LIGHT)
            if (settingsOpen) hideSettingsPanel() else showSettingsPanel()
        }
    }

    private fun showSettingsPanel() {
        settingsOpen = true
        settingsSheet.visibility = View.VISIBLE
        SpringAnimation(settingsSheet, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring.stiffness   = AnimationUtils.SPRING_STIFFNESS_PRIMARY
            spring.dampingRatio = AnimationUtils.SPRING_DAMPING_PRIMARY
            start()
        }
        dimOverlay.animate()
            .alpha(0.55f)
            .setDuration(AnimationUtils.DURATION_ELEMENT)
            .setInterpolator(DecelerateInterpolator())
            .start()
        dimOverlay.isClickable = true
        dimOverlay.isFocusable = true
        dimOverlay.setOnClickListener { hideSettingsPanel() }
    }

    private fun hideSettingsPanel() {
        settingsOpen = false
        val targetY = settingsSheet.height.toFloat()
        SpringAnimation(settingsSheet, DynamicAnimation.TRANSLATION_Y, targetY).apply {
            spring.stiffness    = AnimationUtils.SPRING_STIFFNESS_PRIMARY
            spring.dampingRatio = AnimationUtils.SPRING_DAMPING_PRIMARY
            addEndListener { _, _, _, _ -> settingsSheet.visibility = View.INVISIBLE }
            start()
        }
        dimOverlay.animate()
            .alpha(0f)
            .setDuration(AnimationUtils.DURATION_ELEMENT)
            .setInterpolator(DecelerateInterpolator())
            .start()
        dimOverlay.isClickable = false
        dimOverlay.isFocusable = false
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Segmented controls
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupQualityControl() {
        qualityControl = SegmentedControl(
            track    = findViewById(R.id.qualitySelector),
            pill     = findViewById(R.id.qualityPill),
            segments = listOf(
                findViewById(R.id.seg720p),
                findViewById(R.id.seg1080p),
                findViewById(R.id.seg4k)
            )
        )
        val defaultIndex = when (baseVideoHeight) { 720 -> 0; 1080 -> 1; else -> 2 }
        qualityControl.setup(defaultIndex) { index ->
            val (h, bitrate) = when (index) {
                0    -> 720  to 2_000_000
                1    -> 1080 to 8_000_000
                else -> 2160 to 40_000_000
            }
            if (baseVideoHeight == h && selectedBitrate == bitrate) return@setup
            baseVideoHeight = h
            selectedBitrate = bitrate
            computeAndUpdateDimensions()
            AnimationUtils.pulseLabel(tvLabelQuality)
            updateSliderMax()
            updateFileSizeEstimate()
            updateSummaryChips()
            if (!isRecording) closePreviewCamera { openPreviewCamera() }
        }
    }

    private fun setupFpsControl() {
        fpsControl = SegmentedControl(
            track    = findViewById(R.id.fpsSelector),
            pill     = findViewById(R.id.fpsPill),
            segments = listOf(
                findViewById(R.id.seg24fps),
                findViewById(R.id.seg30fps),
                findViewById(R.id.seg60fps)
            )
        )
        val defaultIndex = when (selectedFps) { 24 -> 0; 30 -> 1; else -> 2 }
        fpsControl.setup(defaultIndex) { index ->
            val fps = when (index) { 0 -> 24; 1 -> 30; else -> 60 }
            if (selectedFps == fps) return@setup
            selectedFps = fps
            AnimationUtils.pulseLabel(tvLabelFrameRate)
            updateSliderMax()   // fps affects 2 GB ceiling — recalculate first
            updateSummaryChips()
            if (!isRecording) closePreviewCamera { openPreviewCamera() }
        }
        if (!is60fpsSupported()) fpsControl.disableSegment(2)
    }

    /** Returns true if the back camera supports ≥60 fps in high-speed config. */
    private fun is60fpsSupported(): Boolean = try {
        val manager  = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return false
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        map?.highSpeedVideoFpsRanges?.any { it.upper >= 60 } ?: false
    } catch (e: Exception) { false }

    // ══════════════════════════════════════════════════════════════════════════
    // Dimensions
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Derives selectedWidth/Height from the quality tier at native 16:9 aspect ratio.
     * Width is rounded DOWN to the nearest even number (H.264 requirement).
     */
    private fun computeAndUpdateDimensions() {
        selectedHeight = baseVideoHeight
        val rawWidth   = baseVideoHeight * 16 / 9
        selectedWidth  = if (rawWidth % 2 == 0) rawWidth else rawWidth - 1
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Duration slider
    // ══════════════════════════════════════════════════════════════════════════

    @Suppress("InlinedApi")
    private fun setupDurationSlider() {
        seekDuration.progress    = 10
        tvDurationValue.text     = "10 min"
        seekDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val mins = maxOf(1, progress)
                if (mins == selectedDurationMin) return
                selectedDurationMin = mins
                tvDurationValue.text = "$mins min"
                if (fromUser) {
                    updateFileSizeEstimate()
                    updateSummaryChips()
                    AnimationUtils.pulseLabel(tvLabelDuration)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                AnimationUtils.hapticFeedback(sb, AnimationUtils.HapticWeight.LIGHT)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                AnimationUtils.hapticFeedback(sb, AnimationUtils.HapticWeight.LIGHT)
            }
        })
    }

    /**
     * Derives the slider ceiling so the clip always stays strictly under 2 GB
     * (Telegram free-tier limit).  Uses 1990 MB as the cap (10 MB safety margin).
     * Called whenever bitrate OR fps changes — both affect the ceiling.
     */
    private fun updateSliderMax() {
        val fpsFactor = selectedFps / 30.0
        val limitMb   = 1990.0
        val maxSec    = (limitMb * 8 * 1_000_000) / (selectedBitrate * fpsFactor)
        val maxMin    = maxOf(1, (maxSec / 60).toInt())

        seekDuration.max = maxMin
        if (seekDuration.progress > maxMin) seekDuration.progress = maxMin
        updateFileSizeEstimate()
    }

    private fun updateFileSizeEstimate(animate: Boolean = true) {
        val fpsFactor   = selectedFps / 30.0
        val durationSec = selectedDurationMin * 60
        val sizeMb      = ((selectedBitrate * fpsFactor * durationSec) / 8 / 1_000_000).toInt()
        if (sizeMb == lastEstimatedSizeMb) return

        if (animate && lastEstimatedSizeMb > 0) {
            fileSizeAnimator?.cancel()
            fileSizeAnimator = ValueAnimator.ofInt(lastEstimatedSizeMb, sizeMb).apply {
                duration = 480L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val v = it.animatedValue as Int
                    val formatted = formatClipSize(v)
                    tvFileSizeEstimate.text = formatted
                    tvFileSizeEstimateMain.text = formatted
                }
                start()
            }
        } else {
            val formatted = formatClipSize(sizeMb)
            tvFileSizeEstimate.text = formatted
            tvFileSizeEstimateMain.text = formatted
        }
        lastEstimatedSizeMb = sizeMb
    }

    /** Below 1000 MB → "≈ 720 MB / clip".  At or above → "≈ 1.4 GB / clip". */
    private fun formatClipSize(mb: Int): String =
        if (mb < 1000) "≈ $mb MB / clip"
        else           "≈ ${"%.1f".format(mb / 1000.0)} GB / clip"

    // ══════════════════════════════════════════════════════════════════════════
    // Record button — circular tap target
    // ══════════════════════════════════════════════════════════════════════════

    @Suppress("InlinedApi")
    private fun setupRecordButton() {
        setRecordingUI(isRecording)

        recordButtonArea.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    AnimationUtils.hapticFeedback(v, AnimationUtils.HapticWeight.MEDIUM)
                    AnimationUtils.animateScalePress(
                        btnToggleRecording, targetScale = 0.90f,
                        stiffness = AnimationUtils.SPRING_STIFFNESS_PRIMARY,
                        damping   = AnimationUtils.SPRING_DAMPING_PRIMARY
                    )
                }
                MotionEvent.ACTION_UP -> {
                    AnimationUtils.animateScaleRelease(
                        btnToggleRecording,
                        stiffness = AnimationUtils.SPRING_STIFFNESS_PRIMARY,
                        damping   = AnimationUtils.SPRING_DAMPING_PRIMARY
                    )
                    v.performClick()
                    // Auto-close settings panel when recording starts/stops
                    if (settingsOpen) hideSettingsPanel()
                    if (isRecording) stopRecording() else startRecording()
                }
                MotionEvent.ACTION_CANCEL -> {
                    AnimationUtils.animateScaleRelease(
                        btnToggleRecording,
                        stiffness = AnimationUtils.SPRING_STIFFNESS_PRIMARY,
                        damping   = AnimationUtils.SPRING_DAMPING_PRIMARY
                    )
                }
            }
            true
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Recording flow
    // ══════════════════════════════════════════════════════════════════════════

    private fun startRecording() {
        closePreviewCamera {
            recordingStartMs = SystemClock.elapsedRealtime()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong("recording_start_ms", recordingStartMs)
                .apply()

            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra("width",    selectedWidth)
                putExtra("height",   selectedHeight)
                putExtra("bitrate",  selectedBitrate)
                putExtra("fps",      selectedFps)
                putExtra("chunk_ms", selectedDurationMin * 60 * 1000L)
            }
            startForegroundService(intent)

            textureView.animate()
                .alpha(0f)
                .setDuration(AnimationUtils.DURATION_SCREEN)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { textureView.visibility = View.INVISIBLE }
                .start()

            setRecordingUI(true)
        }
    }

    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP
        })

        textureView.alpha = 0f
        textureView.visibility = View.VISIBLE
        textureView.animate()
            .alpha(1f)
            .setDuration(AnimationUtils.DURATION_SCREEN)
            .setInterpolator(DecelerateInterpolator())
            .start()

        setRecordingUI(false)
        openPreviewCamera()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UI state machine
    // ══════════════════════════════════════════════════════════════════════════

    private fun setRecordingUI(recording: Boolean) {
        isRecording = recording
        if (recording) {
            // ── Inner circle: accent → active (red) ───────────────────────────
            animateButtonColor(
                from = getColor(R.color.mango_accent),
                to   = getColor(R.color.mango_active)
            )

            // ── Status overlay pill (top-left) ────────────────────────────────
            vOverlayStatusDot.setBackgroundResource(R.drawable.dot_recording)
            tvOverlayStatus.text = "Rec"
            startOverlayDotBreathing()

            // ── Upload countdown pill: fade in ────────────────────────────────
            tvUploadOverlayStatus.text =
                "upload in  %02d:%02d".format(selectedDurationMin, 0)
            vUploadOverlayDot.visibility = View.GONE
            uploadOverlayPill.animate().alpha(1f)
                .setDuration(AnimationUtils.DURATION_ELEMENT)
                .setInterpolator(DecelerateInterpolator()).start()
            startElapsedTimer()

            setControlsEnabled(false)
        } else {
            // ── Inner circle: active (red) → accent ───────────────────────────
            stopElapsedTimer()
            animateButtonColor(
                from = getColor(R.color.mango_active),
                to   = getColor(R.color.mango_accent)
            )

            // ── Status overlay pill (top-left) ────────────────────────────────
            stopOverlayDotBreathing()
            vOverlayStatusDot.setBackgroundResource(R.drawable.dot_ready)
            tvOverlayStatus.text = "Ready"

            // ── Upload countdown pill: fade out ───────────────────────────────
            uploadOverlayPill.animate().alpha(0f)
                .setDuration(AnimationUtils.DURATION_ELEMENT)
                .setInterpolator(DecelerateInterpolator()).start()

            setControlsEnabled(true)
        }
    }

    private fun animateButtonColor(from: Int, to: Int) {
        if (from == to) return
        btnColorAnimator?.cancel()
        btnColorAnimator = AnimationUtils.animateButtonColor(btnRecordDrawable, from, to)
    }

    // ── Elapsed timer ─────────────────────────────────────────────────────────

    private fun startElapsedTimer() {
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.postDelayed(timerRunnable, 500)
    }

    private fun stopElapsedTimer() {
        timerHandler.removeCallbacks(timerRunnable)
    }

    // ── Overlay status dot breathing ──────────────────────────────────────────

    private fun startOverlayDotBreathing() {
        stopOverlayDotBreathing()
        overlayDotAnimators = AnimationUtils.startBreathing(vOverlayStatusDot, BREATHING_REC_MS)
    }

    private fun stopOverlayDotBreathing() {
        overlayDotAnimators?.forEach { it.cancel() }
        overlayDotAnimators = null
        vOverlayStatusDot.alpha  = 1f
        vOverlayStatusDot.scaleX = 1f
        vOverlayStatusDot.scaleY = 1f
    }

    // ── Controls dim/enable ───────────────────────────────────────────────────

    private fun setControlsEnabled(enabled: Boolean) {
        qualityControl.setEnabled(enabled)
        fpsControl.setEnabled(enabled)
        seekDuration.isEnabled = enabled
        AnimationUtils.animateAlphaTransition(seekDuration,    if (enabled) 1.0f else 0.38f)
        AnimationUtils.animateAlphaTransition(btnSettings,     if (enabled) 1.0f else 0.38f)
        btnSettings.isClickable  = enabled
        btnSettings.isFocusable  = enabled
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Upload overlay pill
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Drives the bottom-left upload overlay pill via WorkManager LiveData.
     *
     * RUNNING/ENQUEUED → "● Uploading..."  (blue dot, pulsing)
     * Active → done    → "● Uploaded"      (green dot, 3 s then countdown resumes)
     * FAILED           → pill fades out    (silent, no drama)
     * Idle cold-open   → no change         (wasUploadActive guard)
     */
    private fun observeUploadState() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(UploadWorker.UPLOAD_TAG)
            .observe(this) { workInfos ->
                val active    = workInfos.filter {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
                val anyFailed = workInfos.any { it.state == WorkInfo.State.FAILED }

                when {
                    active.isNotEmpty() -> {
                        uploadChipHandler.removeCallbacks(uploadChipHideRunnable)
                        wasUploadActive = true
                        showUploadingOverlay()
                    }
                    wasUploadActive -> {
                        wasUploadActive = false
                        uploadChipHandler.removeCallbacks(uploadChipHideRunnable)
                        if (anyFailed) {
                            showUploadCountdown()
                        } else {
                            showUploadedOverlay()
                            uploadChipHandler.postDelayed(uploadChipHideRunnable, 3000)
                        }
                    }
                }
            }
    }

    /** Blue pulsing dot + "Uploading…" — suppresses countdown updates. */
    private fun showUploadingOverlay() {
        isCurrentlyUploading = true
        stopUploadOverlayDotBreathing()
        vUploadOverlayDot.setBackgroundResource(R.drawable.dot_blue)
        vUploadOverlayDot.visibility = View.VISIBLE
        tvUploadOverlayStatus.text = "Uploading..."
        uploadOverlayPill.animate()
            .alpha(1f).setDuration(AnimationUtils.DURATION_ELEMENT)
            .setInterpolator(DecelerateInterpolator()).start()
        uploadOverlayDotAnimators = AnimationUtils.startBreathing(vUploadOverlayDot, 700L)
    }

    /** Green dot + "Uploaded" with spring announcement. */
    private fun showUploadedOverlay() {
        isCurrentlyUploading = false
        stopUploadOverlayDotBreathing()
        vUploadOverlayDot.setBackgroundResource(R.drawable.dot_ready)
        vUploadOverlayDot.visibility = View.VISIBLE
        tvUploadOverlayStatus.text = "Uploaded"
        AnimationUtils.announceScale(uploadOverlayPill, peakScale = 1.05f)
        uploadOverlayPill.animate()
            .alpha(1f).setDuration(AnimationUtils.DURATION_MICRO)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    /** Resume "upload in MM:SS" countdown — called after Uploaded display expires. */
    private fun showUploadCountdown() {
        isCurrentlyUploading = false
        stopUploadOverlayDotBreathing()
        vUploadOverlayDot.visibility = View.GONE
        if (isRecording) {
            uploadOverlayPill.animate()
                .alpha(1f).setDuration(AnimationUtils.DURATION_ELEMENT)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /**
     * Hides the upload pill — called 3 s after "Uploaded".
     * If still recording, transitions back to countdown; otherwise fades out.
     */
    private fun hideUploadOverlay() {
        if (isRecording) {
            showUploadCountdown()
        } else {
            stopUploadOverlayDotBreathing()
            uploadOverlayPill.animate()
                .alpha(0f).setDuration(AnimationUtils.DURATION_COMPONENT)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun stopUploadOverlayDotBreathing() {
        uploadOverlayDotAnimators?.forEach { it.cancel() }
        uploadOverlayDotAnimators = null
        vUploadOverlayDot.alpha  = 1f
        vUploadOverlayDot.scaleX = 1f
        vUploadOverlayDot.scaleY = 1f
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Camera preview
    // ══════════════════════════════════════════════════════════════════════════

    private fun openPreviewCamera() {
        if (!hasPermissions()) return
        cameraThread  = HandlerThread("MangoCamera").also { it.start() }
        cameraHandler = Handler(cameraThread.looper)

        val manager  = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.first { id ->
            val char = manager.getCameraCharacteristics(id)
            if (char.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                sensorOrientation = char.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                true
            } else false
        }

        if (textureView.isAvailable) {
            openCamera(manager, cameraId)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    openCamera(manager, cameraId)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    configurePreviewTransform()
                }
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
        // Set buffer size to the CAMERA's native landscape dimensions.
        // We will rotate this buffer in the TextureView's matrix.
        texture.setDefaultBufferSize(selectedWidth, selectedHeight)
        val surface = Surface(texture)

        camera.createCaptureSession(listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        .apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(selectedFps, selectedFps))
                        }.build()
                    session.setRepeatingRequest(request, null, cameraHandler)
                    runOnUiThread { configurePreviewTransform() }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, cameraHandler)
    }

    /**
     * Corrects the TextureView transform for portrait-locked orientation.
     * Uses the hardware sensor orientation and a precise 'Center Crop' scale.
     */
    private fun configurePreviewTransform() {
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        if (viewW == 0f || viewH == 0f) return

        val matrix = android.graphics.Matrix()
        val centerX = viewW / 2f
        val centerY = viewH / 2f

        // 1. Calculate the rotation required to bring the sensor's top to the screen's top.
        // For a portrait app (ROTATION_0) and a standard back sensor (90), this is 90.
        val rotation = windowManager.defaultDisplay.rotation
        val degrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val totalRotation = (sensorOrientation - degrees + 360) % 360
        matrix.postRotate(totalRotation.toFloat(), centerX, centerY)

        // 2. Calculate scale to fill (Center Crop).
        // If we rotated 90 or 270, the buffer's width/height are effectively swapped.
        val isRotated = (totalRotation == 90 || totalRotation == 270)
        val bufferW = if (isRotated) selectedHeight.toFloat() else selectedWidth.toFloat()
        val bufferH = if (isRotated) selectedWidth.toFloat() else selectedHeight.toFloat()

        val scaleX = viewW / bufferW
        val scaleY = viewH / bufferH
        val scale = maxOf(scaleX, scaleY)
        
        matrix.postScale(scale, scale, centerX, centerY)

        textureView.setTransform(matrix)
    }

    private fun closePreviewCamera(onClosed: () -> Unit) {
        captureSession?.close(); captureSession = null
        cameraDevice?.close();   cameraDevice   = null
        if (::cameraThread.isInitialized) cameraThread.quitSafely()
        onClosed()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Permissions
    // ══════════════════════════════════════════════════════════════════════════

    private fun requestPermissions() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (!hasPermissions()) ActivityCompat.requestPermissions(this, perms, PERM_REQUEST)
    }

    private fun hasPermissions() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED &&
        ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (!isRecording) openPreviewCamera()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        isRecording = prefs.getBoolean("recording_active", false)

        if (isRecording) {
            recordingStartMs = prefs.getLong("recording_start_ms", SystemClock.elapsedRealtime())
        }

        setRecordingUI(isRecording)
        if (!isRecording && hasPermissions()) openPreviewCamera()
    }

    override fun onPause() {
        super.onPause()
        stopElapsedTimer()
        if (settingsOpen) hideSettingsPanel()
        if (!isRecording) closePreviewCamera {}
    }
}
