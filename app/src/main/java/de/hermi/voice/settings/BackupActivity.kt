package de.hermi.voice.settings

import android.app.AlertDialog
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import de.hermi.voice.R
import de.hermi.voice.config.ConfigBackup
import java.io.File

/**
 * Backup: verschlüsselte Konfiguration exportieren/importieren.
 */
class BackupActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_backup
    override val titleRes: Int = R.string.backup_section_title

    private val configBackupImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            Toast.makeText(this, R.string.backup_import_empty, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (content.isNullOrBlank()) {
                Toast.makeText(this, R.string.backup_import_invalid, Toast.LENGTH_LONG).show()
            } else {
                askImportPassphrase(content)
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.backup_import_invalid, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<Button>(R.id.backupExportButton).setOnClickListener { backUpExport() }
        findViewById<Button>(R.id.backupImportButton).setOnClickListener { backUpImport() }
    }

    private fun backUpExport() {
        val view = layoutInflater.inflate(R.layout.dialog_passphrase, null)
        val passInput = view.findViewById<EditText>(R.id.passphraseInput)
        val confirmInput = view.findViewById<EditText>(R.id.passphraseConfirmInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.backup_export_passphrase_title)
            .setMessage(R.string.backup_export_passphrase_hint)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.backup_export, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val pass = passInput.text.toString()
                        val confirm = confirmInput.text.toString()
                        when {
                            pass.isEmpty() -> Toast.makeText(
                                this@BackupActivity, R.string.backup_passphrase_required, Toast.LENGTH_SHORT
                            ).show()
                            pass != confirm -> Toast.makeText(
                                this@BackupActivity, R.string.backup_passphrase_mismatch, Toast.LENGTH_SHORT
                            ).show()
                            else -> { dismiss(); runBackUpExport(pass) }
                        }
                    }
                }
            }
            .show()
    }

    private fun runBackUpExport(passphrase: String) {
        val json = try {
            ConfigBackup.export(config, passphrase.toCharArray())
        } catch (e: Exception) {
            Toast.makeText(this, R.string.backup_export_error, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val toDownloads = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            if (toDownloads) {
                writeBackupToMediaStore(json)
                Toast.makeText(this, R.string.backup_export_ok_downloads, Toast.LENGTH_LONG).show()
            } else {
                val file = File(cacheDir, BACKUP_FILE_NAME)
                file.writeText(json)
                Toast.makeText(this, R.string.backup_export_ok_cache, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.backup_export_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun writeBackupToMediaStore(json: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.IS_PENDING, 1)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        contentResolver.openOutputStream(uri).use { os ->
            os ?: throw IllegalStateException("MediaStore stream failed")
            os.write(json.toByteArray(Charsets.UTF_8))
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun backUpImport() {
        configBackupImportLauncher.launch(
            arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")
        )
    }

    private fun askImportPassphrase(content: String) {
        val view = layoutInflater.inflate(R.layout.dialog_passphrase, null)
        view.findViewById<EditText>(R.id.passphraseConfirmInput).visibility = View.GONE
        val passInput = view.findViewById<EditText>(R.id.passphraseInput)
        passInput.setHint(R.string.backup_passphrase_label)

        AlertDialog.Builder(this)
            .setTitle(R.string.backup_import_passphrase_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.backup_import, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val pass = passInput.text.toString()
                        if (pass.isEmpty()) {
                            Toast.makeText(this@BackupActivity, R.string.backup_passphrase_required, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        dismiss()
                        applyBackupImport(content, pass)
                    }
                }
            }
            .show()
    }

    /** Import führt zum Neustart der Activity (liest alle Felder aus dem neuen Stand). */
    private fun applyBackupImport(content: String, passphrase: String) {
        try {
            ConfigBackup.import(content, passphrase.toCharArray(), config)
            Toast.makeText(this, R.string.backup_import_ok, Toast.LENGTH_SHORT).show()
            recreate()
        } catch (e: javax.crypto.AEADBadTagException) {
            Toast.makeText(this, R.string.backup_import_wrong_passphrase, Toast.LENGTH_LONG).show()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, R.string.backup_import_invalid, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.backup_import_invalid, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val BACKUP_FILE_NAME = "hermi-config-backup.json"
    }
}