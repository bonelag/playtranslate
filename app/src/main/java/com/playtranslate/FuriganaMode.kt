package com.playtranslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log
import com.playtranslate.dictionary.DictionaryManager
import com.playtranslate.model.TranslationResult
import com.playtranslate.ui.TranslationOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "FuriganaMode"

/**
 * Live furigana overlay mode. Shows hiragana readings above kanji on the
 * game screen. Uses OCR-based change detection: keeps a clean reference
 * bitmap, patches overlay regions from it on raw frames, OCRs the patched
 * image, and compares text to detect game scene changes.
 *
 * Owns ALL its mutable state. When stopped, scope is cancelled and all
 * state (including cleanRefBitmap) is released.
 */
class FuriganaMode(private val service: CaptureService) : LiveMode {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var cleanProcessingJob: Job? = null

    // ── Mode-owned state ──────────────────────────────────────────────────

    private var cachedFuriganaBoxes: List<TranslationOverlayView.TextBox>? = null
    private var cleanRefBitmap: Bitmap? = null
    private var lastOcrText: String? = null
    private var cropLeft = 0
    private var cropTop = 0
    private var screenshotW = 0
    private var screenshotH = 0
    private var showRegionFlash = true

    // ── LiveMode interface ────────────────────────────────────────────────

    override fun start() {
        val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
        if (mgr == null) {
            DetectionLog.log("ERROR: screenshotManager is null, can't start furigana loop")
            return
        }
        DetectionLog.log("Starting furigana loop on display ${service.gameDisplayId}")
        mgr.requestCleanCapture()
        mgr.startLoop(service.gameDisplayId, service.serviceScope,
            onCleanFrame = ::handleCleanFrame,
            onRawFrame = ::handleRawFrame
        )
    }

    override fun stop() {
        cleanProcessingJob?.cancel()
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
        scope.cancel()
        PlayTranslateAccessibilityService.instance?.screenshotManager?.stopLoop()
        PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
    }

    override fun refresh() {
        cachedFuriganaBoxes = null
        cleanRefBitmap?.recycle()
        cleanRefBitmap = null
        lastOcrText = null
        cleanProcessingJob?.cancel()
        PlayTranslateAccessibilityService.instance?.screenshotManager?.requestCleanCapture()
    }

    override fun getCachedState(): CachedOverlayState? {
        val boxes = cachedFuriganaBoxes ?: return null
        return CachedOverlayState(boxes, cropLeft, cropTop, screenshotW, screenshotH)
    }

    // ── Clean frame handling ──────────────────────────────────────────────

    private fun handleCleanFrame(raw: Bitmap) {
        cleanProcessingJob?.cancel()
        cleanProcessingJob = scope.launch {
            try {
                processCleanFrame(raw)
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (service.liveActive) {
                    PlayTranslateAccessibilityService.instance?.screenshotManager?.requestCleanCapture()
                }
                throw e
            }
        }
    }

    private suspend fun processCleanFrame(raw: Bitmap) {
        if (!service.isConfigured) { raw.recycle(); return }

        try {
            val statusBarHeight = service.getStatusBarHeightForDisplay(service.gameDisplayId)
            val top    = maxOf((raw.height * service.activeRegion.top).toInt(), statusBarHeight)
            val left   = (raw.width  * service.activeRegion.left).toInt()
            val bottom = (raw.height * service.activeRegion.bottom).toInt()
            val right  = (raw.width  * service.activeRegion.right).toInt()
            val needsCrop = top > 0 || left > 0 || bottom < raw.height || right < raw.width
            val bitmap = if (needsCrop)
                Bitmap.createBitmap(raw, left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
            else raw

            if (showRegionFlash) {
                showRegionFlash = false
                service.flashRegionIndicator()
            }

            val ocrBitmap = service.blackoutFloatingIcon(bitmap, left, top)
            val ocrResult = service.ocrManager.recognise(ocrBitmap, service.sourceLang, screenshotWidth = raw.width)
            if (ocrBitmap !== raw && ocrBitmap !== bitmap) ocrBitmap.recycle()

            if (ocrResult == null) {
                cachedFuriganaBoxes = null
                PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                service.onLiveNoText?.invoke()
                return
            }

            val newText = ocrResult.fullText
            val dedupKey = newText.filter { c -> OcrManager.isSourceLangChar(c, service.sourceLang) }
            if (dedupKey.isEmpty()) {
                cachedFuriganaBoxes = null
                PlayTranslateAccessibilityService.instance?.hideTranslationOverlay()
                service.onLiveNoText?.invoke()
                return
            }

            // Dedup: if text unchanged and we have cached furigana, re-show
            if (lastOcrText != null && !OverlayToolkit.isSignificantChange(lastOcrText!!, dedupKey)) {
                val boxes = cachedFuriganaBoxes
                if (boxes != null) {
                    service.showLiveOverlay(boxes, cropLeft, cropTop, screenshotW, screenshotH)
                    return
                }
            }

            lastOcrText = dedupKey

            // Build and show furigana
            val dict = DictionaryManager.get(service)
            val furigana = OverlayToolkit.buildFuriganaBoxes(ocrResult, dict, service.furiganaPaint)
            cachedFuriganaBoxes = furigana
            this@FuriganaMode.cropLeft = left
            this@FuriganaMode.cropTop = top
            this@FuriganaMode.screenshotW = raw.width
            this@FuriganaMode.screenshotH = raw.height

            if (furigana.isNotEmpty()) {
                service.showLiveOverlay(furigana, left, top, raw.width, raw.height)
            }

            // Save clean reference for patching raw frames
            cleanRefBitmap?.recycle()
            cleanRefBitmap = raw.copy(raw.config ?: Bitmap.Config.ARGB_8888, false)

            // Save screenshot for Anki
            val mgr = PlayTranslateAccessibilityService.instance?.screenshotManager
            mgr?.saveToCache(raw)
            val screenshotPath = mgr?.lastCleanPath

            // Send to in-app panel if visible
            val appPanelVisible = !Prefs.isSingleScreen(service) && MainActivity.isInForeground
            if (appPanelVisible) {
                service.onTranslationStarted?.invoke()
                val perGroup = service.translateGroupsSeparately(ocrResult.groupTexts)
                val translated = perGroup.joinToString("\n\n") { it.first }
                val note = perGroup.mapNotNull { it.second }.firstOrNull()
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                service.onResult?.invoke(TranslationResult(
                    originalText   = newText,
                    segments       = ocrResult.segments,
                    translatedText = translated,
                    timestamp      = timestamp,
                    screenshotPath = screenshotPath,
                    note           = note
                ))
            }
        } finally {
            if (!raw.isRecycled) raw.recycle()
        }
    }

    // ── Raw frame handling (OCR-based change detection) ───────────────────

    private fun handleRawFrame(bitmap: Bitmap) {
        if (cleanProcessingJob?.isActive == true) {
            bitmap.recycle()
            return
        }

        val ref = cleanRefBitmap
        val boxes = cachedFuriganaBoxes
        if (ref == null || boxes.isNullOrEmpty()) {
            bitmap.recycle()
            return
        }

        // Patch: copy overlay regions from clean reference into raw frame
        val patched = if (bitmap.isMutable) bitmap
            else bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true).also { bitmap.recycle() }
        val canvas = Canvas(patched)
        for (box in boxes) {
            val srcRect = android.graphics.Rect(
                box.bounds.left + cropLeft, box.bounds.top + cropTop,
                box.bounds.right + cropLeft, box.bounds.bottom + cropTop
            )
            srcRect.inset(-4, -4)
            srcRect.intersect(0, 0, ref.width, ref.height)
            canvas.drawBitmap(ref, srcRect, RectF(
                srcRect.left.toFloat(), srcRect.top.toFloat(),
                srcRect.right.toFloat(), srcRect.bottom.toFloat()
            ), null)
        }

        // OCR the patched frame asynchronously
        scope.launch {
            try {
                val statusBarHeight = service.getStatusBarHeightForDisplay(service.gameDisplayId)
                val top    = maxOf((patched.height * service.activeRegion.top).toInt(), statusBarHeight)
                val left   = (patched.width  * service.activeRegion.left).toInt()
                val bottom = (patched.height * service.activeRegion.bottom).toInt()
                val right  = (patched.width  * service.activeRegion.right).toInt()
                val needsCrop = top > 0 || left > 0 || bottom < patched.height || right < patched.width
                val cropped = if (needsCrop)
                    Bitmap.createBitmap(patched, left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
                else patched

                val ocrBitmap = service.blackoutFloatingIcon(cropped, left, top)
                val ocrResult = service.ocrManager.recognise(ocrBitmap, service.sourceLang, screenshotWidth = patched.width)
                if (ocrBitmap !== patched && ocrBitmap !== cropped) ocrBitmap.recycle()

                if (ocrResult != null) {
                    val newDedupKey = ocrResult.fullText
                        .filter { c -> OcrManager.isSourceLangChar(c, service.sourceLang) }
                    val prevText = lastOcrText

                    if (prevText != null && OverlayToolkit.isSignificantChange(prevText, newDedupKey)) {
                        DetectionLog.log("Furigana: text changed, requesting clean capture")
                        cleanRefBitmap?.recycle()
                        cleanRefBitmap = null
                        PlayTranslateAccessibilityService.instance?.screenshotManager?.requestCleanCapture()
                    }
                }
            } finally {
                if (!patched.isRecycled) patched.recycle()
            }
        }
    }
}
