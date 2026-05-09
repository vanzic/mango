package com.vixcy.mango

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat

object ChipInteraction {
    enum class ChipState {
        ACTIVE,    // Selected, full color
        INACTIVE,  // Unselected, surface color
        DISABLED,  // Disabled, 38% opacity
        LOADING    // Loading state, 72% opacity with pulse
    }

    fun setChipActive(chip: TextView, accentColor: Int) {
        chip.apply {
            setTextColor(ContextCompat.getColor(context, R.color.mango_text_primary))
            setBackgroundColor(accentColor)
            isEnabled = true
            alpha = 1.0f
        }
        AnimationUtils.hapticFeedback(chip, AnimationUtils.HapticWeight.LIGHT)
        AnimationUtils.animateScaleRelease(chip)
    }

    fun setChipInactive(chip: TextView, surfaceColor: Int) {
        chip.apply {
            setTextColor(ContextCompat.getColor(context, R.color.mango_text_secondary))
            setBackgroundColor(surfaceColor)
            isEnabled = true
            alpha = 1.0f
        }
        AnimationUtils.animateScaleRelease(chip)
    }

    fun setChipDisabled(chip: TextView, surfaceColor: Int) {
        chip.apply {
            setTextColor(ContextCompat.getColor(context, R.color.mango_text_secondary))
            setBackgroundColor(surfaceColor)
            isEnabled = false
            alpha = 0.38f
        }
    }

    fun onChipPressed(chip: View) {
        AnimationUtils.hapticFeedback(chip, AnimationUtils.HapticWeight.MEDIUM)
        AnimationUtils.animateScalePress(chip, targetScale = 0.93f)
    }

    fun onChipReleased(chip: View) {
        AnimationUtils.animateScaleRelease(chip)
    }
}
