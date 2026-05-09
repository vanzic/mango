package com.vixcy.mango

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.widget.ScrollView

/**
 * ScrollView that fires a light haptic at the exact moment the rubber-band
 * overscroll boundary is hit — the "Rubber Band Signal" pattern.
 *
 * Psychology: the resistance is not just visual — it becomes tactile.
 * Users who reach the end WITHOUT this signal often keep pulling,
 * unsure if the list is still loading.
 *
 * Parameters (per design doc):
 *   Resistance factor: 0.4  (handled by system ScrollView bounce)
 *   Haptic: KEYBOARD_TAP (light impact) at the moment over-scroll begins
 *   Spring-back: system default (~400ms, dampingRatio 0.85)
 */
class MangoScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    // Guard: fire haptic only once per overscroll event, not every frame
    private var overScrollFired = false

    @Suppress("InlinedApi")
    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)

        if (clampedY && !overScrollFired) {
            // Boundary hit — fire tactile signal at exact resistance moment
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            overScrollFired = true
        }

        // Reset guard once user scrolls back into normal range
        if (!clampedY) {
            overScrollFired = false
        }
    }
}
