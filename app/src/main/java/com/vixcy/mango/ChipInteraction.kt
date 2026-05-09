package com.vixcy.mango

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation

/**
 * Handles the three-phase interaction model for chip buttons:
 *
 *   Phase 1 — Recognition (0–80ms): haptic + scale compress to 0.93
 *   Phase 2 — Hold: stable pressed visual state
 *   Phase 3 — Release: snap-lock pulse (spring 0.93 → 1.04 overshoot → 1.0)
 *
 * Key design decisions:
 *   - Press scale is 0.93 (not 0.96 — that was too subtle to register on small chips)
 *   - setChipActive() does NOT call animateScaleRelease(). The touch handler's
 *     ACTION_UP path calls onClicked() which calls setChipActive(). setChipActive
 *     owns the release animation — a low-damping spring that overshoots to ~1.04
 *     before settling at 1.0. This "snap-lock" communicates "selected and locked."
 *   - setChipInactive() does NOT call animateScaleRelease(). Those chips are already
 *     at 1.0 (not pressed), so it's a no-op anyway — saves two spring allocations.
 */
object ChipInteraction {

    // ── Active state (selected chip) ───────────────────────────────────────────
    fun setChipActive(chip: TextView) {
        chip.apply {
            setTextColor(ContextCompat.getColor(context, R.color.mango_text_primary))
            setBackgroundResource(R.drawable.chip_bg_selected)
            isEnabled = true
            alpha = 1.0f
        }

        // Snap-lock pulse: spring from pressed position (0.93) to 1.0 with low damping.
        // Low damping (0.55) causes natural overshoot to ~1.03–1.04 before settling.
        // This reads as "snapped into place" — distinct from a neutral release.
        // No need to setStartValue; the view is already at ~0.93 from the press.
        SpringAnimation(chip, DynamicAnimation.SCALE_X, 1.0f).apply {
            spring.stiffness = AnimationUtils.SPRING_STIFFNESS_SECONDARY
            spring.dampingRatio = 0.55f   // Intentionally bouncy for "lock" feel
        }.start()
        SpringAnimation(chip, DynamicAnimation.SCALE_Y, 1.0f).apply {
            spring.stiffness = AnimationUtils.SPRING_STIFFNESS_SECONDARY
            spring.dampingRatio = 0.55f
        }.start()
    }

    // ── Inactive state (unselected chip) ──────────────────────────────────────
    fun setChipInactive(chip: TextView) {
        chip.apply {
            setTextColor(ContextCompat.getColor(context, R.color.mango_text_secondary))
            setBackgroundResource(R.drawable.chip_bg_unselected)
            isEnabled = true
            alpha = 1.0f
        }
        // No release animation here — inactive chips are already at scale 1.0.
        // Firing a spring on a view with no displacement wastes allocations.
    }

    // ── Disabled state ─────────────────────────────────────────────────────────
    fun setChipDisabled(chip: TextView) {
        chip.apply {
            setTextColor(ContextCompat.getColor(context, R.color.mango_text_secondary))
            setBackgroundResource(R.drawable.chip_bg_unselected)
            isEnabled = false
            alpha = 0.38f  // Material Design disabled opacity
        }
    }

    // ── Phase 1: Recognition touch feedback ───────────────────────────────────
    // Scale: 0.93 (not 0.96 — on a 44dp chip, 0.96 is only 1.76dp movement,
    //        imperceptible at normal viewing distance)
    fun onChipPressed(chip: View) {
        AnimationUtils.hapticFeedback(chip, AnimationUtils.HapticWeight.LIGHT)
        AnimationUtils.animateScalePress(
            chip,
            targetScale = 0.93f,  // Was 0.96f — corrected for chip size
            stiffness = AnimationUtils.SPRING_STIFFNESS_SECONDARY,
            damping = AnimationUtils.SPRING_DAMPING_SECONDARY
        )
    }

    // ── Cancelled touch: spring back to neutral without selecting ─────────────
    // Called on ACTION_CANCEL and ACTION_UP-outside-bounds.
    fun onChipCancelled(chip: View) {
        AnimationUtils.animateScaleRelease(
            chip,
            stiffness = AnimationUtils.SPRING_STIFFNESS_SECONDARY,
            damping = AnimationUtils.SPRING_DAMPING_SECONDARY
        )
    }
}
