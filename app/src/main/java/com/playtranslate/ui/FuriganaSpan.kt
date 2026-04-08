package com.playtranslate.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan

/**
 * Inline furigana (ruby text) span. Draws the original kanji at the baseline
 * and the reading above it in a smaller font. Adjusts line metrics so the
 * line is tall enough to fit the furigana without clipping.
 */
class FuriganaSpan(private val reading: String) : ReplacementSpan() {

    private companion object {
        const val FURIGANA_SCALE = 0.5f
        const val FURIGANA_GAP = 0.15f // fraction of furigana size, gap between reading and kanji
    }

    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?
    ): Int {
        val kanjiWidth = paint.measureText(text, start, end)
        val furiganaSize = paint.textSize * FURIGANA_SCALE
        val furiganaPaint = Paint(paint).apply { textSize = furiganaSize }
        val furiganaWidth = furiganaPaint.measureText(reading)

        if (fm != null) {
            paint.getFontMetricsInt(fm)
            val furiganaHeight = (furiganaSize * (1f + FURIGANA_GAP)).toInt()
            fm.ascent -= furiganaHeight
            fm.top -= furiganaHeight
        }

        return maxOf(kanjiWidth, furiganaWidth).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val kanjiWidth = paint.measureText(text, start, end)
        val furiganaSize = paint.textSize * FURIGANA_SCALE
        val furiganaPaint = Paint(paint).apply { textSize = furiganaSize }
        val furiganaWidth = furiganaPaint.measureText(reading)

        val spanWidth = maxOf(kanjiWidth, furiganaWidth)

        // Draw kanji centered
        val kanjiX = x + (spanWidth - kanjiWidth) / 2f
        canvas.drawText(text, start, end, kanjiX, y.toFloat(), paint)

        // Draw furigana reading centered above kanji
        val furiganaX = x + (spanWidth - furiganaWidth) / 2f
        val furiganaFm = furiganaPaint.fontMetrics
        val gap = furiganaSize * FURIGANA_GAP
        val furiganaY = y.toFloat() + paint.fontMetrics.ascent - gap - furiganaFm.descent
        canvas.drawText(reading, furiganaX, furiganaY, furiganaPaint)
    }
}
