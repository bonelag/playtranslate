package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * OCR preprocessing recipe optimized for Latin-script languages with diacritics
 * (Vietnamese, French, Spanish, Turkish, Romanian, Indonesian, etc.).
 *
 * Compared to [OcrPreprocessingRecipe.Default] (SigmoidContrast k=7,
 * 1200px target, 3× max upscale, no sharpen), this recipe:
 * - Upscales to 1400 px short side (max 4×) for finer diacritic detail
 * - Uses gentler sigmoid contrast (k=4) to preserve thin accent strokes
 * - Applies a soft unsharp mask convolution sharpen to enhance diacritic edges
 *
 * Design rationale (backed by golden-set measurements in commits
 * ee817df and 440cc02):
 * - Linear contrast at factor ≥2.0 destroys the anti-aliased edge gradients
 *   of Vietnamese tone marks (ả, ạ, ắ, ấ, etc.) — sigmoid avoids hard-clamping.
 * - A 3×3 sharpen kernel with halved intensity (soft unsharp mask) boosts
 *   diacritic recognition without overshoot artifacts that confuse ML Kit.
 * - 1400 px target is the sweet spot: enough resolution for reliable
 *   dấu hỏi / dấu ngã discrimination, but not so large that OCR latency
 *   becomes a problem on mid-range devices.
 *
 * ## Merge-conflict strategy
 *
 * This file is entirely new — no upstream file was edited to add these
 * constants or the sharpen kernel. The only change to upstream code is a
 * single-line recipe-selection call in [OcrManager.recognise] and
 * [OcrManager.recogniseWithPositions] so the diff stays small.
 */
object VietnameseOcrPreprocessing : OcrPreprocessingRecipe {

    // ── Tuning constants ────────────────────────────────────────────────

    /** Target minimum dimension for Latin/Vietnamese OCR (pixels). */
    private const val TARGET_MIN_DIM = 1400

    /** Maximum output dimension to prevent OOM on narrow crops. */
    private const val MAX_DIM = 3500

    /** Maximum upscale multiplier (vs default 3×). */
    private const val MAX_UPSCALE = 4f

    /** Sigmoid steepness — gentler than the default k=7 so thin diacritic
     *  strokes survive the contrast curve without being crushed to 0 or 255. */
    private const val SIGMOID_K = 4f

    // ── OcrPreprocessingRecipe ──────────────────────────────────────────

    override fun apply(bitmap: Bitmap, isDarkBackground: Boolean): Bitmap {
        // 1. Determine scale factor (Latin/Vietnamese upscale more aggressively)
        val (scale, w, h) = upscaleParams(bitmap)

        // 2. Scale + grayscale
        val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(grayMatrix)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        if (scale != 1f) canvas.scale(scale, scale)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        // 3. Sigmoid contrast (k=4, gentler than default k=7)
        val baseLut = buildSigmoidLut(SIGMOID_K)
        val lut = if (shouldInvert(InvertMode.AUTO, isDarkBackground)) {
            IntArray(256) { 255 - baseLut[it] }
        } else baseLut
        applyGrayLut(out, lut)

        // 4. Soft unsharp mask sharpen — last step, operates on already-
        //    contrasted grayscale pixels to enhance diacritic edges.
        val sharpened = sharpenGrayscale(out)
        out.recycle()
        return sharpened
    }

    // ── Scale ───────────────────────────────────────────────────────────

    private fun upscaleParams(bitmap: Bitmap): Triple<Float, Int, Int> {
        val minDim = minOf(bitmap.width, bitmap.height)
        var scale = if (minDim < TARGET_MIN_DIM)
            (TARGET_MIN_DIM.toFloat() / minDim).coerceAtMost(MAX_UPSCALE)
        else 1f
        if (bitmap.width * scale > MAX_DIM || bitmap.height * scale > MAX_DIM) {
            scale = minOf(
                MAX_DIM.toFloat() / bitmap.width,
                MAX_DIM.toFloat() / bitmap.height
            )
        }
        return Triple(scale, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
    }

    // ── Sharpen ─────────────────────────────────────────────────────────

    /**
     * Soft unsharp mask via 3×3 convolution.
     *
     * Kernel (halved intensity vs classic 5-centre unsharp mask):
     * ```
     * [ 0   -0.5   0  ]
     * [-0.5   3   -0.5]
     * [ 0   -0.5   0  ]
     * ```
     *
     * Equivalent to: `v = (6*centre - neighbours) / 2`
     *
     * The 50% reduction avoids overshoot halos on thin diacritic strokes
     * (ẻ, ỉ, ổ etc.) while still boosting edge contrast enough that ML Kit
     * can distinguish tone marks from noise.
     */
    private fun sharpenGrayscale(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val outPixels = IntArray(width * height)

        // Interior pixels: apply 3×3 soft-unsharp-mask kernel
        for (y in 1 until height - 1) {
            var p = y * width + 1
            for (x in 1 until width - 1) {
                val center = pixels[p]
                val top = pixels[p - width]
                val bottom = pixels[p + width]
                val left = pixels[p - 1]
                val right = pixels[p + 1]

                // Image is grayscale → R=G=B, so read just Blue channel
                val cV = center and 0xff
                val tV = top and 0xff
                val bV = bottom and 0xff
                val lV = left and 0xff
                val rV = right and 0xff

                // Soft unsharp mask: (6×centre − neighbours) / 2
                var v = (6 * cV - tV - bV - lV - rV) / 2
                if (v < 0) v = 0 else if (v > 255) v = 255

                outPixels[p] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                p++
            }
        }

        // Copy border rows and columns (no neighbours available)
        for (x in 0 until width) {
            outPixels[x] = pixels[x]                                       // top row
            outPixels[(height - 1) * width + x] = pixels[(height - 1) * width + x] // bottom row
        }
        for (y in 0 until height) {
            outPixels[y * width] = pixels[y * width]                       // left column
            outPixels[y * width + width - 1] = pixels[y * width + width - 1] // right column
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return result
    }
}

// ── Language-based recipe selection ─────────────────────────────────────

/** Languages that benefit from the diacritic-optimized preprocessing path. */
private val LATIN_DIACRITIC_LANGS = setOf(
    "vi", "en", "fr", "es", "pt", "tr", "ro", "id",
    "it", "de", "nl", "sv", "da", "no", "fi", "ca",
    "hu", "cs", "sk", "sl", "hr", "sq", "lt", "lv",
    "et", "mt", "ga", "cy", "eo", "af", "gl", "eu",
    "pl"
)

/**
 * Returns the optimal [OcrPreprocessingRecipe] for the given [sourceLang]
 * (ML Kit translation code). Latin-script languages with diacritics get
 * [VietnameseOcrPreprocessing]; all others fall back to the default recipe.
 *
 * Called from [OcrManager.recognise] and [OcrManager.recogniseWithPositions].
 */
fun selectOcrRecipe(sourceLang: String): OcrPreprocessingRecipe {
    return if (sourceLang in LATIN_DIACRITIC_LANGS) {
        VietnameseOcrPreprocessing
    } else {
        OcrPreprocessingRecipe.Default
    }
}
