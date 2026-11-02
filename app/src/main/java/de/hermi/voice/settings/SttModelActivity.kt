package de.hermi.voice.settings

import android.app.AlertDialog
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import de.hermi.voice.R
import de.hermi.voice.stt.ModelManager
import kotlin.concurrent.thread

/**
 * Sprachmodell (STT): installierte Version anzeigen und manuell auf Updates
 * prüfen. Kein explizites Speichern nötig (nur Update-Flow).
 */
class SttModelActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_stt_model
    override val titleRes: Int = R.string.model_section_title

    /** Aktive STT-Sprache laut Einstellung (gekoppelt an die UI-Sprache). */
    private val activeSttLanguage: String
        get() = config.activeSttLanguage

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val installedName = ModelManager.installedVersion(this, activeSttLanguage)
        findViewById<TextView>(R.id.modelVersionText).text = getString(
            R.string.model_installed_version,
            ModelManager.versionDate(installedName) ?: installedName
        )
        findViewById<Button>(R.id.checkModelButton).setOnClickListener { checkModelUpdate() }
    }

    /** Manuelle Prüfung: ignoriert das Wochenfenster, prüft immer sofort. */
    private fun checkModelUpdate() {
        if (ModelManager.isDownloading()) return
        val status = findViewById<TextView>(R.id.modelStatusText)
        val button = findViewById<Button>(R.id.checkModelButton)
        button.isEnabled = false
        status.text = getString(R.string.model_check_searching)
        status.visibility = TextView.VISIBLE
        thread {
            try {
                val latest = ModelManager.latestModel(activeSttLanguage)
                runOnUiThread {
                    config.lastModelCheckMillis = System.currentTimeMillis()
                    button.isEnabled = true
                    if (latest == null) {
                        status.text = getString(R.string.model_check_current)
                        return@runOnUiThread
                    }
                    val installed = ModelManager.installedVersion(this, activeSttLanguage)
                    if (ModelManager.isNewer(latest, installed)) {
                        status.text = getString(R.string.model_check_update, latest.versionDate)
                        askModelUpdate(latest)
                    } else {
                        status.text = getString(R.string.model_check_current)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    button.isEnabled = true
                    status.text = getString(R.string.model_check_error)
                    Toast.makeText(
                        this,
                        getString(R.string.model_update_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun askModelUpdate(latest: ModelManager.ModelInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.model_update_title)
            .setMessage(getString(R.string.model_update_msg, latest.versionDate))
            .setPositiveButton(R.string.model_update_now) { _, _ -> runModelUpdate(latest) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runModelUpdate(latest: ModelManager.ModelInfo) {
        if (ModelManager.isDownloading()) return
        val status = findViewById<TextView>(R.id.modelStatusText)
        val button = findViewById<Button>(R.id.checkModelButton)
        button.isEnabled = false
        var phase = ModelManager.Phase.DOWNLOADING
        thread {
            try {
                val ok = ModelManager.downloadVersion(
                    this,
                    activeSttLanguage,
                    latest.name,
                    latest.downloadUrl,
                    onProgress = { p ->
                        runOnUiThread {
                            val label = if (phase == ModelManager.Phase.EXTRACTING) {
                                getString(R.string.model_extracting)
                            } else {
                                getString(R.string.model_downloading)
                            }
                            status.text = "$label ${(p * 100).toInt()} %"
                        }
                    },
                    onPhase = { ph ->
                        phase = ph
                        runOnUiThread {
                            status.text = if (ph == ModelManager.Phase.EXTRACTING) {
                                getString(R.string.model_extracting)
                            } else {
                                getString(R.string.model_downloading)
                            }
                        }
                    }
                )
                runOnUiThread {
                    button.isEnabled = true
                    if (ok) {
                        status.text = getString(R.string.model_updated)
                        findViewById<TextView>(R.id.modelVersionText).text = getString(
                            R.string.model_installed_version,
                            latest.versionDate
                        )
                        Toast.makeText(this, R.string.model_updated, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HermiVoice", "Modell-Update fehlgeschlagen", e)
                runOnUiThread {
                    button.isEnabled = true
                    status.text = getString(R.string.model_check_error)
                    Toast.makeText(
                        this,
                        getString(R.string.model_update_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}