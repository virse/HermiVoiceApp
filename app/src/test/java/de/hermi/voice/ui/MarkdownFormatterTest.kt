package de.hermi.voice.ui

import android.graphics.Typeface
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression für das Tabellen-Rendering. Vorher wurde jede Tabellenzeile als
 * gepolsterter Monospace-String ausgegeben (~110 Zeichen bei 3 Spalten). Das
 * TextView bricht bei ~36 Zeichen um, dadurch zerfiel die Tabelle in Zeilen
 * ohne Spaltenzuordnung, und die Trennzeile erschien als Wand aus Strichen.
 * Jetzt: pro Datenzeile ein Titel + "Spalte: Wert"-Zeilen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownFormatterTest {

    /** Original-Antwort, an der der Fehler aufgefallen ist (pipe-lose Tabelle). */
    private val pipeLessTable = """
        Kriterium           | **Lokal (Hermes Default)**                 | **Extern (Groq/OpenAI)**
        ------------------- | ------------------------------------------ | ----------------------------------------------
        **Privatsphäre**    | ✅ 100 % lokal, keine Daten verlassen Gerät | ❌ Audio geht an API
        **Kosten**          | ✅ Kostenlos (nur HW)                       | ⚠️ OpenAI ~$0,006/min; Groq gratis-Tier
    """.trimIndent()

    @Test
    fun `pipe-lose Tabelle wird erkannt und als Karten gerendert`() {
        val out = MarkdownFormatter.format(pipeLessTable, 2f, "copy").toString()

        assertFalse("keine sichtbaren Pipes", out.contains("|"))
        assertFalse("keine Sternchen-Marker", out.contains("*"))
        assertFalse("keine Strichzeile der Trennzeile", out.contains("---"))

        // Kopfzelle als Zeilentitel, Spaltenname aus der Kopfzeile als Label.
        assertTrue(out.contains("Privatsphäre"))
        assertTrue(out.contains("Lokal (Hermes Default): ✅ 100 % lokal, keine Daten verlassen Gerät"))
        assertTrue(out.contains("Extern (Groq/OpenAI): ❌ Audio geht an API"))
        assertTrue(out.contains("Kosten"))
        assertTrue(out.contains("OpenAI ~\$0,006/min"))

        // Keine Zeile darf breiter als das Quell-Limit werden (kein padEnd mehr).
        val longest = out.split("\n").maxOf { it.length }
        assertTrue("längste Zeile war $longest Zeichen: ${out.split("\n").maxByOrNull { it.length }}", longest < 80)
    }

    @Test
    fun `Tabelle mit fuehrenden Pipes wird ebenso als Karten gerendert`() {
        val md = """
            | a | b | c |
            |---|---|---|
            | 1 | 2 | 3 |
        """.trimIndent()
        val out = MarkdownFormatter.format(md, 2f, "copy").toString()
        assertFalse(out.contains("|"))
        assertTrue(out.contains("1"))
        assertTrue(out.contains("b: 2"))
        assertTrue(out.contains("c: 3"))
    }

    @Test
    fun `Zwei-Spalten-Tabelle wird zu Label-Wert-Zeilen`() {
        val md = """
            | Name | Wert |
            |---|---|
            | Temperatur | 21 °C |
        """.trimIndent()
        val out = MarkdownFormatter.format(md, 2f, "copy").toString()
        assertEquals("Temperatur: 21 °C", out.trim())
    }

    @Test
    fun `Zeilentitel und Labels werden ausgezeichnet`() {
        val sp = MarkdownFormatter.format(pipeLessTable, 2f, "copy")
        val text = sp.toString()

        val titleStart = text.indexOf("Privatsphäre")
        assertTrue(
            "Zeilentitel fett",
            sp.getSpans(titleStart, titleStart + 1, StyleSpan::class.java)
                .any { it.style == Typeface.BOLD }
        )
        val bulletStart = text.indexOf("• Lokal")
        assertTrue(
            "eingerückte Spaltenzeile",
            sp.getSpans(bulletStart, bulletStart + 1, LeadingMarginSpan::class.java).isNotEmpty()
        )
    }

    @Test
    fun `reine Textzeile mit einem Pipe bleibt Text`() {
        val out = MarkdownFormatter.format("Der Wert | Bedeutung ist unklar.", 2f, "copy").toString()
        assertTrue(out.contains("Der Wert | Bedeutung ist unklar."))
    }

    @Test
    fun `Inline-Formate ausserhalb von Tabellen unveraendert`() {
        val out = MarkdownFormatter.format("**fett** und *kursiv* und `code`", 2f, "copy").toString()
        assertEquals("fett und kursiv und code", out)
    }
}
