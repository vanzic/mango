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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.work.WorkInfo
import androidx.work.WorkManager

class MainActivity : AppCompatActivity() {

    // ── UI — static views ─────────────────────────────────────────────────────
    private lateinit var textureView: TextureView
    private lateinit var statusChip: LinearLayout
    private lateinit var vStatusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvDurationValue: TextView
    private lateinit var tvFileSizeEstimate: TextView
    private lateinit var seekDuration: SeekBar

    // ── Camera-overlay pills ───────────────────────────────────────────────────
    // Top-left: status pill (always visible — Ready / Rec)
    private lateinit var statusOverlayPill: LinearLayout
    private lateinit var vOverlayStatusDot: View
    private lateinit var tvOverlayStatus: TextView
    private var overlayDotAnimators: List<Animator>? = null

    // Bottom-left: upload countdown / Uploading... / Uploaded
    private lateinit var uploadOverlayPill: LinearLayout
    private lateinit var vUploadOverlayDot: View
    private lateinit var tvUploadOverlayStatus: TextView
    private var uploadOverlayDotAnimators: List<Animator>? = null
    // True while WorkManager shows RUNNING/ENQUEUED — suppresses countdown updates
    private var isCurrentlyUploading = false

    // ── Record button ─────────────────────────────────────────────────────────
    private lateinit var btnToggleRecording: TextView

    // ── Section labels — pulsed on every control change ("system heard you") ──
    private lateinit var tvLabelQuality: TextView
    private lateinit var tvLabelFrameRate: TextView
    private lateinit var tvLabelDuration: TextView

    // ── Upload state guards ────────────────────────────────────────────────────
    private var wasUploadActive = false
    private val uploadChipHandler = Handler(Looper.getMainLooper())
    private val uploadChipHideRunnable = Runnable { hideUploadOverlay() }

    // ── Segmented controls ────────────────────────────────────────────────────
    private lateinit var qualityControl: SegmentedControl
    private lateinit var fpsControl: SegmentedControl

    // ── Recording timer ────────────────────────────────────────────────────────
    // Fires every second while recording.
    // Updates "upload in  MM:SS" countdown — suppressed while uploading.
    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingStartMs = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isCurrentlyUploading) {
                val totalElapsedMs = SystemClock.elapsedRealtime() - recordingStartMs
                val chunkDurationMs = selectedDurationMin * 60 * 1000L
                val chunkElapsedMs = totalElapsedMs % chunkDurationMs
                val uploadInMs = chunkDurationMs - chunkElapsedMs
                val uploadInSec = ((uploadInMs + 999) / 1000).toInt().coerceAtLeast(0)
                tvUploadOverlayStatus.text =
                    "upload in  %02d:%02d".format(uploadInSec / 60, uploadInSec % 60)
            }
            timerHandler.postDelayed(this, 1000)
        }
    }

    // ── Camera ─────────────────────────────────────────────────────────────────
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler

    // ── State ──────────────────────────────────────────────────────────────────
    private var isRecording = false
    // baseVideoHeight is the quality tier (720 / 1080 / 2160).
    // selectedWidth/Height are DERIVED from baseVideoHeight + aspectRatio each time either changes.
    private var baseVideoHeight = 720
    private var selectedWidth = 1280
    private var selectedHeight = 720
    private var selectedBitrate = 2_000_000
    private var selectedFps = 30
    private var selectedDurationMin = 10
    private var lastEstimatedSizeMb = -1
    private var hasAnimatedFirstAppearance = false

    // ── Record button GradientDrawable ────────────────────────────────────────
    // Corner radius = 16dp (pill button). Color animates orange ↔ red.
    private val btnRecordDrawable by lazy {
        GradientDrawable().apply {
            cornerRadius = 16f * resources.displayMetrics.density
            setColor(getColor(R.color.mango_accent))
        }
    }
    private var btnColorAnimator: ValueAnimator? = null

    // ── File size ValueAnimator (cancellation guard) ──────────────────────────
    private var fileSizeAnimator: ValueAnimator? = null

    // ── Status dot breathing ───────────────────────────────────────────────────
    private var statusDotAnimators: List<Animator>? = null

    companion object {
        private const val PERM_REQUEST = 101
        private const val PREFS = "mango_prefs"
        private const val BREATHING_IDLE_MS = 1400L   // "watching quietly"
        private const val BREATHING_REC_MS = 900L     // "actively recording"
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        bindViews()
        requestPermissions()
        computeAndUpdateDimensions()
        restoreState()
        setupQualityControl()
        setupFpsControl()
        setupDurationSlider()
        setupRecordButton()
        observeUploadState()

        // Initial file size — no animation on first render
        updateFileSizeEstimate(animate = false)

        // Staggered entry runs after layout pass so views have real dimensions
        if (!hasAnimatedFirstAppearance) {
            hasAnimatedFirstAppearance = true
            window.decorView.post {
                AnimationUtils.animateStaggeredEntry(listOf(
                    statusOverlayPill,
                    statusChip,
                    findViewById(R.id.qualitySelector),
                    tvLabelFrameRate,
                    findViewById(R.id.fpsSelector),
                    seekDuration,
                    tvFileSizeEstimate,
                    btnToggleRecording
                ))
            }
        }
    }

    private fun bindViews() {
        textureView        = findViewById(R.id.textureView)
        statusChip         = findViewById(R.id.statusChip)
        vStatusDot         = findViewById(R.id.vStatusDot)
        tvStatus           = findViewById(R.id.tvStatus)
        tvDurationValue    = findViewById(R.id.tvDurationValue)
        tvFileSizeEstimate = findViewById(R.id.tvFileSizeEstimate)
        seekDuration       = findViewById(R.id.seekDuration)

        statusOverlayPill    = findViewById(R.id.statusOverlayPill)
        vOverlayStatusDot    = findViewById(R.id.vOverlayStatusDot)
        tvOverlayStatus      = findViewById(R.id.tvOverlayStatus)
        uploadOverlayPill    = findViewById(R.id.uploadOverlayPill)
        vUploadOverlayDot    = findViewById(R.id.vUploadOverlayDot)
        tvUploadOverlayStatus = findViewById(R.id.tvUploadOverlayStatus)

        btnToggleRecording   = findViewById(R.id.btnToggleRecording)

        tvLabelQuality     = findViewById(R.id.tvLabelQuality)
        tvLabelFrameRate   = findViewById(R.id.tvLabelFrameRate)
        tvLabelDuration    = findViewById(R.id.tvLabelDuration)

        // Programmatic shape — animates corner radius (circle ↔ square) and fill color
        btnToggleRecording.background = btnRecordDrawable
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
                0    -> 720 to 2_000_000
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
            updateSliderMax()   // fps affects the 2 GB ceiling — recalculate before estimate
            if (!isRecording) closePreviewCamera { openPreviewCamera() }
        }
        // Never show an option the hardware can't deliver
        if (!is60fpsSupported()) fpsControl.disableSegment(2)
    }

    /** Returns true if the back camera supports ≥60fps in high-speed config. */
    private fun is60fpsSupported(): Boolean = try {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
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
        val rawWidth = (baseVideoHeight * 16 / 9)
        selectedWidth = if (rawWidth % 2 == 0) rawWidth else rawWidth - 1
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Duration slider
    // ══════════════════════════════════════════════════════════════════════════

    @Suppress("InlinedApi")
    private fun setupDurationSlider() {
        seekDuration.progress = 10
        tvDurationValue.text = "10 min"
        seekDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val mins = maxOf(1, progress)
                if (mins == selectedDurationMin) return
                selectedDurationMin = mins
                tvDurationValue.text = "$mins min"
                if (fromUser) {
                    updateFileSizeEstimate()
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
     * Derives the slider ceiling so the clip always stays strictly under 2 GB —
     * Telegram's free-tier upload limit.
     *
     * Inverts the size formula:
     *   sizeMb = bitrate × fpsFactor × durationSec / 8 / 1_000_000
     *   → maxSec = limitMb × 8 × 1_000_000 / (bitrate × fpsFactor)
     *
     * Uses 1990 MB (10 MB safety margin) so rounding never sneaks past 2 GB.
     * Clamped to a minimum of 1 minute so the slider is never unusable.
     *
     * Called whenever bitrate OR fps changes — both affect the ceiling.
     */
    private fun updateSliderMax() {
        val fpsFactor = selectedFps / 30.0
        val limitMb   = 1990.0           // strictly under 2 GB
        val maxSec    = (limitMb * 8 * 1_000_000) / (selectedBitrate * fpsFactor)
        val maxMin    = maxOf(1, (maxSec / 60).toInt())

        seekDuration.max = maxMin
        if (seekDuration.progress > maxMin) seekDuration.progress = maxMin
        updateFileSizeEstimate()
    }

    private fun updateFileSizeEstimate(animate: Boolean = true) {
        val fpsFactor = selectedFps / 30.0
        val durationSec = selectedDurationMin * 60
        val sizeMb = ((selectedBitrate * fpsFactor * durationSec) / 8 / 1_000_000).toInt()
        if (sizeMb == lastEstimatedSizeMb) return

        val newText = formatClipSize(sizeMb)

        if (animate && lastEstimatedSizeMb > 0) {
            fileSizeAnimator?.cancel()
            // Animate via MB integer internally; display text is reformatted each tick
            fileSizeAnimator = AnimationUtils.animateValueChange(
                tvFileSizeEstimate, lastEstimatedSizeMb, sizeMb,
                formatValue = { mb -> formatClipSize(mb) }
            )
        } else {
            tvFileSizeEstimate.text = newText
        }
        lastEstimatedSizeMb = sizeMb
    }

    /** Below 1000 MB → "≈ 720 MB / clip". At or above → "≈ 1.4 GB / clip". */
    private fun formatClipSize(mb: Int): String {
        return if (mb < 1000) {
            "≈ $mb MB / clip"
        } else {
            val gb = mb / 1000.0
            "≈ ${"%.1f".format(gb)} GB / clip"
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Record button — circular tap target with morphing inner shape
    // ══════════════════════════════════════════════════════════════════════════

    @Suppress("InlinedApi")
    private fun setupRecordButton() {
        setRecordingUI(isRecording)

        btnToggleRecording.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    AnimationUtils.hapticFeedback(v, AnimationUtils.HapticWeight.MEDIUM)
                    AnimationUtils.animateScalePress(
                        v, targetScale = 0.94f,
                        stiffness = AnimationUtils.SPRING_STIFFNESS_PRIMARY,
                        damping   = AnimationUtils.SPRING_DAMPING_PRIMARY
                    )
                }
                MotionEvent.ACTION_UP -> {
                    AnimationUtils.animateScaleRelease(
                        v,
                        stiffness = AnimationUtils.SPRING_STIFFNESS_PRIMARY,
                        damping   = AnimationUtils.SPRING_DAMPING_PRIMARY
                    )
                    v.performClick()
                    if (isRecording) stopRecording() else startRecording()
                }
                MotionEvent.ACTION_CANCEL -> {
                    AnimationUtils.animateScaleRelease(
                        v,
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
            // ── Button: text → "Stop Recording", color orange → red ───────────────
            crossfadeText(btnToggleRecording, "Stop Recording")
            animateButtonColor(
                from = getColor(R.color.mango_accent),
                to   = getColor(R.color.mango_active)
            )

            // ── Status chip (panel) ───────────────────────────────────────────────
            vStatusDot.setBackgroundResource(R.drawable.dot_recording)
            crossfadeText(tvStatus, "REC")
            startStatusDotBreathing(BREATHING_REC_MS)
            AnimationUtils.announceScale(statusChip, peakScale = 1.06f)

            // ── Status overlay pill (top-left) ────────────────────────────────────
            vOverlayStatusDot.setBackgroundResource(R.drawable.dot_recording)
            tvOverlayStatus.text = "Rec"
            startOverlayDotBreathing()

            // ── Upload countdown pill: fade in ────────────────────────────────────
            tvUploadOverlayStatus.text =
                "upload in  %02d:%02d".format(selectedDurationMin, 0)
            vUploadOverlayDot.visibility = View.GONE
            uploadOverlayPill.animate().alpha(1f)
                .setDuration(AnimationUtils.DURATION_ELEMENT)
                .setInterpolator(DecelerateInterpolator()).start()
            startElapsedTimer()

            setControlsEnabled(false)
        } else {
            // ── Button: text → "Start Recording", color red → orange ─────────────
            stopElapsedTimer()
            crossfadeText(btnToggleRecording, "Start Recording")
            animateButtonColor(
                from = getColor(R.color.mango_active),
                to   = getColor(R.color.mango_accent)
            )

            // ── Status chip (panel) ───────────────────────────────────────────────
            vStatusDot.setBackgroundResource(R.drawable.dot_ready)
            crossfadeText(tvStatus, "IDLE")
            startStatusDotBreathing(BREATHING_IDLE_MS)
            AnimationUtils.announceScale(statusChip, peakScale = 1.04f)

            // ── Status overlay pill (top-left) ────────────────────────────────────
            stopOverlayDotBreathing()
            vOverlayStatusDot.setBackgroundResource(R.drawable.dot_ready)
            tvOverlayStatus.text = "Ready"

            // ── Upload countdown pill: fade out ───────────────────────────────────
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
        // Small lead delay so "REC • 00:00" reads for one beat before counting
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

    // ── Status dot breathing ───────────────────────────────────────────────────

    private fun startStatusDotBreathing(durationMs: Long) {
        stopStatusDotBreathing()
        statusDotAnimators = AnimationUtils.startBreathing(vStatusDot, durationMs)
    }

    private fun stopStatusDotBreathing() {
        statusDotAnimators?.forEach { it.cancel() }
        statusDotAnimators = null
        vStatusDot.alpha  = 1f
        vStatusDot.scaleX = 1f
        vStatusDot.scaleY = 1f
    }

    // ── Controls dim/enable ───────────────────────────────────────────────────

    private fun setControlsEnabled(enabled: Boolean) {
        qualityControl.setEnabled(enabled)
        fpsControl.setEnabled(enabled)
        seekDuration.isEnabled = enabled
        AnimationUtils.animateAlphaTransition(seekDuration, if (enabled) 1.0f else 0.38f)
    }

    // ── Text crossfade ────────────────────────────────────────────────────────

    private fun crossfadeText(tv: TextView, newText: String) {
        if (tv.text == newText) return
        tv.animate()
            .alpha(0f)
            .setDuration(AnimationUtils.DURATION_INSTANT)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                tv.text = newText
                tv.animate()
                    .alpha(1f)
                    .setDuration(AnimationUtils.DURATION_MICRO)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }.start()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Upload status chip
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Observes WorkManager tasks tagged UPLOAD_TAG.
     * State machine:
     *   RUNNING / ENQUEUED → pulsing orange "UPLOADING"
     *   Active → 0 active  → "UPLOADED ✓" with scale announcement, auto-hides after 3s
     *   FAILED             → "UPLOAD FAILED" then auto-hides
     *   Idle on cold open  → chip stays hidden (wasUploadActive guard)
     */
    /**
     * Drives the bottom-left upload overlay pill via WorkManager LiveData.
     *
     * RUNNING/ENQUEUED → "● Uploading..."  (blue dot, pulsing)
     * Active → done    → "● Uploaded"      (green dot, 3 s then countdown resumes)
     * FAILED           → pill fades out    (honest, no drama)
     * Idle cold-open   → no change         (wasUploadActive guard)
     */
    private fun observeUploadState() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(UploadWorker.UPLOAD_TAG)
            .observe(this) { workInfos ->
                val active = workInfos.filter {
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
                            // Failed — just let countdown resume silently
                            showUploadCountdown()
                        } else {
                            showUploadedOverlay()
                            // After 3 s resume countdown if still recording, else hide
                            uploadChipHandler.postDelayed(uploadChipHideRunnable, 3000)
                        }
                    }
                }
            }
    }

    /** Blue pulsing dot + "Uploading..." — suppresses countdown updates. */
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
        // Text will be refreshed on the next timerRunnable tick (≤1 s)
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
     * Corrects the TextureView transform so the camera preview appears upright.
     *
     * The camera sensor has a fixed physical orientation (SENSOR_ORIENTATION, typically
     * 90° for back cameras). The device can be held at ROTATION_0/90/180/270.
     * We need to rotate the TextureView contents so the visual result is always correct.
     *
     * Formula from Android Camera2Basic sample:
     *   totalRotation = 90 * (deviceRotation - 2)  [for ROTATION_90 and ROTATION_270]
     * Combined with a scale step to fill the view after rotation.
     *
     * Also called from onSurfaceTextureSizeChanged so rotation is re-applied after
     * any display size change (e.g. split-screen, foldable).
     */
    @Suppress("DEPRECATION")
    private fun configurePreviewTransform() {
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        if (viewW == 0f || viewH == 0f) return

        val rotation = windowManager.defaultDisplay.rotation
        val matrix   = android.graphics.Matrix()
        val cx = viewW / 2f
        val cy = viewH / 2f

        when (rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                // Buffer rect uses the SWAPPED dimensions because the sensor output
                // is in landscape but Camera2 reports it in portrait coordinates.
                val bufferRect = android.graphics.RectF(
                    0f, 0f, selectedHeight.toFloat(), selectedWidth.toFloat()
                )
                bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
                matrix.setRectToRect(
                    android.graphics.RectF(0f, 0f, viewW, viewH),
                    bufferRect,
                    android.graphics.Matrix.ScaleToFit.FILL
                )
                val scale = maxOf(viewH / selectedHeight, viewW / selectedWidth)
                matrix.postScale(scale, scale, cx, cy)
                // ROTATION_90=1 → -90°   ROTATION_270=3 → +90°
                matrix.postRotate((90f * (rotation - 2)), cx, cy)
            }
            Surface.ROTATION_180 -> {
                matrix.postRotate(180f, cx, cy)
            }
            // ROTATION_0: sensor and display are already aligned — no transform needed
        }

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
            // Restore start time for elapsed timer continuity across backgrounding
            recordingStartMs = prefs.getLong("recording_start_ms", SystemClock.elapsedRealtime())
        }

        setRecordingUI(isRecording)
        if (!isRecording && hasPermissions()) openPreviewCamera()
    }

    override fun onPause() {
        super.onPause()
        stopElapsedTimer()
        if (!isRecording) closePreviewCamera {}
    }
}
