package de.hermi.voice.settings

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import de.hermi.voice.R
import de.hermi.voice.config.AppConfig

/**
 * Neue Konversation nach Pause: Toggle + Minuten-Auswahl. Sofort gespeichert.
 */
class AutoSessionActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_auto_session
    override val titleRes: Int = R.string.auto_session_section_title

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val autoSessionSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.autoSessionSwitch)
        val minutesSpinner = findViewById<Spinner>(R.id.autoSessionMinutesSpinner)

        val minutesLabels = AppConfig.AUTO_NEW_SESSION_OPTIONS.map {
            getString(R.string.minutes_format, it)
        }
        minutesSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, minutesLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val currentMinutes = AppConfig.AUTO_NEW_SESSION_OPTIONS.indexOf(config.autoNewSessionMinutes)
        minutesSpinner.setSelection(if (currentMinutes >= 0) currentMinutes else
            AppConfig.AUTO_NEW_SESSION_OPTIONS.indexOf(AppConfig.DEFAULT_AUTO_NEW_SESSION_MINUTES))

        autoSessionSwitch.isChecked = config.autoNewSession
        minutesSpinner.isEnabled = autoSessionSwitch.isChecked
        autoSessionSwitch.setOnCheckedChangeListener { _, checked ->
            config.autoNewSession = checked
            minutesSpinner.isEnabled = checked
        }
        minutesSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                config.autoNewSessionMinutes = AppConfig.AUTO_NEW_SESSION_OPTIONS[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }
}