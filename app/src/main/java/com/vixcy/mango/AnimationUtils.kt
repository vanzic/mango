package com.vixcy.mango

import android.view.View
import android.view.HapticFeedbackConstants
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation

object AnimationUtils {
    // Spring parameters (tension & damping calibrated for touch-initiated interactions)
    const val SPRING_DAMPING_PRIMARY = 0.82f   // Card expand, primary object motion
    const val SPRING_DAMPING_SECONDARY = 0.78f // Button press, secondary elements
    const val SPRING_DAMPING_MICRO = 0.70f     // Micro-interactions, subtle feedback
    const val SPRING_STIFFNESS_PRIMARY = 120f
    const val SPRING_STIFFNESS_SECONDARY = 160f
    const val SPRING_STIFFNESS_MICRO = 220f

    // Duration tokens (milliseconds)
    const val DURATION_INSTANT = 80L      // Feedback: scale, color tint
    const val DURATION_MICRO = 160L       // State badge, icon morph
    const val DURATION_ELEMENT = 240L     // Element appear/disappear
    const val DURATION_COMPONENT = 360L   // Component transition
    const val DURATION_SCREEN = 480L      // Screen/sheet transition

    // Stagger formula: capped at 8 elements × 45ms offset
    fun getStaggerDelay(index: Int, baseDelayMs: Long = 45L): Long {
        val cappedIndex = minOf(index, 8)
        return cappedIndex * baseDelayMs
    }

    // Spring scale animation: 1.0 → targetScale → 1.0 with spring physics
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

    // Spring release: animate scale from pressed back to 1.0
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

    // Translation spring: from current position to target, with optional velocity inheritance
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

    // Haptic feedback calibrated to interaction weight
    @Suppress("InlinedApi")
    fun hapticFeedback(view: View, weight: HapticWeight) {
        val feedbackConstant = when (weight) {
            HapticWeight.LIGHT -> HapticFeedbackConstants.KEYBOARD_TAP
            HapticWeight.MEDIUM -> HapticFeedbackConstants.KEYBOARD_PRESS
            HapticWeight.HEAVY -> HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(feedbackConstant)
    }

    enum class HapticWeight {
        LIGHT,   // Toggle, segment selection, minor state change
        MEDIUM,  // Button press, chip selection, standard action
        HEAVY    // Destructive action, confirmation
    }
}
