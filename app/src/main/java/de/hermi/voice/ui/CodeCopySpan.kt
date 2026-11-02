package de.hermi.voice.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import de.hermi.voice.R
import android.widget.Toast

/**
 * Klickbarer Bereich über einem Code-Block: Ein Tap kopiert den Code in die
 * Zwischenablage und zeigt eine kurze Bestätigung.
 *
 * Erbt von ClickableSpan, damit die vorhandene LinkArrowKeyMovementMethod den
 * Tap genauso auslöst wie bei Links — es ist kein Layout-/MovementMethod-Umbau
 * nötig, und der Hinweis „⧉ Tap zum Kopieren" am Block-Ende ist nicht Teil des
 * kopierten Inhalts.
 */
class CodeCopySpan(private val code: String) : ClickableSpan() {

    override fun onClick(widget: View) {
        val context = widget.context
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Code", code))
        Toast.makeText(context, R.string.code_copied, Toast.LENGTH_SHORT).show()
    }

    override fun updateDrawState(ds: TextPaint) {
        // Kein Link-Styling: weder Unterstreichung noch Linkfarbe.
        ds.isUnderlineText = false
    }
}
