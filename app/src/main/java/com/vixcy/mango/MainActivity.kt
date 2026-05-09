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

    // ── UI ─────────────────────────────────────────────────────────────────────
    private lateinit var textureView: TextureView
    private lateinit var statusChip: LinearLayout
    private lateinit var vStatusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvDurationValue: TextView
    private lateinit var tvFileSizeEstimate: TextView
    private lateinit var seekDuration: SeekBar
    private lateinit var btnToggleRecording: TextView
    // Section labels — pulsed on every selection change ("system heard you")
    private lateinit var tvLabelQuality: TextView
    private lateinit var tvLabelFrameRate: TextView
    private lateinit var tvLabelAspectRatio: TextView
    private lateinit var tvLabelDuration: TextView
    private lateinit var chip720p: TextView
    private lateinit var chip1080p: TextView
    private lateinit var chip4k: TextView
    private lateinit var chip24fps: TextView
    private lateinit var chip30fps: TextView
    private lateinit var chip60fps: TextView
    private lateinit var chipAspect16_9: TextView
    private lateinit var chipAspect4_3: TextView
    private lateinit var chipAspect1_1: TextView

    // ── Upload status chip ─────────────────────────────────────────────────────
    private lateinit var uploadStatusChip: LinearLayout
    private lateinit var vUploadDot: View
    private lateinit var tvUploadStatus: TextView
    // Breathing animators for the upload dot — stored so we can cancel on completion
    private var uploadDotAnimators: List<Animator>? = null
    // Tracks whether we were uploading so we can detect the "just finished" transition
    private var wasUploadActive = false
    // Delays the chip fade-out after "UPLOADED ✓" so the user can read it
    private val uploadChipHandler = Handler(Looper.getMainLooper())
    private val uploadChipHideRunnable = Runnable { hideUploadChip() }

    // ── Elapsed recording timer ────────────────────────────────────────────────
    // Drives the "REC • 00:03:42" live readout in the status chip.
    // Psychology: a ticking timer is the strongest possible proof that the system
    // is alive and working. It answers "is it recording?" without any UI chrome.
    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingStartMs = 0L   // SystemClock.elapsedRealtime() at start
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - recordingStartMs
            val s = (elapsed / 1000).toInt()
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            // Monospace font prevents digit-width jitter as numbers change
            tvStatus.text = if (h > 0) "REC • %02d:%02d:%02d".format(h, m, sec)
                            else        "REC • %02d:%02d".format(m, sec)
            timerHandler.postDelayed(this, 1000)
        }
    }

    // ── Camera ─────────────────────────────────────────────────────────────────
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler

    // ── State ──────────────────────────────────────────────────────────────────
    private var isRecording        = false
    // baseVideoHeight is the quality tier (720 / 1080 / 2160).
    // selectedWidth/selectedHeight are DERIVED from baseVideoHeight + aspectRatio
    // every time either changes. Never set them directly.
    private var baseVideoHeight    = 720
    private var selectedWidth      = 1280   // recomputed by computeAndUpdateDimensions()
    private var selectedHeight     = 720    // recomputed by computeAndUpdateDimensions()
    private var selectedBitrate    = 2_000_000
    private var selectedFps        = 30
    private var selectedDurationMin = 10
    private var lastEstimatedSizeMb = -1    // -1 sentinel → force first render
    private var aspectRatioW       = 16f
    private var aspectRatioH       = 9f
    private var hasAnimatedFirstAppearance = false

    // ── Button background (programmatic GradientDrawable for color animation) ──
    // Rule: background color cannot be animated via compositable GPU properties,
    //       but animating it on a GradientDrawable avoids a full layout pass.
    private val btnRecordDrawable by lazy {
        GradientDrawable().apply {
            cornerRadius = 16f * resources.displayMetrics.density
            setColor(getColor(R.color.mango_accent)) // starts as idle/orange
        }
    }
    private var btnColorAnimator: ValueAnimator? = null

    // ── File size ValueAnimator reference (cancellation fix) ──────────────────
    // Bug fix: without this, rapid slider drags stacked multiple ValueAnimators
    // all updating the same TextView → flickering.
    private var fileSizeAnimator: ValueAnimator? = null

    // ── Status dot breathing ───────────────────────────────────────────────────
    private var statusDotAnimators: List<Animator>? = null

    companion object {
        private const val PERM_REQUEST = 101
        private const val PREFS        = "mango_prefs"
        // Breathing cadences (per design doc):
        //   IDLE → 1400ms ("watching quietly" — very slow, calm)
        //   REC  →  900ms ("actively recording" — slightly elevated)
        private const val BREATHING_IDLE_MS = 1400L
        private const val BREATHING_REC_MS  =  900L
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
        computeAndUpdateDimensions()   // set selectedWidth/Height before any chip setup
        restoreState()
        setupQualityChips()
        setupFpsChips()
        setupAspectRatioChips()
        setupDurationSlider()
        setupToggleButton()
        observeUploadState()

        // BUG FIX #1: Compute initial file size immediately (no animation on first render).
        // Previously: selectQuality/selectFps guards returned early because defaults matched,
        // so updateFileSizeEstimate() was never called. lastEstimatedSizeMb stayed 0.
        // When user first moved the slider, the ValueAnimator counted from 0 → actual,
        // briefly showing "≈ 0 MB / clip". Fixed by calling with animate=false here.
        updateFileSizeEstimate(animate = false)

        // BUG FIX #3: Staggered entry must run AFTER layout pass.
        // Previously called synchronously in onCreate() — views had width/height=0,
        // causing alpha=0 to flash before layout, and translation offsets to be wrong.
        // decorView.post() defers until first layout pass completes.
        if (!hasAnimatedFirstAppearance) {
            hasAnimatedFirstAppearance = true
            window.decorView.post {
                AnimationUtils.animateStaggeredEntry(listOf(
                    findViewById(R.id.statusChip),
                    findViewById(R.id.qualitySelector),
                    findViewById(R.id.fpsSelector),
                    findViewById(R.id.aspectRatioSelector),
                    seekDuration.parent as View,
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
        btnToggleRecording = findViewById(R.id.btnToggleRecording)
        uploadStatusChip   = findViewById(R.id.uploadStatusChip)
        vUploadDot         = findViewById(R.id.vUploadDot)
        tvUploadStatus     = findViewById(R.id.tvUploadStatus)
        tvLabelQuality     = findViewById(R.id.tvLabelQuality)
        tvLabelFrameRate   = findViewById(R.id.tvLabelFrameRate)
        tvLabelAspectRatio = findViewById(R.id.tvLabelAspectRatio)
        tvLabelDuration    = findViewById(R.id.tvLabelDuration)
        chip720p           = findViewById(R.id.chip720p)
        chip1080p          = findViewById(R.id.chip1080p)
        chip4k             = findViewById(R.id.chip4k)
        chip24fps          = findViewById(R.id.chip24fps)
        chip30fps          = findViewById(R.id.chip30fps)
        chip60fps          = findViewById(R.id.chip60fps)
        chipAspect16_9     = findViewById(R.id.chipAspect16_9)
        chipAspect4_3      = findViewById(R.id.chipAspect4_3)
        chipAspect1_1      = findViewById(R.id.chipAspect1_1)

        // Attach programmatic GradientDrawable so color can animate smoothly
        btnToggleRecording.background = btnRecordDrawable
    }

    private fun restoreState() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        isRecording = prefs.getBoolean("recording_active", false)
        // BUG FIX #4: setRecordingUI() was called here AND again in onCreate().
        // Two calls cause a redundant crossfade attempt and a double startBreathing()
        // guard hit. Now called only once from setRecordingUI() at end of onCreate().
        if (isRecording) {
            textureView.visibility = View.INVISIBLE
            textureView.alpha = 0f
        } else {
            textureView.visibility = View.VISIBLE
            textureView.alpha = 1f
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Quality chips
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupQualityChips() {
        // Quality only specifies the BASE HEIGHT (pixel count tier) and bitrate.
        // Actual width is computed by computeAndUpdateDimensions() from baseVideoHeight
        // combined with the currently selected aspect ratio. This ensures a 4:3 + 1080p
        // selection records at 1440×1080, not 1920×1080.
        addChipTouchFeedback(chip720p)  { selectQuality(chip720p,   720, 2_000_000) }
        addChipTouchFeedback(chip1080p) { selectQuality(chip1080p, 1080, 8_000_000) }
        addChipTouchFeedback(chip4k)    { selectQuality(chip4k,    2160, 40_000_000) }
    }

    private fun selectQuality(chip: TextView, baseH: Int, bitrate: Int) {
        if (baseVideoHeight == baseH && selectedBitrate == bitrate) return
        baseVideoHeight = baseH
        selectedBitrate = bitrate
        computeAndUpdateDimensions()
        listOf(chip720p, chip1080p, chip4k).forEach { setChipInactive(it) }
        setChipActive(chip)
        AnimationUtils.pulseLabel(tvLabelQuality)  // "I see you changed quality"
        updateSliderMax()
        updateFileSizeEstimate()
        if (!isRecording) closePreviewCamera { openPreviewCamera() }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FPS chips
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupFpsChips() {
        addChipTouchFeedback(chip24fps) { selectFps(chip24fps, 24) }
        addChipTouchFeedback(chip30fps) { selectFps(chip30fps, 30) }

        // NEW: Check hardware capability before offering 60fps.
        // Rule: never present a control whose effect will be silently ignored.
        // If camera doesn't support it, disable chip at 0.38 opacity.
        if (is60fpsSupported()) {
            addChipTouchFeedback(chip60fps) { selectFps(chip60fps, 60) }
        } else {
            ChipInteraction.setChipDisabled(chip60fps)
        }
    }

    private fun selectFps(chip: TextView, fps: Int) {
        if (selectedFps == fps) return
        selectedFps = fps
        listOf(chip24fps, chip30fps, chip60fps).forEach { setChipInactive(it) }
        setChipActive(chip)
        AnimationUtils.pulseLabel(tvLabelFrameRate)
        updateFileSizeEstimate()
        if (!isRecording) closePreviewCamera { openPreviewCamera() }
    }

    /** Returns true if the back camera's high-speed config includes ≥60fps. */
    private fun is60fpsSupported(): Boolean = try {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return false
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        // High-speed ranges include 60fps if any upper bound ≥ 60
        map?.highSpeedVideoFpsRanges?.any { it.upper >= 60 } ?: false
    } catch (e: Exception) {
        false
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Aspect ratio chips
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupAspectRatioChips() {
        addChipTouchFeedback(chipAspect16_9) { selectAspectRatio(chipAspect16_9, 16f, 9f) }
        addChipTouchFeedback(chipAspect4_3)  { selectAspectRatio(chipAspect4_3,  4f,  3f) }
        addChipTouchFeedback(chipAspect1_1)  { selectAspectRatio(chipAspect1_1,  1f,  1f) }
    }

    private fun selectAspectRatio(chip: TextView, w: Float, h: Float) {
        if (aspectRatioW == w && aspectRatioH == h) return
        aspectRatioW = w
        aspectRatioH = h
        computeAndUpdateDimensions()    // update selectedWidth/Height for recording
        listOf(chipAspect16_9, chipAspect4_3, chipAspect1_1).forEach { setChipInactive(it) }
        setChipActive(chip)
        AnimationUtils.pulseLabel(tvLabelAspectRatio)
        applyAspectRatioToPreview()
    }

    /**
     * Derives selectedWidth/selectedHeight from the current quality tier and aspect ratio.
     *
     * Strategy: keep baseVideoHeight as the "quality anchor" and compute width from the
     * aspect ratio. Always round width DOWN to the nearest even number — MediaRecorder and
     * H.264 both require even dimensions.
     *
     * Examples (baseVideoHeight = 1080):
     *   16:9  → 1920 × 1080   (standard full HD)
     *    4:3  → 1440 × 1080   (4:3 at 1080p)
     *    1:1  → 1080 × 1080   (square)
     *
     * Examples (baseVideoHeight = 720):
     *   16:9  → 1280 × 720
     *    4:3  →  960 × 720
     *    1:1  →  720 × 720
     */
    private fun computeAndUpdateDimensions() {
        selectedHeight = baseVideoHeight
        // Round to nearest even number (H.264 codec requirement)
        val rawWidth = (baseVideoHeight * aspectRatioW / aspectRatioH).toInt()
        selectedWidth = if (rawWidth % 2 == 0) rawWidth else rawWidth - 1
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Duration slider
    // ══════════════════════════════════════════════════════════════════════════

    @Suppress("InlinedApi")
    private fun setupDurationSlider() {
        seekDuration.progress = 10
        tvDurationValue.text  = "10 min"
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
                // No per-tick haptic: haptic budget ≤4 per flow. Start/stop cover it.
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                AnimationUtils.hapticFeedback(sb, AnimationUtils.HapticWeight.LIGHT)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                AnimationUtils.hapticFeedback(sb, AnimationUtils.HapticWeight.LIGHT)
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

    /**
     * @param animate false on first call (avoids counting from 0 → actual on launch).
     * BUG FIX #2: Cancels previous ValueAnimator before starting new one.
     * Previously, rapid slider drags stacked N animators on the same TextView.
     */
    private fun updateFileSizeEstimate(animate: Boolean = true) {
        val fpsFactor   = selectedFps / 30.0
        val durationSec = selectedDurationMin * 60
        val sizeMb      = ((selectedBitrate * fpsFactor * durationSec) / 8 / 1_000_000).toInt()
        if (sizeMb == lastEstimatedSizeMb) return

        if (animate && lastEstimatedSizeMb > 0) {
            fileSizeAnimator?.cancel()
            fileSizeAnimator = AnimationUtils.animateValueChange(
                tvFileSizeEstimate, lastEstimatedSizeMb, sizeMb,
                prefix = "≈ ", suffix = " MB / clip"
            )
        } else {
            // First render or non-user-triggered: set directly, no animation
            tvFileSizeEstimate.text = "≈ $sizeMb MB / clip"
        }
        lastEstimatedSizeMb = sizeMb
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Recording toggle button
    // ══════════════════════════════════════════════════════════════════════════

    @Suppress("InlinedApi")
    private fun setupToggleButton() {
        // setRecordingUI() is called once from onResume to sync button visual to state.
        // Applying initial UI here avoids the double-call bug.
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
                    // Action and spring-back are concurrent — design rule:
                    // action must not wait for animation to complete.
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

    private fun startRecording() {
        closePreviewCamera {
            // Persist the start timestamp so onResume() can reconstruct the elapsed
            // timer correctly after the user backgrounds and returns to the app.
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

            // Fade out preview (easeOut — system-initiated, not touch-driven)
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

        // Fade in preview
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
    // UI state management
    // ══════════════════════════════════════════════════════════════════════════

    private fun setRecordingUI(recording: Boolean) {
        isRecording = recording
        if (recording) {
            crossfadeText(btnToggleRecording, "Stop Recording")
            animateButtonColor(
                from = getColor(R.color.mango_accent),  // orange
                to   = getColor(R.color.mango_active)   // red
            )
            vStatusDot.setBackgroundResource(R.drawable.dot_recording)
            // Don't crossfade status to "REC" — the timer owns that slot.
            // Set it directly so timer can begin updating it immediately.
            tvStatus.text = "REC"
            startStatusDotBreathing(BREATHING_REC_MS)  // 900ms — elevated cadence

            // Status chip announces the state change with a spatial jolt.
            // The spring-bounce reads as "something just happened here" —
            // confirms the user's action without a modal or toast.
            AnimationUtils.announceScale(statusChip, peakScale = 1.06f)

            // Start elapsed timer — the single most powerful "validating" signal.
            // A ticking clock proves the system is alive, understood the command,
            // and is actively working. Users stop second-guessing when they see time moving.
            startElapsedTimer()
            setControlsEnabled(false)
        } else {
            // Stop timer before crossfade so "REC • 00:03:42" doesn't linger
            stopElapsedTimer()

            crossfadeText(btnToggleRecording, "Start Recording")
            animateButtonColor(
                from = getColor(R.color.mango_active),  // red
                to   = getColor(R.color.mango_accent)   // orange
            )
            vStatusDot.setBackgroundResource(R.drawable.dot_ready)
            crossfadeText(tvStatus, "IDLE")
            startStatusDotBreathing(BREATHING_IDLE_MS) // 1400ms — calm, watching

            // Announce the stop too — confirms the user that recording ended
            AnimationUtils.announceScale(statusChip, peakScale = 1.04f)
            setControlsEnabled(true)
        }
    }

    // ── Elapsed recording timer ────────────────────────────────────────────────

    private fun startElapsedTimer() {
        timerHandler.removeCallbacks(timerRunnable)
        // Post at 500ms — allows the initial "REC" text to read for a beat
        // before the timer starts ticking, so the transition has a rhythm
        timerHandler.postDelayed(timerRunnable, 500)
    }

    private fun stopElapsedTimer() {
        timerHandler.removeCallbacks(timerRunnable)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Upload status chip
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Observes all WorkManager tasks tagged UPLOAD_TAG.
     *
     * State machine:
     *   RUNNING / ENQUEUED  →  show pulsing orange "UPLOADING" chip
     *   Active → 0 active   →  show "UPLOADED ✓" with scale announcement,
     *                           then auto-hide after 3 s
     *   FAILED (any)        →  show "UPLOAD FAILED" briefly then hide
     *   Idle on cold open   →  chip stays hidden (wasUploadActive guard)
     *
     * The wasUploadActive flag prevents a spurious "UPLOADED ✓" flash when the
     * app reopens and sees old completed work in WorkManager's history.
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
                        // Cancel any scheduled hide — still uploading
                        uploadChipHandler.removeCallbacks(uploadChipHideRunnable)
                        wasUploadActive = true
                        val count = active.size
                        showUploadingChip(count)
                    }
                    wasUploadActive -> {
                        // Transitioned from uploading → done
                        wasUploadActive = false
                        uploadChipHandler.removeCallbacks(uploadChipHideRunnable)
                        if (anyFailed) {
                            showUploadFailedChip()
                        } else {
                            showUploadedChip()
                        }
                        // Auto-hide after 3 s — long enough to read, short enough not to clutter
                        uploadChipHandler.postDelayed(uploadChipHideRunnable, 3000)
                    }
                    // else: idle on cold open — leave chip hidden, no wasUploadActive set
                }
            }
    }

    /** Pulsing orange "↑ UPLOADING" or "↑ UPLOADING (N)" when N > 1. */
    private fun showUploadingChip(count: Int) {
        stopUploadDotBreathing()
        vUploadDot.setBackgroundResource(R.drawable.dot_uploading)
        tvUploadStatus.text = if (count > 1) "UPLOADING ($count)" else "UPLOADING"
        tvUploadStatus.setTextColor(getColor(R.color.mango_text_primary))

        // Reveal chip if currently hidden
        if (uploadStatusChip.alpha < 0.5f) {
            uploadStatusChip.animate()
                .alpha(1f)
                .setDuration(AnimationUtils.DURATION_ELEMENT)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        // Dot breathes at 700ms — faster than REC (900ms), signals active network work
        uploadDotAnimators = AnimationUtils.startBreathing(vUploadDot, durationMs = 700L)
    }

    /** Green "UPLOADED ✓" with spatial announcement — confirms the work is done. */
    private fun showUploadedChip() {
        stopUploadDotBreathing()
        vUploadDot.setBackgroundResource(R.drawable.dot_ready)          // green
        tvUploadStatus.text = "UPLOADED ✓"
        tvUploadStatus.setTextColor(getColor(R.color.mango_dot_ready))  // green text

        // Chip announces itself: snaps slightly larger then springs to 1.0
        // Same pattern as the status chip state-change ceremony.
        AnimationUtils.announceScale(uploadStatusChip, peakScale = 1.06f)

        // Ensure visible
        uploadStatusChip.animate()
            .alpha(1f)
            .setDuration(AnimationUtils.DURATION_MICRO)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Red "UPLOAD FAILED" — honest, quiet, no haptic (not user's fault). */
    private fun showUploadFailedChip() {
        stopUploadDotBreathing()
        vUploadDot.setBackgroundResource(R.drawable.dot_recording)     // red
        tvUploadStatus.text = "UPLOAD FAILED"
        tvUploadStatus.setTextColor(getColor(R.color.mango_active))    // red text

        uploadStatusChip.animate()
            .alpha(1f)
            .setDuration(AnimationUtils.DURATION_MICRO)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Fades the chip out smoothly — called 3s after upload completes. */
    private fun hideUploadChip() {
        stopUploadDotBreathing()
        uploadStatusChip.animate()
            .alpha(0f)
            .setDuration(AnimationUtils.DURATION_COMPONENT)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun stopUploadDotBreathing() {
        uploadDotAnimators?.forEach { it.cancel() }
        uploadDotAnimators = null
        vUploadDot.alpha  = 1f
        vUploadDot.scaleX = 1f
        vUploadDot.scaleY = 1f
    }

    /**
     * Animates button background color between states.
     * NEW: replaces the previous setBackgroundResource() snap.
     * Uses ArgbEvaluator on the GradientDrawable fill — keeps corner radius
     * and avoids triggering a layout pass.
     * NEW: replaces the previous setBackgroundResource() snap.
     * Uses ArgbEvaluator on the GradientDrawable fill — keeps corner radius
     * and avoids triggering a layout pass.
     */
    private fun animateButtonColor(from: Int, to: Int) {
        if (from == to) return
        btnColorAnimator?.cancel()
        btnColorAnimator = AnimationUtils.animateButtonColor(btnRecordDrawable, from, to)
    }

    /** Crossfade: old text fades out (80ms), new text fades in (160ms). */
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

    private fun setControlsEnabled(enabled: Boolean) {
        val targetAlpha = if (enabled) 1.0f else 0.38f
        listOf(chip720p, chip1080p, chip4k,
               chip24fps, chip30fps, chip60fps,
               chipAspect16_9, chipAspect4_3, chipAspect1_1,
               seekDuration).forEach {
            it.isEnabled = enabled
            AnimationUtils.animateAlphaTransition(it, targetAlpha)
        }
    }

    private fun setChipActive(chip: TextView) {
        ChipInteraction.setChipActive(chip)
    }

    private fun setChipInactive(chip: TextView) {
        ChipInteraction.setChipInactive(chip)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Chip touch feedback
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Three-phase interaction:
     *   ACTION_DOWN   → Phase 1 Recognition: haptic + scale press (0.93)
     *   ACTION_UP     → Phase 3 Release: if within bounds → snap-lock (setChipActive);
     *                                    if outside bounds → neutral spring-back
     *   ACTION_CANCEL → Neutral spring-back (user changed their mind mid-press)
     *
     * BUG FIX #5: Previously ACTION_UP called onClicked() unconditionally.
     * A press-drag-release outside the chip would still trigger selection.
     * Now bounds-checked: if finger lifted outside the view, treat as cancel.
     *
     * BUG FIX: Previously both onChipReleased() AND setChipActive() each
     * started a spring to 1.0 → two competing animations. Resolved by having
     * setChipActive() own the release (snap-lock spring), and onChipCancelled()
     * own the neutral release. The ACTION_UP path no longer calls onChipReleased().
     */
    @Suppress("InlinedApi")
    private fun addChipTouchFeedback(chip: TextView, onClicked: () -> Unit) {
        chip.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    ChipInteraction.onChipPressed(v)
                }
                MotionEvent.ACTION_UP -> {
                    val withinBounds = event.x >= 0 && event.x <= v.width &&
                                       event.y >= 0 && event.y <= v.height
                    if (withinBounds) {
                        v.performClick()
                        onClicked() // setChipActive() inside handles the snap-lock spring
                    } else {
                        ChipInteraction.onChipCancelled(v) // neutral spring-back
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    ChipInteraction.onChipCancelled(v)
                }
            }
            true
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Status dot breathing animation
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * NEW: Both IDLE and REC states breathe — with distinct cadences.
     *   IDLE → 1400ms: "watching quietly," very slow, communicates calm readiness
     *   REC  →  900ms: "actively recording," slightly elevated, distinct from idle
     *
     * Previously only REC state breathed. IDLE was static — missed the ambient
     * motion principle ("Secondary Action reinforces primary state without competing").
     *
     * The breathing restarts when switching states so the new cadence takes effect
     * immediately rather than waiting for the old cycle to complete.
     */
    private fun startStatusDotBreathing(durationMs: Long) {
        // Always restart — state changed, new cadence must apply immediately
        stopStatusDotBreathing()
        statusDotAnimators = AnimationUtils.startBreathing(vStatusDot, durationMs)
    }

    private fun stopStatusDotBreathing() {
        statusDotAnimators?.forEach { it.cancel() }
        statusDotAnimators = null
        vStatusDot.alpha  = 1.0f
        vStatusDot.scaleX = 1.0f
        vStatusDot.scaleY = 1.0f
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
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(selectedFps, selectedFps))
                        }.build()
                    session.setRepeatingRequest(request, null, cameraHandler)
                    runOnUiThread { applyAspectRatioToPreview() }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, cameraHandler)
    }

    private fun closePreviewCamera(onClosed: () -> Unit) {
        captureSession?.close(); captureSession = null
        cameraDevice?.close();   cameraDevice   = null
        if (::cameraThread.isInitialized) cameraThread.quitSafely()
        onClosed()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Preview transform
    // ══════════════════════════════════════════════════════════════════════════

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

        val targetAspect = aspectRatioW / aspectRatioH
        val matrix = android.graphics.Matrix()
        val cx = viewW / 2f
        val cy = viewH / 2f

        val rotate = when (sensorOrientation) {
            90  -> -90f
            270 ->  90f
            180 -> 180f
            else ->  0f
        }

        if (sensorOrientation == 90 || sensorOrientation == 270) {
            val scaleX = 1f / targetAspect
            val scaleY = viewW / viewH
            matrix.postScale(scaleX, scaleY, cx, cy)
        }

        matrix.postRotate(rotate, cx, cy)
        textureView.setTransform(matrix)
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
    // Activity lifecycle
    // ══════════════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        isRecording = prefs.getBoolean("recording_active", false)

        // Restore the recording start time so the elapsed timer shows the correct
        // duration after the user backgrounds and returns (e.g. during a long recording).
        // Falls back to now if not found, which means timer restarts from 0 — acceptable.
        if (isRecording) {
            recordingStartMs = prefs.getLong("recording_start_ms", SystemClock.elapsedRealtime())
        }

        // setRecordingUI() syncs button color, text, dot, breathing, controls, and timer.
        setRecordingUI(isRecording)
        if (!isRecording && hasPermissions()) openPreviewCamera()
    }

    override fun onPause() {
        super.onPause()
        stopElapsedTimer()   // no point ticking when invisible; onResume restores it
        if (!isRecording) closePreviewCamera {}
    }
}
