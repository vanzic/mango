package com.vixcy.mango

import android.animation.Animator
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation

object AnimationUtils {

    // ── Spring vocabulary ──────────────────────────────────────────────────────
    // Rule: spring physics for ALL touch-initiated transitions.
    //       Formula: stiffness = (2π / T)²
    //
    // PRIMARY   (~0.5s response)  — Card expand, primary object motion
    // SECONDARY (~0.3s response)  — Chip/button press, component transition
    // MICRO     (~0.18s response) — Haptic-sync, instant mechanical feel

    const val SPRING_DAMPING_PRIMARY   = 0.82f  // Weighted, confident
    const val SPRING_DAMPING_SECONDARY = 0.70f  // Bouncy, tactile
    const val SPRING_DAMPING_MICRO     = 0.65f  // Snappy, mechanical

    const val SPRING_STIFFNESS_PRIMARY   = 150f   // ~0.5s
    const val SPRING_STIFFNESS_SECONDARY = 440f   // ~0.3s
    const val SPRING_STIFFNESS_MICRO     = 1200f  // ~0.18s

    // ── Duration tokens ────────────────────────────────────────────────────────
    // Rule: duration communicates weight. Faster = more reactive. Slower = more significant.
    const val DURATION_INSTANT   = 80L   // Feedback: scale, color tint
    const val DURATION_MICRO     = 160L  // State badge, icon morph, value update
    const val DURATION_ELEMENT   = 240L  // Element appear/disappear
    const val DURATION_COMPONENT = 360L  // Component transition
    const val DURATION_SCREEN    = 480L  // Screen/sheet transition
    const val DURATION_EMPHASIS  = 720L  // Onboarding, achievement

    // ── Stagger formula ────────────────────────────────────────────────────────
    // Capped at 8 elements × 45ms. Beyond 8 elements stagger = bug, not feature.
    fun getStaggerDelay(index: Int, baseDelayMs: Long = 45L): Long =
        minOf(index, 8) * baseDelayMs

    // ── Press / Release springs ────────────────────────────────────────────────

    fun animateScalePress(
        view: View,
        targetScale: Float = 0.96f,
        stiffness: Float = SPRING_STIFFNESS_MICRO,
        damping: Float = SPRING_DAMPING_MICRO,
        onComplete: (() -> Unit)? = null
    ) {
        SpringAnimation(view, DynamicAnimation.SCALE_X, targetScale).apply {
            spring.stiffness = stiffness
            spring.dampingRatio = damping
            addEndListener { _, _, _, _ -> onComplete?.invoke() }
        }.start()
        SpringAnimation(view, DynamicAnimation.SCALE_Y, targetScale).apply {
            spring.stiffness = stiffness
            spring.dampingRatio = damping
        }.start()
    }

    fun animateScaleRelease(
        view: View,
        stiffness: Float = SPRING_STIFFNESS_MICRO,
        damping: Float = SPRING_DAMPING_MICRO
    ) {
        SpringAnimation(view, DynamicAnimation.SCALE_X, 1.0f).apply {
            spring.stiffness = stiffness
            spring.dampingRatio = damping
        }.start()
        SpringAnimation(view, DynamicAnimation.SCALE_Y, 1.0f).apply {
            spring.stiffness = stiffness
            spring.dampingRatio = damping
        }.start()
    }

    // ── Translation spring with velocity inheritance ──────────────────────────
    // Rule: animation that starts from a gesture inherits that gesture's velocity.
    fun animateTranslation(
        view: View,
        targetX: Float = 0f,
        targetY: Float = 0f,
        startVelocityX: Float = 0f,
        startVelocityY: Float = 0f,
        stiffness: Float = SPRING_STIFFNESS_PRIMARY,
        damping: Float = SPRING_DAMPING_PRIMARY
    ) {
        SpringAnimation(view, DynamicAnimation.TRANSLATION_X, targetX).apply {
            spring.stiffness = stiffness
            spring.dampingRatio = damping
            setStartVelocity(startVelocityX)
        }.start()
        SpringAnimation(view, DynamicAnimation.TRANSLATION_Y, targetY).apply {
            spring.stiffness = stiffness
            spring.dampingRatio = damping
            setStartVelocity(startVelocityY)
        }.start()
    }

    // ── Breathing animation (status indicator) ─────────────────────────────────
    // The 1200ms cycle ≈ human resting respiration. The brain reads it as
    // "calm, living, monitoring." A faster cycle (700ms) reads as elevated/alerting.
    //
    // Both IDLE (green) and REC (red) states breathe — with different cadences:
    //   IDLE: 1400ms — very slow, "watching quietly"
    //   REC:   900ms — slightly elevated, "actively recording"
    fun startBreathing(view: View, durationMs: Long = 1200L): List<Animator> {
        val interp = AccelerateDecelerateInterpolator() // sinusoidal — matches breathing rhythm
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 0.85f, 1.0f).apply {
            duration = durationMs
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = interp
        }
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 0.85f, 1.0f).apply {
            duration = durationMs
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = interp
        }
        val alpha = ObjectAnimator.ofFloat(view, "alpha", 0.45f, 1.0f).apply {
            duration = durationMs
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = interp
        }
        scaleX.start()
        scaleY.start()
        alpha.start()
        return listOf(scaleX, scaleY, alpha)
    }

    // ── Value counting animation ───────────────────────────────────────────────
    // Returns the ValueAnimator so callers can CANCEL it before starting a new one.
    // Bug fix: previously callers couldn't cancel, causing multiple animators to
    // stack on rapid slider drags and produce flickering.
    //
    // Rule: numbers that update with meaningful data should count, not snap.
    //       Use animate=false for initial/non-user-triggered updates.
    fun animateValueChange(
        view: TextView,
        startValue: Int,
        endValue: Int,
        prefix: String = "",
        suffix: String = ""
    ): ValueAnimator = ValueAnimator.ofInt(startValue, endValue).apply {
        duration = 480L // 400–600ms per design rule
        interpolator = DecelerateInterpolator()
        addUpdateListener { view.text = "$prefix${it.animatedValue}$suffix" }
        start()
    }

    // ── Button background color animation ─────────────────────────────────────
    // Animates between two fill colors on a GradientDrawable (keeps corner radius).
    // Rule: only compositable properties. Background color requires ArgbEvaluator
    // on the drawable fill, NOT setBackgroundColor() which triggers a layout pass.
    fun animateButtonColor(
        drawable: GradientDrawable,
        fromColor: Int,
        toColor: Int,
        durationMs: Long = DURATION_ELEMENT
    ): ValueAnimator = ValueAnimator().apply {
        setIntValues(fromColor, toColor)
        setEvaluator(ArgbEvaluator())
        duration = durationMs
        interpolator = DecelerateInterpolator()
        addUpdateListener { drawable.setColor(it.animatedValue as Int) }
        start()
    }

    // ── Staggered entry (first-appearance only) ────────────────────────────────
    // Must be called AFTER layout pass — wrap in decorView.post{} at call site.
    // Rule: only animate-in on first appearance. Never re-animate on return.
    fun animateStaggeredEntry(views: List<View>, baseDelayMs: Long = 45L) {
        views.forEachIndexed { index, view ->
            val delay = getStaggerDelay(index, baseDelayMs)
            view.alpha = 0f
            view.translationY = 12f
            view.postDelayed({
                SpringAnimation(view, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                    spring.stiffness = SPRING_STIFFNESS_SECONDARY
                    spring.dampingRatio = SPRING_DAMPING_SECONDARY
                }.start()
                view.animate()
                    .alpha(1f)
                    .setDuration(DURATION_ELEMENT)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }, delay)
        }
    }

    // ── Alpha transition (system-initiated, easeOut) ───────────────────────────
    // Rule: system-initiated transitions use easeOut, not spring.
    fun animateAlphaTransition(view: View, targetAlpha: Float) {
        view.animate()
            .alpha(targetAlpha)
            .setDuration(DURATION_ELEMENT)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // ── Section label acknowledgement pulse ────────────────────────────────────
    // "The system understood you." — when a control section changes, its label
    // briefly brightens from dim (0.6) to full (1.0) then fades back.
    //
    // Psychology: the label becoming visible for a moment reads as "this area just
    // responded." The user doesn't think about it consciously — they just feel heard.
    //
    // Timing: fast rise (INSTANT = 80ms) so it feels synchronous with the tap,
    // slow fade (COMPONENT = 360ms) so it lingers just long enough to register.
    fun pulseLabel(view: View, baseDimAlpha: Float = 0.6f) {
        view.animate().cancel()
        view.animate()
            .alpha(1.0f)
            .setDuration(DURATION_INSTANT)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .alpha(baseDimAlpha)
                    .setDuration(DURATION_COMPONENT)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }.start()
    }

    // ── Spring scale announcement ──────────────────────────────────────────────
    // One-shot spring jolt to announce a state change on an element that's already
    // at rest. Snaps to peakScale (overshoot), then springs back to 1.0.
    // Used on the status chip when recording starts/stops — communicates "something
    // just changed here" without a modal or toast.
    fun announceScale(view: View, peakScale: Float = 1.08f) {
        view.scaleX = peakScale
        view.scaleY = peakScale
        SpringAnimation(view, DynamicAnimation.SCALE_X, 1.0f).apply {
            spring.stiffness = SPRING_STIFFNESS_SECONDARY
            spring.dampingRatio = 0.50f   // bouncy — the "announcement" needs to be felt
        }.start()
        SpringAnimation(view, DynamicAnimation.SCALE_Y, 1.0f).apply {
            spring.stiffness = SPRING_STIFFNESS_SECONDARY
            spring.dampingRatio = 0.50f
        }.start()
    }

    // ── Haptic feedback ────────────────────────────────────────────────────────
    // Rule: haptic fires at visual peak, not before/after.
    //       Budget: ≤4 haptic events per user flow.
    @Suppress("InlinedApi")
    fun hapticFeedback(view: View, weight: HapticWeight) {
        val constant = when (weight) {
            HapticWeight.LIGHT  -> HapticFeedbackConstants.KEYBOARD_TAP
            HapticWeight.MEDIUM -> HapticFeedbackConstants.KEYBOARD_PRESS
            HapticWeight.HEAVY  -> HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }

    enum class HapticWeight {
        LIGHT,   // Toggle, segment selection, minor state change
        MEDIUM,  // Button press, chip selection, standard action
        HEAVY    // Destructive action, confirmation
    }
}
