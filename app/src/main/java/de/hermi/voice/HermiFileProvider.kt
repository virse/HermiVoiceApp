package de.hermi.voice

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * Eigener FileProvider, der Markdown-Dateien (.md) als text/plain meldet.
 *
 * Die Standard-Implementierung liefert für die Endung ".md" den MIME-Type
 * "text/markdown". Das ist problematisch: die meisten Ziel-Apps beim Teilen
 * (Mail, Notizen, Chat) registrieren nur "text/plain" und betrachten die Datei
 * sonst als nicht zuständig — der Teilen-Dialog filtert sie heraus bzw. der
 * Empfänger kann die Datei nicht öffnen. Zudem hängt das System-Ranking des
 * Share-Sheets an einem seltenen MIME-Type und läuft bis zum Timeout.
 *
 * Daher wird "text/markdown" nach "text/plain" normalisiert. Der Inhalt der
 * Datei bleibt unverändert Markdown.
 */
class HermiFileProvider : FileProvider() {

    override fun getType(uri: Uri): String? {
        val type = super.getType(uri)
        return if (type == "text/markdown") "text/plain" else type
    }
}