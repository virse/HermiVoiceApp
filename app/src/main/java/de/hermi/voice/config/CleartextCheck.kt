package de.hermi.voice.config

import java.net.URI
import java.util.Locale

/**
 * Prüft, ob eine Server-Adresse unverschlüsselt (http://) über ein NICHT lokales
 * Netz erreicht würde. Der Bearer-Key und der gesamte Chat liefen dann im Klartext
 * übers Internet. Im LAN/VPN ist http bewusst erlaubt (kein Hartverbot, siehe
 * Manifest usesCleartextTraffic) — die App warnt deshalb nur.
 *
 * Eine `network_security_config` kann keine IP-Bereiche (CIDR) beschreiben und
 * würde damit beliebige LAN-Adressen/Hostnamen aussperren; deshalb die Laufzeit-Prüfung.
 */
object CleartextCheck {

    /** true, wenn [url] mit "http://" beginnt und der Host nicht eindeutig lokal ist. */
    fun isRisky(url: String): Boolean {
        val u = url.trim()
        if (!u.lowercase(Locale.ROOT).startsWith("http://")) return false
        val host = try {
            URI(u).host
        } catch (_: Exception) {
            null
        }?.lowercase(Locale.ROOT)?.trim('[', ']') ?: return false
        return !isLocalHost(host)
    }

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host.endsWith(".local") || host.endsWith(".lan") ||
            host.endsWith(".home.arpa") || host.endsWith(".internal")
        ) return true
        // Einzelner Hostname ohne Punkt (z. B. "hermes", "nas") → lokaler Name.
        if (!host.contains('.') && !host.contains(':')) return true
        // IPv6: Loopback, Unique-Local (fc00::/7), Link-Local (fe80::/10).
        if (host.contains(':')) {
            return host == "::1" || host.startsWith("fc") || host.startsWith("fd") ||
                host.startsWith("fe80")
        }
        // IPv4 privat/Loopback/Link-Local.
        val p = host.split('.')
        if (p.size == 4 && p.all { it.toIntOrNull() in 0..255 }) {
            val a = p[0].toInt()
            val b = p[1].toInt()
            return a == 10 || a == 127 ||
                (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127) // CGNAT/Tailscale
        }
        return false
    }
}
