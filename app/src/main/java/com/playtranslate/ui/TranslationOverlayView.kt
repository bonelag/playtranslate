package com.playtranslate.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat

/**
 * Transparent overlay that positions auto-sizing TextViews inside bounding
 * boxes on the game screen during live mode. Each box corresponds to an OCR
 * text group and is filled with a semi-transparent background so the
 * translated text is readable over game graphics. Font size auto-scales
 * via Android's built-in [TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration].
 */
class TranslationOverlayView(context: Context) : FrameLayout(context) {

    data class TextBox(
        val translatedText: String,
        /** Bounding box in original bitmap pixel coordinates. */
        val bounds: Rect,
        /** Average color of the game content behind this box (ARGB). */
        val bgColor: Int = Color.argb(200, 0, 0, 0),
        /** Text color — estimated from game text or chosen for contrast. */
        val textColor: Int = Color.WHITE
    )

    private val dp = context.resources.displayMetrics.density

    private val minTextSizeSp = 6
    private val maxTextSizeSp = 200
    private val boxPadding = 6f * dp
    /** Small inset so text doesn't touch the edges of the background. */
    private val textMargin = (3f * dp).toInt()

    private var boxes: List<TextBox> = emptyList()
    private var cropOffsetX = 0
    private var cropOffsetY = 0
    private var displayScaleX = 1f
    private var displayScaleY = 1f
    private var screenshotW = 1
    private var screenshotH = 1

    fun setBoxes(
        boxes: List<TextBox>,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int
    ) {
        this.boxes = boxes
        cropOffsetX = cropLeft
        cropOffsetY = cropTop
        this.screenshotW = screenshotW
        this.screenshotH = screenshotH
        if (width > 0 && height > 0) {
            updateScales()
            rebuildChildren()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScales()
        post { rebuildChildren() }
    }

    private fun updateScales() {
        displayScaleX = width.toFloat() / screenshotW
        displayScaleY = height.toFloat() / screenshotH
    }

    private fun mapRect(r: Rect): RectF {
        val left   = (r.left   + cropOffsetX) * displayScaleX
        val top    = (r.top    + cropOffsetY) * displayScaleY
        val right  = (r.right  + cropOffsetX) * displayScaleX
        val bottom = (r.bottom + cropOffsetY) * displayScaleY
        return RectF(left, top, right, bottom)
    }

    private fun rebuildChildren() {
        removeAllViews()
        if (boxes.isEmpty()) return

        val displayW = width.toFloat()
        val displayH = height.toFloat()

        // Map OCR bounds to screen coordinates, expanded by boxPadding
        val screenRects = boxes.map { box ->
            val r = mapRect(box.bounds)
            RectF(
                (r.left - boxPadding).coerceAtLeast(0f),
                (r.top - boxPadding).coerceAtLeast(0f),
                (r.right + boxPadding).coerceAtMost(displayW),
                (r.bottom + boxPadding).coerceAtMost(displayH)
            )
        }

        // Resolve vertical overlaps: trim shared edge to midpoint
        val finalRects = screenRects.map { RectF(it) }
        val sortedIndices = finalRects.indices.sortedBy { finalRects[it].top }
        for (a in sortedIndices.indices) {
            for (b in a + 1 until sortedIndices.size) {
                val i = sortedIndices[a]
                val j = sortedIndices[b]
                val ri = finalRects[i]
                val rj = finalRects[j]
                if (ri.bottom > rj.top && ri.left < rj.right && ri.right > rj.left) {
                    val mid = (ri.bottom + rj.top) / 2f
                    ri.bottom = mid
                    rj.top = mid
                }
            }
        }

        // Create a TextView for each box
        boxes.zip(finalRects).forEach { (box, rect) ->
            val rectW = rect.width().toInt().coerceAtLeast(1)
            val rectH = rect.height().toInt().coerceAtLeast(1)

            val tv = TextView(context).apply {
                text = box.translatedText
                setTextColor(box.textColor)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(textMargin, textMargin, textMargin, textMargin)
                setBackgroundColor(box.bgColor)
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, minTextSizeSp, maxTextSizeSp, 1, TypedValue.COMPLEX_UNIT_SP
                )
            }

            val lp = LayoutParams(rectW, rectH).apply {
                leftMargin = rect.left.toInt()
                topMargin = rect.top.toInt()
            }
            addView(tv, lp)
        }
    }
}
