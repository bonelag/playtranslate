package com.playtranslate.ui

import android.graphics.Rect
import android.graphics.RectF

/**
 * Maps OCR bitmap-space bounding boxes to screen-space coordinates,
 * accounting for the view's actual on-screen position.
 *
 * Why this file exists (merge-conflict strategy):
 * The original code in OcrDebugOverlayView and TranslationOverlayView used
 * displayScaleX/displayScaleY to scale OCR coordinates, which assumes the
 * overlay view starts at screen origin (0,0). On devices with status bars,
 * navigation bars, or multiple displays, the overlay is often offset from
 * origin, causing bounding boxes to render shifted down/right from the
 * actual text.
 *
 * This file isolates the corrected mapping logic in one place. The two
 * overlay views inject it via a minimal, single-line change to their
 * mapRect method — keeping the original file diff tiny and reducing
 * merge conflicts when the upstream author publishes updates.
 */
object BoundingBoxMapper {

    /**
     * Maps a single [Rect] from bitmap coordinates to screen coordinates.
     *
     * @param r         OCR bounding box in bitmap space
     * @param viewLocation overlay view location from [android.view.View.getLocationOnScreen]
     * @param scaleFactor  OCR downscale factor applied by ML Kit (1 = no scaling)
     * @param cropOffsetX  left crop offset in bitmap pixels
     * @param cropOffsetY  top crop offset in bitmap pixels
     * @return [RectF] in the view's local coordinate system, ready for
     *         [android.graphics.Canvas.drawRect]
     */
    fun mapRect(
        r: Rect,
        viewLocation: IntArray,
        scaleFactor: Float,
        cropOffsetX: Int,
        cropOffsetY: Int,
    ): RectF {
        val physicalLeft   = r.left   / scaleFactor + cropOffsetX
        val physicalTop    = r.top    / scaleFactor + cropOffsetY
        val physicalRight  = r.right  / scaleFactor + cropOffsetX
        val physicalBottom = r.bottom / scaleFactor + cropOffsetY

        val left   = physicalLeft   - viewLocation[0]
        val top    = physicalTop    - viewLocation[1]
        val right  = physicalRight  - viewLocation[0]
        val bottom = physicalBottom - viewLocation[1]

        return RectF(left, top, right, bottom)
    }

    /**
     * Maps a [Rect] from bitmap coordinates to screen coordinates without
     * a scale factor (scaleFactor = 1). Convenience overload for callers
     * whose coordinates are already at 1:1 bitmap scale — which is the
     * case for TranslationOverlayView (its incoming boxes are pre-scaled).
     *
     * @param r         bounding box in bitmap space (1:1)
     * @param viewLocation overlay view location from [android.view.View.getLocationOnScreen]
     * @param cropOffsetX  left crop offset
     * @param cropOffsetY  top crop offset
     * @return [RectF] in the view's local coordinate system
     */
    fun mapRect(
        r: Rect,
        viewLocation: IntArray,
        cropOffsetX: Int,
        cropOffsetY: Int,
    ): RectF {
        val physicalLeft   = r.left   + cropOffsetX
        val physicalTop    = r.top    + cropOffsetY
        val physicalRight  = r.right  + cropOffsetX
        val physicalBottom = r.bottom + cropOffsetY

        val left   = physicalLeft   - viewLocation[0]
        val top    = physicalTop    - viewLocation[1]
        val right  = physicalRight  - viewLocation[0]
        val bottom = physicalBottom - viewLocation[1]

        return RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }
}
