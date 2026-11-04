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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import de.hermi.voice.R
import de.hermi.voice.config.ConfigBackup
import de.hermi.voice.update.UpdateManager
import de.hermi.voice.update.UpdateUi
import de.hermi.voice.update.UpdateVersion
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * „Update & Backup“: Update-Prüfung (Toggle, manueller Check, Status) und die
 * verschlüsselte Konfigurationssicherung in einem Screen. Optik analog zu
 * „Akzent & Hintergrund“ (Sektionsüberschrift in Akzentfarbe, Bold-Untertitel,
 * Hint, Controls).
 *
 * Die Update-Logik selbst liegt in [UpdateManager]; diese Activity verdrahtet
 * nur UI und Backup-Ablauf.
 */
class UpdateBackupActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_update_backup
    override val titleRes: Int = R.string.update_backup_section_title

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
        } catch (_: Exception) {
            Toast.makeText(this, R.string.backup_import_invalid, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUpdateSection()
        setupBackupSection()
    }

    override fun onResume() {
        super.onResume()
        // Prüft Pflicht-Updates, laufende Downloads und den Auto-Check.
        UpdateManager.onAppResume(this)
        refreshStatus()
    }

    // ── Update ───────────────────────────────────────────────────────────────

    private fun setupUpdateSection() {
        val switch = findViewById<SwitchCompat>(R.id.updateAutoCheckSwitch)
        switch.isChecked = config.updateAutoCheck
        switch.setOnCheckedChangeListener { _, checked ->
            val previous = config.updateAutoCheck
            config.updateAutoCheck = checked
            refreshStatus()
            if (checked && !previous) {
                // Einschalten prüft sofort, statt bis zum nächsten Start zu warten.
                triggerManualCheck()
            }
        }

        findViewById<Button>(R.id.updateCheckButton).setOnClickListener {
            triggerManualCheck()
        }
    }

    private fun triggerManualCheck() {
        val button = findViewById<Button>(R.id.updateCheckButton)
        val status = findViewById<TextView>(R.id.updateStatusText)
        button.isEnabled = false
        status.text = getString(R.string.update_checking)

        val started = UpdateManager.checkManually(this) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                button.isEnabled = true
                when (result) {
                    is UpdateManager.CheckResult.Available -> {
                        refreshStatus()
                        UpdateUi.showUpdateDialog(
                            activity = this,
                            info = result.info,
                            forced = result.forced,
                            onInstall = {
                                UpdateManager.startDownload(this, result.info)
                                refreshStatus()
                            },
                            onLater = {
                                config.updateSkippedVersion = result.info.versionCode
                                refreshStatus()
                            }
                        )
                    }

                    is UpdateManager.CheckResult.UpToDate -> {
                        refreshStatus()
                        Toast.makeText(
                            this,
                            getString(R.string.update_up_to_date, result.versionName),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    is UpdateManager.CheckResult.Failure -> {
                        refreshStatus()
                        Toast.makeText(
                            this,
                            getString(R.string.update_check_failed, result.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        if (!started) {
            button.isEnabled = true
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        val status = findViewById<TextView>(R.id.updateStatusText)
        val version = getString(
            R.string.update_current_version,
            UpdateVersion.installedVersionName(this)
        )
        val lastCheck = config.updateLastSuccessMillis
        val checkText = if (lastCheck <= 0L) {
            getString(R.string.update_never_checked)
        } else {
            val formatted = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT
            ).format(Date(lastCheck))
            getString(R.string.update_last_check, formatted)
        }
        status.text = "$version · $checkText"
    }

    // ── Backup ───────────────────────────────────────────────────────────────

    private fun setupBackupSection() {
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
                                this@UpdateBackupActivity,
                                R.string.backup_passphrase_required,
                                Toast.LENGTH_SHORT
                            ).show()

                            pass != confirm -> Toast.makeText(
                                this@UpdateBackupActivity,
                                R.string.backup_passphrase_mismatch,
                                Toast.LENGTH_SHORT
                            ).show()

                            else -> {
                                dismiss()
                                runBackUpExport(pass)
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun runBackUpExport(passphrase: String) {
        val json = try {
            ConfigBackup.export(config, passphrase.toCharArray())
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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
                            Toast.makeText(
                                this@UpdateBackupActivity,
                                R.string.backup_passphrase_required,
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnClickListener
                        }
                        dismiss()
                        applyBackupImport(content, pass)
                    }
                }
            }
            .show()
    }

    private fun applyBackupImport(content: String, passphrase: String) {
        try {
            ConfigBackup.import(content, passphrase.toCharArray(), config)
            Toast.makeText(this, R.string.backup_import_ok, Toast.LENGTH_SHORT).show()
            recreate()
        } catch (_: javax.crypto.AEADBadTagException) {
            Toast.makeText(this, R.string.backup_import_wrong_passphrase, Toast.LENGTH_LONG).show()
        } catch (_: IllegalArgumentException) {
            Toast.makeText(this, R.string.backup_import_invalid, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.backup_import_invalid, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val BACKUP_FILE_NAME = "hermi-config-backup.json"
    }
}
