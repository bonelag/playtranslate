package com.gamelens.ui

import android.content.DialogInterface
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
import com.gamelens.dictionary.Deinflector
import com.gamelens.fullScreenDialogTheme
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WordAnkiReviewSheet : DialogFragment() {

    private data class WordEntry(val word: String, val reading: String, val meaning: String, val freqScore: Int = 0)

    private var includePhoto = true
    private var deckEntries: List<Map.Entry<Long, String>> = emptyList()
    private var isSentenceMode = false
    private val sentenceWords = mutableListOf<WordEntry>()

    /** Optional listener called when this sheet is dismissed (used by WordAnkiReviewActivity). */
    var onDismissListener: DialogInterface.OnDismissListener? = null

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.onDismiss(dialog)
    }

    override fun getTheme(): Int = fullScreenDialogTheme(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_word_anki_review, container, false)

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.AnimSlideRight)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.btnBackWordAnki).setOnClickListener { dismiss() }

        val args           = arguments ?: return
        val word           = args.getString(ARG_WORD) ?: return
        val reading        = args.getString(ARG_READING) ?: ""
        val pos            = args.getString(ARG_POS) ?: ""
        val definition     = args.getString(ARG_DEFINITION) ?: ""
        val screenshotPath = args.getString(ARG_SCREENSHOT_PATH)

        // Sentence data (optional)
        val sentenceOriginal    = args.getString(ARG_SENTENCE_ORIGINAL)
        val sentenceTranslation = args.getString(ARG_SENTENCE_TRANSLATION) ?: ""
        val sentenceWordArr     = args.getStringArray(ARG_SENTENCE_WORDS)
        val sentenceReadingArr  = args.getStringArray(ARG_SENTENCE_READINGS)
        val sentenceMeaningArr  = args.getStringArray(ARG_SENTENCE_MEANINGS)
        val sentenceFreqArr     = args.getIntArray(ARG_SENTENCE_FREQ_SCORES)
        val hasSentenceData     = sentenceOriginal != null

        if (hasSentenceData && sentenceWordArr != null) {
            sentenceWordArr.forEachIndexed { i, w ->
                sentenceWords.add(
                    WordEntry(
                        w,
                        sentenceReadingArr?.getOrElse(i) { "" } ?: "",
                        sentenceMeaningArr?.getOrElse(i) { "" } ?: "",
                        sentenceFreqArr?.getOrElse(i) { 0 } ?: 0
                    )
                )
            }
        }

        val spinnerDeck    = view.findViewById<Spinner>(R.id.spinnerWordAnkiDeck)
        val toggleGroup    = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleCardType)
        val containerSentence = view.findViewById<LinearLayout>(R.id.containerSentence)
        val containerWord     = view.findViewById<LinearLayout>(R.id.containerWord)

        // Word fields
        val tvHeadword     = view.findViewById<TextView>(R.id.tvWordAnkiHeadword)
        val tvReading      = view.findViewById<TextView>(R.id.tvWordAnkiReading)
        val tvPos          = view.findViewById<TextView>(R.id.tvWordAnkiPos)
        val etDefinition   = view.findViewById<EditText>(R.id.etWordAnkiDefinition)

        // Sentence fields
        val etSentenceJapanese    = view.findViewById<EditText>(R.id.etSentenceJapanese)
        val etSentenceTranslation = view.findViewById<EditText>(R.id.etSentenceTranslation)
        val defsContainer         = view.findViewById<LinearLayout>(R.id.sentenceDefinitionsContainer)

        // Shared photo
        val tvPhotoLabel   = view.findViewById<TextView>(R.id.tvWordAnkiPhotoLabel)
        val layoutPhoto    = view.findViewById<FrameLayout>(R.id.layoutWordAnkiPhoto)
        val ivPhoto        = view.findViewById<ImageView>(R.id.ivWordAnkiPhoto)
        val btnRemovePhoto = view.findViewById<Button>(R.id.btnWordAnkiRemovePhoto)
        val btnSend        = view.findViewById<Button>(R.id.btnWordAnkiSend)

        // Populate word fields
        tvHeadword.text = word
        if (reading.isNotEmpty()) {
            tvReading.text = reading
            tvReading.visibility = View.VISIBLE
        }
        if (pos.isNotEmpty()) {
            tvPos.text = pos
            tvPos.visibility = View.VISIBLE
        }
        etDefinition.setText(definition)

        // Populate sentence fields
        if (hasSentenceData) {
            etSentenceJapanese.setText(sentenceOriginal)
            etSentenceTranslation.setText(sentenceTranslation)
            rebuildWordRows(defsContainer)
        }

        // Photo setup
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

        // Toggle setup
        if (hasSentenceData) {
            toggleGroup.visibility = View.VISIBLE
            toggleGroup.check(R.id.btnModeSentence)
            isSentenceMode = true
            containerSentence.visibility = View.VISIBLE
            containerWord.visibility = View.GONE

            toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                isSentenceMode = checkedId == R.id.btnModeSentence
                containerSentence.visibility = if (isSentenceMode) View.VISIBLE else View.GONE
                containerWord.visibility = if (isSentenceMode) View.GONE else View.VISIBLE
            }
        } else {
            // No sentence data — word mode only, toggle hidden
            containerWord.visibility = View.VISIBLE
        }

        loadDecks(spinnerDeck)

        btnSend.setOnClickListener {
            val deckId = deckEntries.getOrNull(spinnerDeck.selectedItemPosition)?.key
                ?: Prefs(requireContext()).ankiDeckId
            if (deckId < 0L) {
                Toast.makeText(requireContext(), getString(R.string.anki_no_deck_selected), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSend.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                if (isSentenceMode) {
                    val japanese = etSentenceJapanese.text.toString()
                    val english  = etSentenceTranslation.text.toString()
                    sendSentenceToAnki(japanese, english, deckId,
                        if (includePhoto) screenshotPath else null)
                } else {
                    val defText = etDefinition.text.toString()
                    sendWordToAnki(word, reading, pos, defText, deckId,
                        if (includePhoto) screenshotPath else null)
                }
                btnSend.isEnabled = true
            }
        }
    }

    // ── Word rows for sentence mode ─────────────────────────────────────────

    private fun rebuildWordRows(container: LinearLayout) {
        container.removeAllViews()
        sentenceWords.forEachIndexed { index, entry ->
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
                text = "\u2715"
                textSize = 14f
                setBackgroundResource(android.R.color.transparent)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    sentenceWords.removeAt(index)
                    rebuildWordRows(container)
                }
            }

            row.addView(textBlock)
            row.addView(btnRemove)
            container.addView(row)
        }
    }

    // ── Deck loading ─────────────────────────────────────────────────────────

    private fun loadDecks(spinner: Spinner) {
        loadAnkiDecksInto(spinner) { entries -> deckEntries = entries }
    }

    // ── Send: word mode ──────────────────────────────────────────────────────

    private suspend fun sendWordToAnki(
        word: String, reading: String, pos: String, definition: String,
        deckId: Long, screenshotPath: String?
    ) {
        val ankiManager = AnkiManager(requireContext())

        val imageFilename: String? = if (screenshotPath != null) {
            withContext(Dispatchers.IO) { ankiManager.addMediaFromFile(File(screenshotPath)) }
        } else null

        val front = buildWordFrontHtml(word)
        val back  = buildWordBackHtml(word, reading, pos, definition, imageFilename)

        val success = withContext(Dispatchers.IO) { ankiManager.addNote(deckId, front, back) }
        val msg = if (success) getString(R.string.anki_added) else getString(R.string.anki_failed)
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        if (success) dismiss()
    }

    private fun buildWordFrontHtml(word: String): String = buildString {
        append("<style>")
        append("body{margin:0;padding:0;}")
        append("</style>")
        append("<div class=\"gl-front\" style=\"text-align:center;font-size:2.2em;padding:32px 16px;\">$word</div>")
    }

    private fun buildWordBackHtml(
        word: String, reading: String, pos: String,
        definition: String, imageFilename: String?
    ): String = buildString {
        append("<style>")
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
        append("<div style=\"text-align:center;font-size:1.8em;padding:12px 4px;\">$word</div>")
        if (reading.isNotEmpty()) {
            append("<div style=\"text-align:center;font-size:1.1em;color:#888;\">$reading</div>")
        }
        if (pos.isNotEmpty()) {
            append("<div style=\"text-align:center;font-size:0.85em;color:#888;margin-bottom:12px;\">$pos</div>")
        }
        append("<hr>")
        val defHtml = definition.lines().filter { it.isNotBlank() }
            .joinToString("<br>") { it.trimStart() }
        append("<div style=\"font-size:1.1em;margin:12px 4px;\">$defHtml</div>")
        append("</div>")
    }

    // ── Send: sentence mode ──────────────────────────────────────────────────

    private suspend fun sendSentenceToAnki(
        japanese: String, english: String,
        deckId: Long, screenshotPath: String?
    ) {
        val ankiManager = AnkiManager(requireContext())

        val imageFilename: String? = if (screenshotPath != null) {
            withContext(Dispatchers.IO) { ankiManager.addMediaFromFile(File(screenshotPath)) }
        } else null

        val front = buildSentenceFrontHtml(japanese)
        val back  = buildSentenceBackHtml(japanese, english, imageFilename)

        val success = withContext(Dispatchers.IO) { ankiManager.addNote(deckId, front, back) }
        val msg = if (success) getString(R.string.anki_added) else getString(R.string.anki_failed)
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

        if (success) {
            parentFragmentManager.setFragmentResult(AnkiReviewBottomSheet.RESULT_ANKI_ADDED, bundleOf())
            dismiss()
        }
    }

    private fun buildSentenceFrontHtml(japanese: String): String {
        val clean = japanese.replace(Regex("[\\n\\r]+"), " ").trim()
        val annotated = buildAnnotatedFront(clean)
        return buildString {
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
            append("r.addEventListener('touchend',function(e){showTip(r,e);});")
            append("r.addEventListener('click',function(e){showTip(r,e);});")
            append("});")
            append("document.addEventListener('touchend',function(e){if(activeR&&!activeR.contains(e.target))hide();});")
            append("document.addEventListener('click',function(e){if(activeR&&!activeR.contains(e.target))hide();});")
            append("})()</script>")
        }
    }

    private fun buildAnnotatedFront(text: String): String {
        val wordMap = sentenceWords.associate { it.word to it.reading }
        return annotateText(text, wordMap, newlineAsBr = false)
    }

    private fun buildSentenceBackHtml(japanese: String, english: String, imageFilename: String?): String {
        val furigana  = buildFuriganaHtml(japanese)
        val wordsHtml = buildWordsHtml()
        return buildString {
            append("<style>")
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
        val wordMap = sentenceWords.associate { it.word to it.reading }
        return annotateText(text, wordMap, newlineAsBr = true)
    }

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
            val direct = sortedWords.firstOrNull { (word, _) -> text.startsWith(word, i) }
            if (direct != null) {
                val (w, r) = direct
                val hasKanji = w.any { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' }
                if (hasKanji && r.isNotEmpty() && r != w) {
                    sb.append("<ruby>$w<rt>$r</rt></ruby>")
                } else {
                    sb.append(w)
                }
                i += w.length
                continue
            }
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
                        val r = matchedEntry.value
                        val hasKanji = sub.any { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' }
                        if (hasKanji && r.isNotEmpty() && r != sub) {
                            sb.append("<ruby>$sub<rt>$r</rt></ruby>")
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

    private fun starsString(score: Int) = "\u2605".repeat(score)

    private fun buildWordsHtml(): String {
        if (sentenceWords.isEmpty()) return ""
        val sb = StringBuilder()
        sentenceWords.forEach { entry ->
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
        const val TAG = "WordAnkiReviewSheet"
        private const val ARG_WORD            = "word"
        private const val ARG_READING         = "reading"
        private const val ARG_POS             = "pos"
        private const val ARG_DEFINITION      = "definition"
        private const val ARG_SCREENSHOT_PATH = "screenshot_path"
        private const val ARG_SENTENCE_ORIGINAL     = "sentence_original"
        private const val ARG_SENTENCE_TRANSLATION  = "sentence_translation"
        private const val ARG_SENTENCE_WORDS        = "sentence_words"
        private const val ARG_SENTENCE_READINGS     = "sentence_readings"
        private const val ARG_SENTENCE_MEANINGS     = "sentence_meanings"
        private const val ARG_SENTENCE_FREQ_SCORES  = "sentence_freq_scores"

        fun newInstance(
            word: String,
            reading: String,
            pos: String,
            definition: String,
            screenshotPath: String?,
            sentenceOriginal: String? = null,
            sentenceTranslation: String? = null,
            sentenceWordResults: Map<String, Triple<String, String, Int>>? = null
        ) = WordAnkiReviewSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_WORD, word)
                putString(ARG_READING, reading)
                putString(ARG_POS, pos)
                putString(ARG_DEFINITION, definition)
                if (screenshotPath != null) putString(ARG_SCREENSHOT_PATH, screenshotPath)
                if (sentenceOriginal != null) {
                    putString(ARG_SENTENCE_ORIGINAL, sentenceOriginal)
                    putString(ARG_SENTENCE_TRANSLATION, sentenceTranslation ?: "")
                    if (sentenceWordResults != null) {
                        putStringArray(ARG_SENTENCE_WORDS, sentenceWordResults.keys.toTypedArray())
                        putStringArray(ARG_SENTENCE_READINGS, sentenceWordResults.values.map { it.first }.toTypedArray())
                        putStringArray(ARG_SENTENCE_MEANINGS, sentenceWordResults.values.map { it.second }.toTypedArray())
                        putIntArray(ARG_SENTENCE_FREQ_SCORES, sentenceWordResults.values.map { it.third }.toIntArray())
                    }
                }
            }
        }
    }
}
