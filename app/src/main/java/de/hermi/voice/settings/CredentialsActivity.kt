package de.hermi.voice.settings

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import de.hermi.voice.R
import de.hermi.voice.config.CleartextCheck

/**
 * URL- und Zugangsdaten: Server-URL, API-Key, externe Gateway-Domain,
 * Nachrichten-Präfix. Diese vier Textfelder werden beim Verlassen eines
 * Feldes bzw. beim Verlassen des Screens gespeichert.
 */
class CredentialsActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_credentials
    override val titleRes: Int = R.string.credentials_section_title

    private lateinit var urlInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var externalDomainInput: EditText
    private lateinit var prefixInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        urlInput = findViewById(R.id.urlInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        externalDomainInput = findViewById(R.id.externalDomainInput)
        prefixInput = findViewById(R.id.prefixInput)

        urlInput.setText(config.serverUrl)
        apiKeyInput.setText(config.apiKey)
        externalDomainInput.setText(config.externalDomain)
        prefixInput.setText(config.messagePrefix)

        // Beim Verlassen eines Feldes sofort speichern.
        urlInput.onFocusChangeListener = focusSave {
            config.serverUrl = urlInput.text.toString()
            warnIfCleartext(urlInput.text.toString())
        }
        apiKeyInput.onFocusChangeListener = focusSave { config.apiKey = apiKeyInput.text.toString() }
        externalDomainInput.onFocusChangeListener = focusSave {
            config.externalDomain = externalDomainInput.text.toString()
            warnIfCleartext(externalDomainInput.text.toString())
        }
        prefixInput.onFocusChangeListener = focusSave {
            config.messagePrefix = prefixInput.text.toString()
        }
    }

    /** Warnt, wenn eine http://-Adresse außerhalb des lokalen Netzes eingetragen wird:
     *  API-Key und Chat würden unverschlüsselt über das Internet laufen. */
    private fun warnIfCleartext(url: String) {
        if (CleartextCheck.isRisky(url)) {
            Toast.makeText(this, R.string.cleartext_warning, Toast.LENGTH_LONG).show()
        }
    }

    /** Speichert beim Verlassen des Feldes (Fokus geht ab). */
    private inline fun focusSave(crossinline save: () -> Unit): View.OnFocusChangeListener =
        View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) save()
        }

    /** Sichert auch halb getippte Werte, falls mit Fokus im Feld zurückgenavigt wird. */
    override fun onPause() {
        super.onPause()
        config.serverUrl = urlInput.text?.toString().orEmpty()
        config.apiKey = apiKeyInput.text?.toString().orEmpty()
        config.externalDomain = externalDomainInput.text?.toString().orEmpty()
        config.messagePrefix = prefixInput.text?.toString().orEmpty()
    }
}