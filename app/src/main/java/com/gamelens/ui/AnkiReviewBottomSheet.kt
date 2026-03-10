package com.gamelens.ui

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.gamelens.AnkiManager
import com.gamelens.Prefs
import com.gamelens.R
import com.gamelens.fullScreenDialogTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gamelens.dictionary.Deinflector
import java.io.File

class AnkiReviewBottomSheet : DialogFragment() {

    private data class WordEntry(val word: String, val reading: String, val meaning: String, val freqScore: Int = 0)

    private val activeWords = mutableListOf<WordEntry>()
    private var includePhoto = true
    private var deckEntries: List<Map.Entry<Long, String>> = emptyList()

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_anki_review, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideRight)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnBackReview).setOnClickListener { dismiss() }

        val args = arguments ?: return
        val original       = args.getString(ARG_ORIGINAL) ?: ""
        val translation    = args.getString(ARG_TRANSLATION) ?: ""
        val words          = args.getStringArray(ARG_WORDS) ?: emptyArray()
        val readings       = args.getStringArray(ARG_READINGS) ?: emptyArray()
        val meanings       = args.getStringArray(ARG_MEANINGS) ?: emptyArray()
        val screenshotPath = args.getString(ARG_SCREENSHOT_PATH)

        val freqScores = args.getIntArray(ARG_FREQ_SCORES) ?: IntArray(0)
        words.forEachIndexed { i, word ->
            activeWords.add(
                WordEntry(
                    word,
                    readings.getOrElse(i) { "" },
                    meanings.getOrElse(i) { "" },
                    freqScores.getOrElse(i) { 0 }
                )
            )
        }

        val spinnerDeck    = view.findViewById<Spinner>(R.id.spinnerReviewDeck)
        val etJapanese     = view.findViewById<EditText>(R.id.etReviewJapanese)
        val etTranslation  = view.findViewById<EditText>(R.id.etReviewTranslation)
        val tvPhotoLabel   = view.findViewById<TextView>(R.id.tvReviewPhotoLabel)
        val layoutPhoto    = view.findViewById<FrameLayout>(R.id.layoutReviewPhoto)
        val ivPhoto        = view.findViewById<ImageView>(R.id.ivReviewPhoto)
        val btnRemovePhoto = view.findViewById<Button>(R.id.btnRemovePhoto)
        val defsContainer  = view.findViewById<LinearLayout>(R.id.reviewDefinitionsContainer)
        val btnSend        = view.findViewById<Button>(R.id.btnSendToAnki)

        etJapanese.setText(original)
        etTranslation.setText(translation)


        if (screenshotPath != null) {
            val file = File(screenshotPath)
            if (file.exists()) {
                tvPhotoLabel.visibility = View.VISIBLE
                layoutPhoto.visibility  = View.VISIBLE
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) ivPhoto.setImageBitmap(bmp)
                btnRemovePhoto.setOnClickListener {
                    includePhoto = false
                    tvPhotoLabel.visibility = View.GONE
                    layoutPhoto.visibility  = View.GONE
                }
            }
        }

        rebuildWordRows(defsContainer)
        loadDecks(spinnerDeck)

        btnSend.setOnClickListener {
            val japanese = etJapanese.text.toString()
            val english  = etTranslation.text.toString()
            val deckId   = deckEntries.getOrNull(spinnerDeck.selectedItemPosition)?.key
                ?: Prefs(requireContext()).ankiDeckId
            if (deckId < 0L) {
                Toast.makeText(requireContext(), getString(R.string.anki_no_deck_selected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSend.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                sendToAnki(japanese, english, deckId, if (includePhoto) screenshotPath else null)
                btnSend.isEnabled = true
            }
        }
    }

    private fun rebuildWordRows(container: LinearLayout) {
        container.removeAllViews()
        activeWords.forEachIndexed { index, entry ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 12)
            }

            val textBlock = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvHeader = TextView(requireContext()).apply {
                text = entry.word
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                setTypeface(null, Typeface.BOLD)
            }
            textBlock.addView(tvHeader)

            if (entry.reading.isNotEmpty() || entry.freqScore > 0) {
                val readingLine = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                if (entry.reading.isNotEmpty()) {
                    readingLine.addView(TextView(requireContext()).apply {
                        text = entry.reading
                        textSize = 11f
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                    })
                }
                if (entry.freqScore > 0) {
                    readingLine.addView(TextView(requireContext()).apply {
                        text = "  " + starsString(entry.freqScore)
                        textSize = 10f
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                    })
                }
                textBlock.addView(readingLine)
            }

            entry.meaning.split("\n").filter { it.isNotBlank() }.forEach { line ->
                val tvLine = TextView(requireContext()).apply {
                    text = line
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    setPadding(16, 0, 0, 0)
                }
                textBlock.addView(tvLine)
            }

            val btnRemove = Button(requireContext()).apply {
                text = "✕"
                textSize = 14f
                setBackgroundResource(android.R.color.transparent)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    activeWords.removeAt(index)
                    rebuildWordRows(container)
                }
            }

            row.addView(textBlock)
            row.addView(btnRemove)
            container.addView(row)
        }
    }

    private fun loadDecks(spinner: Spinner) {
        loadAnkiDecksInto(spinner) { entries -> deckEntries = entries }
    }

    private suspend fun sendToAnki(
        japanese: String,
        english: String,
        deckId: Long,
        screenshotPath: String?
    ) {
        val ankiManager = AnkiManager(requireContext())

        val imageFilename: String? = if (screenshotPath != null) {
            withContext(Dispatchers.IO) { ankiManager.addMediaFromFile(File(screenshotPath)) }
        } else null

        val front = buildFrontHtml(japanese)
        val back  = buildBackHtml(japanese, english, imageFilename)

        val success = withContext(Dispatchers.IO) { ankiManager.addNote(deckId, front, back) }
        val msg = if (success) getString(R.string.anki_added) else getString(R.string.anki_failed)
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

        if (success) {
            // Signal SentenceDetailBottomSheet to dismiss itself too
            parentFragmentManager.setFragmentResult(RESULT_ANKI_ADDED, bundleOf())
            dismiss()
        }
    }

    private fun buildFrontHtml(japanese: String): String {
        val clean = japanese.replace(Regex("[\\n\\r]+"), " ").trim()
        val annotated = buildAnnotatedFront(clean)
        return buildString {
            // rt is always hidden; tapping a ruby shows a floating tooltip bubble above the word
            append("<style>")
            append(".gl-front ruby{cursor:pointer;-webkit-tap-highlight-color:transparent;}")
            append(".gl-front ruby rt{display:none;}")
            append(".gl-tip{position:fixed;background:rgba(40,40,40,0.93);color:#fff;padding:6px 16px;border-radius:8px;font-size:20px;pointer-events:none;z-index:9999;white-space:nowrap;box-shadow:0 2px 8px rgba(0,0,0,0.45);}")
            append(".gl-tip::after{content:'';position:absolute;top:100%;left:50%;transform:translateX(-50%);border:6px solid transparent;border-top-color:rgba(40,40,40,0.93);}")
            append("</style>")
            append("<div class=\"gl-front\" style=\"text-align:center;font-size:1.5em;padding:20px;line-height:2.8em;\">$annotated</div>")
            append("<script>(function(){")
            append("var tip=null,activeR=null;")
            append("function hide(){if(tip){tip.parentNode.removeChild(tip);tip=null;}activeR=null;}")
            append("function showTip(r,e){")
            append("e.stopPropagation();e.preventDefault();")
            append("if(activeR===r){hide();return;}")
            append("hide();")
            append("var rt=r.querySelector('rt');if(!rt)return;")
            append("var rect=r.getBoundingClientRect();")
            append("tip=document.createElement('div');tip.className='gl-tip';")
            append("tip.textContent=rt.textContent;")
            append("tip.style.left=(rect.left+rect.width/2)+'px';")
            append("tip.style.top=rect.top+'px';")
            append("tip.style.transform='translate(-50%,calc(-100% - 8px))';")
            append("document.body.appendChild(tip);")
            append("activeR=r;")
            append("}")
            append("document.querySelectorAll('.gl-front ruby').forEach(function(r){")
            // touchend fires before AnkiMobile's card-flip handler so stopPropagation here
            // prevents the card from advancing on iOS when tapping a ruby word
            append("r.addEventListener('touchend',function(e){showTip(r,e);});")
            append("r.addEventListener('click',function(e){showTip(r,e);});")
            append("});")
            append("document.addEventListener('touchend',function(e){if(activeR&&!activeR.contains(e.target))hide();});")
            append("document.addEventListener('click',function(e){if(activeR&&!activeR.contains(e.target))hide();});")
            append("})()</script>")
        }
    }

    private fun buildAnnotatedFront(text: String): String {
        val wordMap = activeWords.associate { it.word to it.reading }
        return annotateText(text, wordMap, newlineAsBr = false)
    }

    private fun buildBackHtml(japanese: String, english: String, imageFilename: String?): String {
        val furigana  = buildFuriganaHtml(japanese)
        val wordsHtml = buildWordsHtml()
        return buildString {
            // AnkiDroid always uses afmt={{FrontSide}}\n\n<hr id=answer>\n\n{{Back}}.
            // The back page therefore contains FrontSide HTML, two \n\n text nodes, the
            // <hr id=answer> element, two more \n\n text nodes, then our Back field.
            //
            // visibility:hidden on body hides ALL children including text nodes (which
            // have no tag and cannot be targeted by display:none selectors).
            // visibility:visible on a descendant CAN override the inherited hidden —
            // this is the key difference from opacity, which cannot be overridden.
            append("<style>")
            // body visibility:hidden makes the \n\n text nodes injected by AnkiDroid's afmt invisible.
            // .gl-front and #answer use display:none to remove them from layout entirely
            // (visibility:hidden alone keeps their space). .gl-back restores visibility in normal flow.
            append("body{visibility:hidden!important;white-space:normal!important;}")
            append(".gl-front{display:none!important;}")
            append("#answer{display:none!important;}")
            append(".gl-back{visibility:visible!important;}")
            append("</style>")
            append("<div class=\"gl-back\">")
            if (imageFilename != null) {
                append("<div style=\"text-align:center;margin:12px 0;\">")
                append("<img src=\"$imageFilename\" style=\"max-width:100%;border-radius:6px;\">")
                append("</div>")
            }
            append("<div style=\"text-align:center;font-size:1.5em;margin:12px 4px;line-height:2.2em;\">$furigana</div>")
            append("<div class=\"gl-secondary\" style=\"text-align:center;font-size:1.2em;margin:12px 4px;\">")
            append(english.replace(Regex("[\\n\\r]+"), "<br>"))
            append("</div>")
            if (wordsHtml.isNotEmpty()) {
                append("<hr>")
                append("<div style=\"text-align:left;margin-top:8px;\">$wordsHtml</div>")
            }
            append("</div>")
        }
    }

    private fun buildFuriganaHtml(text: String): String {
        val wordMap = activeWords.associate { it.word to it.reading }
        return annotateText(text, wordMap, newlineAsBr = true)
    }

    /**
     * Annotates [text] with ruby furigana for words in [wordMap].
     * Uses direct substring matching (longest-match-first) so inflected forms like 移って
     * are found even when the BreakIterator would split them differently.
     */
    private fun annotateText(text: String, wordMap: Map<String, String>, newlineAsBr: Boolean): String {
        if (wordMap.isEmpty()) return text
        val sortedWords = wordMap.entries
            .filter { it.key.isNotEmpty() }
            .sortedByDescending { it.key.length }
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                sb.append(if (newlineAsBr) "<br>" else " ")
                i++
                continue
            }
            // 1. Direct match (dictionary form present verbatim in text)
            val direct = sortedWords.firstOrNull { (word, _) -> text.startsWith(word, i) }
            if (direct != null) {
                val (word, reading) = direct
                val hasKanji = word.any { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' }
                if (hasKanji && reading.isNotEmpty() && reading != word) {
                    sb.append("<ruby>$word<rt>$reading</rt></ruby>")
                } else {
                    sb.append(word)
                }
                i += word.length
                continue
            }
            // 2. Deinflection fallback — handles conjugated forms like 移って → 移る.
            //    Only attempted when the current character is Japanese (CJK/kana).
            val isJapanese = c in '\u3000'..'\u9fff' || c in '\uf900'..'\ufaff'
            if (isJapanese) {
                val maxEnd = minOf(i + 12, text.length)
                var deinflected = false
                for (end in maxEnd downTo i + 1) {
                    val sub = text.substring(i, end)
                    val matchedEntry = Deinflector.candidates(sub)
                        .asSequence()
                        .mapNotNull { cand -> sortedWords.firstOrNull { it.key == cand.text } }
                        .firstOrNull()
                    if (matchedEntry != null) {
                        val reading = matchedEntry.value
                        val hasKanji = sub.any { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' }
                        if (hasKanji && reading.isNotEmpty() && reading != sub) {
                            sb.append("<ruby>$sub<rt>$reading</rt></ruby>")
                        } else {
                            sb.append(sub)
                        }
                        i = end
                        deinflected = true
                        break
                    }
                }
                if (deinflected) continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun starsString(score: Int) = "★".repeat(score)

    private fun buildWordsHtml(): String {
        if (activeWords.isEmpty()) return ""
        val sb = StringBuilder()
        activeWords.forEach { entry ->
            sb.append("<div style=\"margin-bottom:14px;\">")
            sb.append("<div><b>${entry.word}</b></div>")
            if (entry.reading.isNotEmpty() || entry.freqScore > 0) {
                sb.append("<div style=\"font-size:0.85em;\">")
                if (entry.reading.isNotEmpty()) sb.append("<span class=\"gl-hint\">${entry.reading}</span>")
                if (entry.freqScore > 0) sb.append(" <span style=\"color:#606060;\">${starsString(entry.freqScore)}</span>")
                sb.append("</div>")
            }
            entry.meaning.split("\n").filter { it.isNotBlank() }.forEach { line ->
                sb.append("<div class=\"gl-secondary\" style=\"margin-left:10px;\">$line</div>")
            }
            sb.append("</div>")
        }
        return sb.toString()
    }

    companion object {
        const val RESULT_ANKI_ADDED = "anki_added"

        private const val ARG_ORIGINAL              = "original"
        private const val ARG_TRANSLATION           = "translation"
        private const val ARG_WORDS                 = "words"
        private const val ARG_READINGS              = "readings"
        private const val ARG_MEANINGS              = "meanings"
        private const val ARG_FREQ_SCORES           = "freq_scores"
        private const val ARG_SCREENSHOT_PATH = "screenshot_path"
        const val TAG = "AnkiReviewBottomSheet"

        fun newInstance(
            original: String,
            translation: String,
            wordResults: Map<String, Triple<String, String, Int>>,
            screenshotPath: String?
        ): AnkiReviewBottomSheet {
            return AnkiReviewBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ORIGINAL, original)
                    putString(ARG_TRANSLATION, translation)
                    putStringArray(ARG_WORDS,    wordResults.keys.toTypedArray())
                    putStringArray(ARG_READINGS, wordResults.values.map { it.first }.toTypedArray())
                    putStringArray(ARG_MEANINGS, wordResults.values.map { it.second }.toTypedArray())
                    putIntArray(ARG_FREQ_SCORES, wordResults.values.map { it.third }.toIntArray())
                    if (screenshotPath != null) putString(ARG_SCREENSHOT_PATH, screenshotPath)
                }
            }
        }
    }
}
