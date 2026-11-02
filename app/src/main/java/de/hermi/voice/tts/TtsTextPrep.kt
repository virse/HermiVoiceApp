package de.hermi.voice.tts

/**
 * Bereitet Antworttext für die Sprachausgabe auf (B7) und teilt ihn in Stücke, die
 * die TTS-Engine verarbeiten kann (B8).
 *
 * Hintergrund: Antworten enthalten Markdown (Überschriften, Fett, Listen, Links,
 * Code). Roh vorgelesen würden Zeichen wie „Sternchen“ oder „Raute“ gesprochen und
 * URLs/Code buchstabiert. Außerdem lehnt `TextToSpeech.speak` Texte ab, die länger
 * als `getMaxSpeechInputLength()` (i. d. R. 4000 Zeichen) sind — lange Antworten
 * würden dann gar nicht vorgelesen.
 */
object TtsTextPrep {

    private val CODE_BLOCK = Regex("```[\\s\\S]*?(```|$)")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val BARE_URL = Regex("https?://\\S+")
    private val HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val QUOTE = Regex("(?m)^\\s*>+\\s?")
    private val BULLET = Regex("(?m)^\\s*([-*+•]|\\d+[.)])\\s+")
    private val HRULE = Regex("(?m)^\\s*([-*_]\\s*){3,}$")
    private val EMPHASIS = Regex("(\\*\\*|__|\\*|~~)")
    private val MULTI_BLANK = Regex("\\n{3,}")

    /** Entfernt Markdown-Syntax; Codeblöcke entfallen (nicht sinnvoll vorlesbar). */
    fun clean(text: String): String {
        var t = text
        t = CODE_BLOCK.replace(t, " ")
        t = IMAGE.replace(t) { it.groupValues[1] }
        t = LINK.replace(t) { it.groupValues[1] }
        t = BARE_URL.replace(t, "")
        t = INLINE_CODE.replace(t) { it.groupValues[1] }
        t = HRULE.replace(t, "")
        t = HEADING.replace(t, "")
        t = QUOTE.replace(t, "")
        t = BULLET.replace(t, "")
        t = EMPHASIS.replace(t, "")
        t = MULTI_BLANK.replace(t, "\n\n")
        return t.trim()
    }

    /**
     * Teilt [text] in Stücke von höchstens [maxLen] Zeichen, bevorzugt an Absatz-,
     * dann Satz-, dann Wortgrenzen (nie mitten im Wort, außer ein Wort ist selbst
     * länger als [maxLen]).
     */
    fun split(text: String, maxLen: Int): List<String> {
        val limit = maxLen.coerceAtLeast(100)
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > limit) {
            val window = rest.substring(0, limit)
            var cut = window.lastIndexOf("\n\n")
            if (cut < limit / 2) cut = lastSentenceEnd(window)
            if (cut < limit / 2) cut = window.lastIndexOf(' ')
            if (cut <= 0) cut = limit
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out.filter { it.isNotBlank() }
    }

    private fun lastSentenceEnd(s: String): Int {
        var best = -1
        for (m in arrayOf(". ", "! ", "? ", ".\n", "!\n", "?\n", ":\n")) {
            best = maxOf(best, s.lastIndexOf(m))
        }
        return if (best >= 0) best + 1 else -1
    }
}
