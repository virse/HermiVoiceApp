package de.hermi.voice.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/**
 * Markdown → Spans. Aus MainActivity herausgelöst (rein funktional, ohne
 * Activity-Zugriff). Rendert Inline-Auszeichnungen UND Block-Elemente
 * (Code-Blöcke, Tabellen, Überschriften, Listen, Blockquotes) in einen
 * einzigen SpannableStringBuilder — eine Nachricht bleibt genau ein TextView.
 *
 * Streaming-Sicherheit: Der Parser ist ein linearer Durchlauf. Nicht
 * geschlossene Marker (`` ` ``, `**`, `$`, Fences) bleiben während des
 * Streamings wörtlich stehen, damit halbe Konstrukte kurz sichtbar sein
 * können, ohne dass etwas falsch gerendert wird.
 */
object MarkdownFormatter {

    /** Hintergrund Inline-Code (Backticks, `$…$`) — dezentes Weiß-Overlay. */
    private const val CODE_BG = 0x26FFFFFF
    /** Hintergrund mehrzeiliger Code-Blöcke (größere Fläche, noch dezenter). */
    private const val BLOCK_BG = 0x14FFFFFF
    /** Sekundäre Textfarbe (Hinweis, Blockquote, Tabellen-Separator). */
    private val MUTED_COLOR = 0x99FFFFFF.toInt()

    /**
     * Wandelt Markdown in Spans um. [density] (Display-Dichte) wird für die
     * Einrückung von Listen/Blockquotes in echten dp gebraucht — die Klasse
     * selbst ist context-frei und bekommt den Wert übergeben.
     */
    fun format(text: String, density: Float = 1f, copyHint: String = "⧉ Tap zum Kopieren"): Spanned {
        val sb = SpannableStringBuilder()
        val lines = text.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // 1. Fenced Code-Block (``` oder ~~~, optionaler Sprach-Tag).
            if (isFence(trimmed)) {
                val code = mutableListOf<String>()
                var j = i + 1
                var closed = false
                // Schließende Fence: gleiches Zeichen, mindestens gleich lang, ohne
                // Info-String (sonst würde ein "```kotlin" im Code den Block beenden).
                val fenceChar = trimmed[0]
                val fenceLen = trimmed.takeWhile { it == fenceChar }.length
                while (j < lines.size) {
                    val t = lines[j].trim()
                    if (t.length >= fenceLen && t.all { it == fenceChar }) { closed = true; break }
                    code.add(lines[j])
                    j++
                }
                if (closed) {
                    appendCodeBlock(sb, code, copyHint)
                    i = j + 1
                    appendLineBreak(sb, i < lines.size)
                    continue
                }
                // Offener Fence: Zeile fällt unten in den normalen Textpfad.
            }

            // 2. Tabelle (Header-Zeile + Trennzeile, danach Datenzeilen).
            if (isTableRow(trimmed) && i + 1 < lines.size && isTableSeparator(lines[i + 1].trim())) {
                val rows = mutableListOf<String>()
                var j = i
                while (j < lines.size && isTableRow(lines[j].trim())) {
                    rows.add(lines[j].trim())
                    j++
                }
                appendTable(sb, rows, density)
                i = j
                appendLineBreak(sb, i < lines.size)
                continue
            }

            // 3. Überschrift (# … ######).
            val heading = headingLevel(trimmed)
            if (heading > 0) {
                // Abstand vor der Überschrift: genau eine Leerzeile, wenn davor Text steht.
                if (sb.isNotEmpty() && sb.endsWith("\n") && !sb.endsWith("\n\n")) sb.append("\n")
                val start = sb.length
                sb.append(formatInline(trimmed.substring(heading).trim()))
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(
                    RelativeSizeSpan(HEADING_SIZES[heading - 1]),
                    start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                i++
                appendLineBreak(sb, i < lines.size)
                continue
            }

            // 3b. Horizontale Linie (---, ***, ___ — mind. 3 gleiche Zeichen).
            if (isHorizontalRule(trimmed)) {
                val start = sb.length
                sb.append("────────────")
                sb.setSpan(ForegroundColorSpan(MUTED_COLOR), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++
                appendLineBreak(sb, i < lines.size)
                continue
            }

            // 4. Blockquote (> Text), auch verschachtelt (>> Text).
            if (trimmed.startsWith(">")) {
                var content = trimmed
                var quoteLevel = 0
                while (content.startsWith(">")) {
                    quoteLevel++
                    content = content.substring(1).trimStart()
                }
                val start = sb.length
                sb.append(formatInline(content))
                val indent = (16 * density * quoteLevel).toInt()
                sb.setSpan(LeadingMarginSpan.Standard(indent, indent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(MUTED_COLOR), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++
                appendLineBreak(sb, i < lines.size)
                continue
            }

            // 5. Liste (Einrückung = Verschachtelungsebene).
            // Tabs wie 4 Leerzeichen behandeln, sonst zählt die Einrückung nicht.
            val expanded = line.replace("\t", "    ")
            val leading = expanded.indexOfFirst { it != ' ' }.let { if (it < 0) 0 else it }
            val markerEnd = listMarker(expanded.substring(leading))
            if (markerEnd > 0) {
                val isNumbered = expanded.substring(leading)[0].isDigit()
                var content = expanded.substring(leading + markerEnd).trim()
                var marker = if (isNumbered) expanded.substring(leading, leading + markerEnd).trim() else "•"
                // Task-Listen: "- [ ] Text" / "- [x] Text".
                if (!isNumbered) {
                    if (content.startsWith("[ ] ")) { marker = "☐"; content = content.substring(4).trim() }
                    else if (content.startsWith("[x] ") || content.startsWith("[X] ")) { marker = "☑"; content = content.substring(4).trim() }
                }
                val start = sb.length
                sb.append(marker)
                sb.append(" ")
                sb.append(formatInline(content))
                // 2er- und 4er-Einrückung ergeben beide eine Ebene pro Schritt.
                val level = if (leading >= 4) leading / 4 else leading / 2
                val margin = ((24 + 18 * level) * density).toInt()
                // Hängeeinzug: umbrochene Zeilen starten bündig unter dem Text, nicht unter dem Marker.
                val hanging = ((if (isNumbered) 8 * marker.length + 6 else 14) * density).toInt()
                sb.setSpan(LeadingMarginSpan.Standard(margin, margin + hanging), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i++
                appendLineBreak(sb, i < lines.size)
                continue
            }

            // 6. Normale Zeile.
            sb.append(formatInline(line))
            i++
            appendLineBreak(sb, i < lines.size)
        }
        return sb
    }

    /** Mehrzeiliger Code-Block: Monospace + Hintergrund, Tap kopiert den Code,
     *  dezente Hinweiszeile am Block-Ende (nicht Teil des kopierten Inhalts). */
    private fun appendCodeBlock(sb: SpannableStringBuilder, codeLines: List<String>, copyHint: String) {
        val start = sb.length
        val code = codeLines.joinToString("\n")
        sb.append(code)
        sb.append("\n")
        val codeEnd = start + code.length
        sb.setSpan(CodeCopySpan(code), start, codeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(BackgroundColorSpan(BLOCK_BG), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val hintStart = sb.length
        sb.append(copyHint)
        sb.setSpan(RelativeSizeSpan(0.8f), hintStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(MUTED_COLOR), hintStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Tabelle als umbruchfähige Karten. Vorher wurde jede Tabellenzeile als
     *  EIN gepolsterter Monospace-String ausgegeben (Spalten per `padEnd`
     *  ausgerichtet). Das funktioniert nur, solange die Zeile in die Blase
     *  passt: Bei 16sp Monospace sind das in einer 450dp-Breite ~36 Zeichen.
     *  Eine dreispaltige Tabelle mit echten Inhalten kommt leicht auf 110+
     *  Zeichen — dann bricht das TextView mitten in der Zeile um und die
     *  Spaltenzuordnung ist zerstört (die Trennzeile aus `-` erscheint als
     *  Reihe von Strichzeilen). Deshalb jetzt: Kopfzeile liefert die
     *  Spaltennamen, jede Datenzeile wird zu "Spalte: Wert"-Zeilen, die
     *  beliebig umbrechen dürfen. Zellinhalte laufen durch [formatInline]
     *  (fett, Code, Links funktionieren damit auch in Zellen). */
    private fun appendTable(sb: SpannableStringBuilder, rows: List<String>, density: Float) {
        val parsed = rows.map { splitTableCells(it) }
        if (parsed.isEmpty()) return
        val header = parsed.first()
        val colCount = parsed.maxOf { it.size }
        // Zeile 1 ist die Trennzeile (|---|) — inhaltlich leer, wird übersprungen.
        val dataStart = if (parsed.size > 1 && isTableSeparator(rows[1])) 2 else 1
        val data = parsed.drop(dataStart)
        val bulletIndent = (14 * density).toInt()
        val hanging = (11 * density).toInt()

        if (data.isEmpty()) {
            // Nur Kopfzeile (z. B. abgeschnittene Streaming-Tabelle): als Text.
            if (header.any { it.isNotEmpty() }) sb.append(formatInline(header.joinToString(" — ") { it }))
            return
        }

        for ((rowIndex, cells) in data.withIndex()) {
            if (rowIndex > 0) sb.append("\n\n")
            val rowLabel = cells.firstOrNull().orEmpty()
            val rowStart = sb.length

            if (colCount <= 2) {
                // Zwei Spalten: "Label: Wert" in einer Zeile.
                if (rowLabel.isNotEmpty()) {
                    val start = sb.length
                    sb.append(formatInline(rowLabel))
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val value = cells.getOrNull(1).orEmpty()
                    if (value.isNotEmpty()) {
                        sb.append(": ")
                        sb.append(formatInline(value))
                    }
                } else {
                    val value = cells.getOrNull(1).orEmpty()
                    if (value.isNotEmpty()) sb.append(formatInline(value))
                }
                continue
            }

            // Drei und mehr Spalten: erste Zelle als Zeilentitel, die übrigen
            // als "Spalte: Wert" darunter (Spaltenname gedimmt).
            if (rowLabel.isNotEmpty()) {
                val start = sb.length
                sb.append(formatInline(rowLabel))
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            for (c in 1 until colCount) {
                val value = cells.getOrNull(c).orEmpty()
                if (value.isEmpty()) continue
                if (sb.length > rowStart) sb.append("\n")
                val lineStart = sb.length
                sb.append("• ")
                val label = header.getOrNull(c).orEmpty()
                if (label.isNotEmpty()) {
                    val labelStart = sb.length
                    sb.append(formatInline(label))
                    sb.setSpan(
                        ForegroundColorSpan(MUTED_COLOR), labelStart, sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    sb.append(": ")
                }
                sb.append(formatInline(value))
                sb.setSpan(
                    LeadingMarginSpan.Standard(bulletIndent, bulletIndent + hanging),
                    lineStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    /** Inline-Markdown in einer einzelnen Zeile (ohne Zeilenumbrüche):
     *  `**fett**`, `*kursiv*`, `` `Code` ``, `$…$`, `~~durch~~`,
     *  `[Link](url)` (nur http/https klickbar). Nicht geschlossene Marker
     *  bleiben wörtlich stehen. */
    private fun formatInline(text: String): Spanned {
        val sb = SpannableStringBuilder()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '`' || c == '$' -> {
                    val end = text.indexOf(c, i + 1)
                    // `$…$` nur bei formelartigem Inhalt (kein Leerzeichen direkt nach dem
                    // öffnenden / vor dem schließenden $, danach keine Ziffer) — sonst wird
                    // "5$ und 6$" fälschlich zu Code.
                    val mathOk = c != '$' || (end > i + 1 &&
                        !text[i + 1].isWhitespace() && !text[end - 1].isWhitespace() &&
                        (end + 1 >= n || !text[end + 1].isDigit()))
                    if (end > i + 1 && mathOk) {
                        val start = sb.length
                        sb.append(text.substring(i + 1, end))
                        sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(BackgroundColorSpan(CODE_BG), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else {
                        sb.append(c); i++
                    }
                }
                text.startsWith("***", i) -> {
                    // ***fett-kursiv***
                    val end = text.indexOf("***", i + 3)
                    if (end > i + 3) {
                        val start = sb.length
                        sb.append(formatInline(text.substring(i + 3, end)))
                        sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 3
                    } else {
                        sb.append(c); i++
                    }
                }
                text.startsWith("**", i) || (text.startsWith("__", i) && (i == 0 || !text[i - 1].isLetterOrDigit())) -> {
                    val delim = text.substring(i, i + 2)
                    val end = text.indexOf(delim, i + 2)
                    if (end > i + 2) {
                        val start = sb.length
                        sb.append(formatInline(text.substring(i + 2, end)))
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else {
                        sb.append(c); i++
                    }
                }
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i + 2) {
                        val start = sb.length
                        sb.append(formatInline(text.substring(i + 2, end)))
                        sb.setSpan(StrikethroughSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else {
                        sb.append(c); i++
                    }
                }
                (c == '*' || c == '_') && isMdOpen(text, i) -> {
                    val end = findMdClose(text, i, c)
                    if (end > i + 1) {
                        val start = sb.length
                        sb.append(formatInline(text.substring(i + 1, end)))
                        sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else {
                        sb.append(c); i++
                    }
                }
                c == '[' -> {
                    // Markdown-Link [Label](url) → klickbarer Link.
                    val close = text.indexOf(']', i + 1)
                    if (close > i + 1 && close + 1 < n && text[close + 1] == '(') {
                        // Passende schließende Klammer (URLs wie .../Foo_(Bar) enthalten Klammern).
                        var depth = 1
                        var urlEnd = -1
                        var k = close + 2
                        while (k < n) {
                            if (text[k] == '(') depth++
                            else if (text[k] == ')' && --depth == 0) { urlEnd = k; break }
                            k++
                        }
                        if (urlEnd > close + 2) {
                            val label = text.substring(i + 1, close)
                            val url = text.substring(close + 2, urlEnd)
                            val start = sb.length
                            sb.append(label)
                            // Nur http/https werden klickbar. Antworttexte stammen vom Modell
                            // (ggf. durch Webinhalte/Prompt-Injection beeinflusst); Schemata wie
                            // intent:, javascript:, file:, content: oder tel: würden beim Tippen
                            // beliebige Aktionen auslösen → dort bleibt nur das Label als Text.
                            val urlLower = url.trim().lowercase(java.util.Locale.ROOT)
                            if (urlLower.startsWith("http://") || urlLower.startsWith("https://")) {
                                sb.setSpan(URLSpan(url.trim()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            i = urlEnd + 1
                        } else {
                            sb.append(c); i++
                        }
                    } else {
                        sb.append(c); i++
                    }
                }
                text.startsWith("http://", i) || text.startsWith("https://", i) -> {
                    // Blanke URL → klickbar (nur http/https), Satzzeichen am Ende ausnehmen.
                    var end = i
                    while (end < n && !text[end].isWhitespace()) end++
                    while (end > i && text[end - 1] in ".,;:!?)\"'") end--
                    val url = text.substring(i, end)
                    val start = sb.length
                    sb.append(url)
                    sb.setSpan(URLSpan(url), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end
                }
                else -> {
                    sb.append(c); i++
                }
            }
        }
        return sb
    }

    /** Horizontale Linie: mind. 3 gleiche Zeichen aus - * _ (Leerzeichen erlaubt). */
    private fun isHorizontalRule(trimmed: String): Boolean {
        val t = trimmed.replace(" ", "")
        return t.length >= 3 && (t[0] == '-' || t[0] == '*' || t[0] == '_') && t.all { it == t[0] }
    }

    /** Öffnende bzw. schließende Fence-Zeile (``` oder ~~~). */
    private fun isFence(trimmed: String): Boolean =
        trimmed.startsWith("```") || trimmed.startsWith("~~~")

    /** Tabellen-Datenzeile: mindestens zwei |-Trenner. Die führende Pipe ist
     *  optional — Modelle lassen sie häufig weg (`A | B` statt `| A | B |`),
     *  vorher fiel so eine Tabelle komplett in den Fließtext-Pfad. */
    private fun isTableRow(trimmed: String): Boolean =
        trimmed.count { it == '|' } >= 2

    /** Trennzeile: nur -, :, | und Leerzeichen zwischen den Pipes; mindestens
     *  ein |-Trenner, damit eine bloße Linie (`---`) nicht als Tabelle gilt. */
    private fun isTableSeparator(trimmed: String): Boolean {
        if (!trimmed.contains('|')) return false
        var t = trimmed
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        return t.contains('-') && t.all { it == '-' || it == ':' || it == ' ' || it == '|' }
    }

    /** Zellen einer Tabellenzeile splitten, dabei `\|` als Literal-Pipe. */
    private fun splitTableCells(row: String): List<String> {
        var t = row.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length && t[i + 1] == '|') {
                cur.append('|'); i += 2; continue
            }
            if (c == '|') {
                cells.add(cur.toString().trim())
                cur.clear()
            } else {
                cur.append(c)
            }
            i++
        }
        cells.add(cur.toString().trim())
        return cells
    }

    /** Überschriftsebene 1–6 oder 0, wenn keine Überschrift (Folgezeichen muss
     *  Leerzeichen/Tab sein, damit "#hashtag" kein Titel wird). */
    /** Größen H1–H6: deutlich gestaffelt (vorher kaum unterscheidbar). */
    private val HEADING_SIZES = floatArrayOf(1.45f, 1.3f, 1.15f, 1.05f, 1.0f, 0.95f)

    private fun headingLevel(trimmed: String): Int {
        var level = 0
        while (level < trimmed.length && level < 6 && trimmed[level] == '#') level++
        if (level == 0 || level >= trimmed.length) return 0
        val next = trimmed[level]
        if (next != ' ' && next != '\t') return 0
        return level
    }

    /** Listenmarker am Zeilenanfang: - * + oder Nummer mit . / ). Liefert den
     *  Index direkt nach dem Marker (inkl. Leerzeichen) oder 0. */
    private fun listMarker(s: String): Int {
        if (s.isEmpty()) return 0
        val c0 = s[0]
        if (c0 == '-' || c0 == '*' || c0 == '+') {
            if (s.length >= 2 && s[1] == ' ') return 2
            return 0
        }
        if (c0.isDigit()) {
            var j = 0
            while (j < s.length && s[j].isDigit()) j++
            if (j < s.length && (s[j] == '.' || s[j] == ')') && j + 1 < s.length && s[j + 1] == ' ') {
                return j + 2
            }
        }
        return 0
    }

    /** Öffnendes * bzw. _ (nicht direkt hinter einem Zeichen, gefolgt von Text). */
    private fun isMdOpen(text: String, i: Int): Boolean =
        (i == 0 || !text[i - 1].isLetterOrDigit()) &&
            i + 1 < text.length && text[i + 1] != ' ' && text[i + 1] != '\n'

    /** Schließendes * bzw. _ (davor kein Leerzeichen, danach Ende/Satzzeichen). */
    private fun findMdClose(text: String, i: Int, c: Char): Int {
        var j = i + 1
        while (j < text.length) {
            if (text[j] == c && text[j - 1] != ' ' && (j + 1 >= text.length || !text[j + 1].isLetterOrDigit())) {
                return j
            }
            j++
        }
        return -1
    }

    private fun appendLineBreak(sb: SpannableStringBuilder, hasMore: Boolean) {
        if (hasMore) sb.append("\n")
    }
}
