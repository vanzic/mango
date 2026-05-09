package com.vixcy.mango

import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation

/**
 * Manages a segmented control composed of a FrameLayout track, a sliding pill indicator View,
 * and a list of TextViews as selectable segments.
 *
 * Architecture (mirrors the XML):
 *   track (FrameLayout) — background: segmented_track.xml
 *     pill (View)          — background: seg_pill.xml, animated via TRANSLATION_X spring
 *     container (LL)       — horizontal LinearLayout holding segment TextViews
 *       seg0 (TextView)
 *       seg1 (TextView)
 *       ...
 *
 * Pill is sized and positioned after the first layout pass via doOnLayout.
 * Springs follow the app-wide vocabulary: SECONDARY stiffness=440f, damping=0.70f.
 *
 * Text states:
 *   Selected   → white (#FFFFFF), bold
 *   Unselected → #8E8E93, normal weight
 *   Disabled   → #8E8E93, normal weight, 0.38 alpha (set at call-time, not managed here)
 */
class SegmentedControl(
    private val track: FrameLayout,
    private val pill: View,
    private val segments: List<TextView>
) {

    private var selectedIndex = 0
    private var onSelectCallback: ((Int) -> Unit)? = null
    private val disabledIndices = mutableSetOf<Int>()
    private var isControlEnabled = true
    private var segWidth = 0f

    // ── Setup ─────────────────────────────────────────────────────────────────

    fun setup(defaultIndex: Int, onSelect: (Int) -> Unit) {
        onSelectCallback = onSelect
        selectedIndex = defaultIndex

        segments.forEachIndexed { index, tv -> addTouchFeedback(tv, index) }

        // Pill must be measured and placed after layout is known.
        // doOnLayout fires exactly once after the first measure pass.
        track.doOnLayout {
            measureAndPlacePill()
            updateColors()
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Programmatically selects a segment. Respects disabled-index and global enable state.
     * @param animate if true, pill slides via spring; if false, teleports (for state restore).
     */
    fun selectIndex(index: Int, animate: Boolean = true) {
        if (index < 0 || index >= segments.size) return
        if (disabledIndices.contains(index)) return
        selectedIndex = index
        if (animate) springPillTo(index) else positionPillImmediate(index)
        updateColors()
        onSelectCallback?.invoke(index)
    }

    /**
     * Dims all enabled segments to 0.38 and blocks interaction (recording mode).
     * Disabled segments (e.g. 60fps) stay at their own 0.38 alpha, unaffected.
     */
    fun setEnabled(enabled: Boolean) {
        isControlEnabled = enabled
        val alpha = if (enabled) 1.0f else 0.38f
        segments.forEachIndexed { index, tv ->
            if (!disabledIndices.contains(index)) {
                tv.isEnabled = enabled
                AnimationUtils.animateAlphaTransition(tv, alpha)
            }
        }
        AnimationUtils.animateAlphaTransition(pill, alpha)
    }

    /** Permanently dims one segment and prevents selection (e.g. 60fps unsupported). */
    fun disableSegment(index: Int) {
        if (index < 0 || index >= segments.size) return
        disabledIndices.add(index)
        segments[index].apply {
            isEnabled = false
            alpha = 0.38f
        }
    }

    // ── Pill geometry ─────────────────────────────────────────────────────────

    private fun measureAndPlacePill() {
        if (segments.isEmpty() || track.width == 0 || track.height == 0) return
        val density = track.resources.displayMetrics.density
        val inset = (3 * density).toInt()

        segWidth = track.width.toFloat() / segments.size

        val params = pill.layoutParams as FrameLayout.LayoutParams
        params.width = segWidth.toInt()
        params.height = track.height - inset * 2
        pill.layoutParams = params
        pill.translationY = inset.toFloat()
        pill.translationX = selectedIndex * segWidth
        pill.requestLayout()
    }

    private fun positionPillImmediate(index: Int) {
        if (segWidth == 0f) return
        pill.translationX = index * segWidth
    }

    private fun springPillTo(index: Int) {
        if (segWidth == 0f) { positionPillImmediate(index); return }
        SpringAnimation(pill, DynamicAnimation.TRANSLATION_X, index * segWidth).apply {
            spring.stiffness = AnimationUtils.SPRING_STIFFNESS_SECONDARY
            spring.dampingRatio = AnimationUtils.SPRING_DAMPING_SECONDARY
        }.start()
    }

    // ── Color management ──────────────────────────────────────────────────────

    private fun updateColors() {
        segments.forEachIndexed { index, tv ->
            if (disabledIndices.contains(index)) return@forEachIndexed
            if (index == selectedIndex) {
                tv.setTextColor(0xFFFFFFFF.toInt())
                tv.setTypeface(null, Typeface.BOLD)
            } else {
                tv.setTextColor(0xFF8E8E93.toInt())
                tv.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    // ── Touch feedback ────────────────────────────────────────────────────────

    private fun addTouchFeedback(tv: TextView, index: Int) {
        tv.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isControlEnabled && !disabledIndices.contains(index)) {
                        AnimationUtils.hapticFeedback(v, AnimationUtils.HapticWeight.LIGHT)
                        AnimationUtils.animateScalePress(
                            v, targetScale = 0.92f,
                            stiffness = AnimationUtils.SPRING_STIFFNESS_SECONDARY,
                            damping = AnimationUtils.SPRING_DAMPING_SECONDARY
                        )
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val inBounds = event.x >= 0 && event.x <= v.width &&
                                   event.y >= 0 && event.y <= v.height
                    AnimationUtils.animateScaleRelease(
                        v,
                        stiffness = AnimationUtils.SPRING_STIFFNESS_SECONDARY,
                        damping   = AnimationUtils.SPRING_DAMPING_SECONDARY
                    )
                    if (inBounds && isControlEnabled && !disabledIndices.contains(index)) {
                        v.performClick()
                        selectIndex(index)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    AnimationUtils.animateScaleRelease(
                        v,
                        stiffness = AnimationUtils.SPRING_STIFFNESS_SECONDARY,
                        damping   = AnimationUtils.SPRING_DAMPING_SECONDARY
                    )
                }
            }
            true
        }
    }
}
