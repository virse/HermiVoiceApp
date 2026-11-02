package de.hermi.voice

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Teile-Schleuse („Senden mit / Teilen mit“). Nimmt den ACTION_SEND-Intent
 * (text/plain) aus dem Android-Share-Sheet entgegen, extrahiert den geteilten
 * Text und reicht ihn als Extra an die MainActivity weiter. Diese eigentliche
 * Schleuse ist die EINZIGE exportierte Activity des Chats; die MainActivity
 * selbst bleibt exported=false. Das Theme ist transparent + excludeFromRecents,
 * damit der User beim Teilen kein Blinken sieht und die Mini-Activity nicht in
 * der App-Übersicht auftaucht.
 */
class ShareActivity : AppCompatActivity() {

    companion object {
        // Schlüssel für den weitergeleiteten Text-Intent an MainActivity.
        const val EXTRA_SHARED_TEXT = "de.hermi.voice.extra.SHARED_TEXT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent()
    }

    /** Holt den geteilten Text, leitet ihn an MainActivity weiter und beendet
     *  sich. Damit die bereits laufende MainActivity wiederverwendet statt neu
     *  erzeugt wird (singleTop), wird FLAG_ACTIVITY_CLEAR_TOP | SINGLE_TOP
     *  gesetzt - dort treffen onNewIntent/onCreate den passenden Empfangszweig. */
    private fun handleShareIntent() {
        val i = intent
        // ACTION_SEND mit EXTRA_TEXT: der Standardweg für geteilten Text.
        val sharedText = if (i.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        } else {
            ""
        }

        // Kein (nutzbarer) Text: trotzdem zur MainActivity, dort wird der leere
        // Zusatz ignoriert. So landet der Nutzer nie in einer leeren Schleuse.
        val target = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_SHARED_TEXT, sharedText)
        }
        startActivity(target)
        finish()
    }
}