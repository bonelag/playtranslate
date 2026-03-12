package com.gamelens.ui

/**
 * In-memory cache for the most recent sentence translation result.
 * Written by MainActivity when a translation completes; read by
 * DragLookupController when launching the Anki review from the
 * floating popup.
 *
 * Safe because the Activity and AccessibilityService share one process.
 */
object LastSentenceCache {
    var original: String? = null
    var translation: String? = null
    var wordResults: Map<String, Triple<String, String, Int>>? = null

    fun clear() {
        original = null
        translation = null
        wordResults = null
    }
}
