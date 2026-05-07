package com.playtranslate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.playtranslate.OcrManager

/**
 * Transparent overlay that draws OCR bounding boxes on the game screen.
 * Currently shows:
 * - TextBlock boxes: thick red border
 * - Group boxes (combined TextBlocks): thick blue border
 * Line and element boxes are collected but not drawn (available for future use).
 */
class OcrDebugOverlayView(context: Context) : View(context) {

    private val dp = context.resources.displayMetrics.density

    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f * dp
    }

    private val groupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4488FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f * dp
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
    }

    private var debugBoxes: OcrManager.OcrDebugBoxes? = null
    private var cropOffsetX = 0
    private var cropOffsetY = 0
    private var screenshotW = 1
    private var screenshotH = 1

    fun setBoxes(
        boxes: OcrManager.OcrDebugBoxes,
        cropLeft: Int, cropTop: Int,
        screenshotW: Int, screenshotH: Int
    ) {
        debugBoxes = boxes
        cropOffsetX = cropLeft
        cropOffsetY = cropTop
        this.screenshotW = screenshotW
        this.screenshotH = screenshotH
        invalidate()
    }

    private fun mapRect(r: Rect, scaleFactor: Float): RectF {
        return BoundingBoxMapper.mapRect(r, this, scaleFactor, cropOffsetX, cropOffsetY)
    }

    override fun onDraw(canvas: Canvas) {
        val boxes = debugBoxes ?: return
        val sf = boxes.scaleFactor

        // Individual TextBlock boxes (red)
        for (box in boxes.blockBoxes) {
            val rf = mapRect(box.bounds, sf)
            canvas.drawRect(rf, blockPaint)
        }

        // Line boxes (thin green)
        for (box in boxes.lineBoxes) {
            val rf = mapRect(box.bounds, sf)
            canvas.drawRect(rf, linePaint)
        }

        // Combined group boxes (blue)
        for (box in boxes.groupBoxes) {
            val rf = mapRect(box.bounds, sf)
            canvas.drawRect(rf, groupPaint)
        }
    }
}
