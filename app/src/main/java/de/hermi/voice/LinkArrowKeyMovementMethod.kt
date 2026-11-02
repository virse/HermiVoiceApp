package de.hermi.voice

import android.text.Layout
import android.text.Selection
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.widget.TextView

/**
 * Movement-Methode, die beides kann: Textauswahl (Kopieren per Long-Press)
 * UND Klick auf Links.
 *
 * Basis ist LinkMovementMethod — ein Untertyp von ScrollingMovementMethod,
 * der Links klickbar macht und den String-Bau der Link-Spans übernimmt.
 * Damit die Textauswahl aktiviert wird, muss canSelectArbitrarily() true
 * liefern (LinkMovementMethod selbst gibt false zurück, was die Auswahl
 * deaktiviert). Aufrufreihenfolge: setTextIsSelectable(true) zuerst, dann
 * setMovementMethod(getInstance()).
 */
class LinkArrowKeyMovementMethod : LinkMovementMethod() {

    /** Selektion (Long-Press-Kopieren) auf einem TextView ausdruecklich erlauben. */
    override fun canSelectArbitrarily(): Boolean = true

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val action = event.action
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
            var x = event.x.toInt()
            var y = event.y.toInt()
            x -= widget.totalPaddingLeft
            y -= widget.totalPaddingTop
            x += widget.scrollX
            y += widget.scrollY
            val layout: Layout = widget.layout
            val line = layout.getLineForVertical(y)
            val off = layout.getOffsetForHorizontal(line, x.toFloat())
            val links = buffer.getSpans(off, off, ClickableSpan::class.java)
            if (links.isNotEmpty()) {
                if (action == MotionEvent.ACTION_UP) {
                    links[0].onClick(widget)
                } else if (action == MotionEvent.ACTION_DOWN) {
                    Selection.setSelection(
                        buffer,
                        buffer.getSpanStart(links[0]),
                        buffer.getSpanEnd(links[0])
                    )
                }
                return true
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }

    companion object {
        private var instance: LinkArrowKeyMovementMethod? = null

        @JvmStatic
        fun getInstance(): LinkArrowKeyMovementMethod {
            if (instance == null) instance = LinkArrowKeyMovementMethod()
            return instance!!
        }
    }
}