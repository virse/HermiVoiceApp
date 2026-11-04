package de.hermi.voice.update

import java.io.File
import java.security.MessageDigest

/** SHA-256-Prüfung der heruntergeladenen APK. Blockierend — im Hintergrund aufrufen. */
object Sha256 {

    fun of(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /** true, wenn kein Hash hinterlegt ist oder der Hash übereinstimmt. */
    fun verify(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        val actual = of(file) ?: return false
        return actual.equals(expected.trim(), ignoreCase = true)
    }
}
