package de.hermi.voice.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Schlüsselspeicher: AES-256-GCM mit einem Keystore-Key.
 *
 * Der kryptographische Schlüssel wird EINMALIG erzeugt und lebt ausschließlich im
 * Android-Keystore (hardware-gebunden, wo verfügbar) — er verlässt das Gerät nie und
 * wird vom System NIE mitgesichert. Verschlüsselte Daten (API-Key, Session-Historie)
 * sind damit nur entschlüsselbar, solange dieser Keystore-Key auf demselben Gerät
 * existiert. Nach Werksreset/Neuinstallation ist der Ciphertext dauerhaft unlesbar —
 * bewusste, so gewollte Konsequenz (allowBackup=false, Historie nur lokal).
 *
 * Format pro verschlüsseltem Wert: Base64(NO_PADDING) von [12-Byte-IV || Ciphertext].
 * Jedes encrypt() erzeugt eine frische, zufällige IV — kein IV-Reuse pro Schlüssel.
 * GCM authentifiziert den Text: Manipulation oder falscher Schlüssel führen zu einer
 * Exception (AEADBadTagException), die der Aufrufer abfangen darf.
 */
object CryptoManager {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "hermi_master_key"
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Verschlüsselt Klartext → Base64(IV || Ciphertext). */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(cipherBytes, 0, out, iv.size, cipherBytes.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * Entschlüsselt Base64(IV || Ciphertext). Wirft bei zu kurzem Datenblock,
     * Manipulation (GCM-Tag) oder fehlendem/anderem Keystore-Key.
     */
    fun decrypt(encoded: String): String {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        require(data.size > IV_LENGTH) { "Ciphertext zu kurz" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, data, 0, IV_LENGTH)
        )
        val plain = cipher.doFinal(data, IV_LENGTH, data.size - IV_LENGTH)
        return String(plain, Charsets.UTF_8)
    }

    /** Heuristik: Ist dieser Wert ein (potenziell) verschlüsselter Block? */
    fun isEncryptedText(value: String): Boolean = value.isNotEmpty() && try {
        decrypt(value)
        true
    } catch (e: Exception) {
        false
    }

    // ── Transportverschlüsselung (Config-Backup) ─────────────────────────
    // Backup-Dateien müssen auf einem ANDEREN Gerät lesbar sein, auf dem der
    // Keystore-Key (hermi_master_key) nicht existiert. Deshalb wird für den
    // Export ein Schlüssel aus einer Passphrase abgeleitet (PBKDF2), und die
    // API-Bestandteile werden NUR damit transportverschlüsselt. Beim Import
    // wird mit derselben Passphrase entschlüsselt und anschließend wieder mit
    // dem lokalen Keystore-Key verschlüsselt (durch AppConfig-Setter). So
    // bleibt das Prinzip „Keys nur verschlüsselt ablegen“ auch in der
    // Backup-Datei gewahrt — der Klartext liegt nie im Export.

    // PBKDF2-Round-Count (ausgewogener Kompromiss aus Härtung und Latenz).
    private const val PBKDF2_ITERATIONS = 120_000
    private const val SALT_LENGTH = 16

    /** Leitet einen AES-256-Schlüssel aus der Passphrase + Salz ab (PBKDF2-HMAC-SHA256). */
    fun derivePassphraseKey(passphrase: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
    }

    /**
     * Transportverschlüsselung eines Klartextes mit passphrase-abgeleitetem
     * Schlüssel. Liefert Base64(IV || Ciphertext) — gleiches Format wie
     * encrypt(), aber mit externem (nicht-keystore-)Schlüssel. Jeder Aufruf
     * erzeugt eine frische IV.
     */
    fun encryptWithKey(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(cipherBytes, 0, out, iv.size, cipherBytes.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Transportentschlüsselung eines Base64(IV || Ciphertext)-Blocks. */
    fun decryptWithKey(key: SecretKey, encoded: String): String {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        require(data.size > IV_LENGTH) { "Ciphertext zu kurz" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, data, 0, IV_LENGTH)
        )
        val plain = cipher.doFinal(data, IV_LENGTH, data.size - IV_LENGTH)
        return String(plain, Charsets.UTF_8)
    }

    /** Erzeugt ein frisches, zufälliges Salz für die Passphrase-Ableitung. */
    fun newExportSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /** Kodiert ein ByteArray als Base64 (für die JSON-Ablage). */
    fun toBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Dekodiert eine Base64-Zeichenkette zu Bytes. */
    fun fromBase64(encoded: String): ByteArray = Base64.decode(encoded, Base64.NO_WRAP)
}