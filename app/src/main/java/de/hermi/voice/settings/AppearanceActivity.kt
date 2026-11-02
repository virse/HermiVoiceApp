package de.hermi.voice.settings

import android.os.Bundle
import de.hermi.voice.R

/**
 * Darstellung: Antwort-Trenner, Haptik, Sprachausgabe, Startmodus und Tastatur.
 * Alle Schalter speichern sofort.
 */
class AppearanceActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_appearance
    override val titleRes: Int = R.string.appearance_section_title

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.showSeparatorSwitch).apply {
            isChecked = config.showMessageSeparator
            setOnCheckedChangeListener { _, checked -> config.showMessageSeparator = checked }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.hapticFeedbackSwitch).apply {
            isChecked = config.hapticFeedback
            setOnCheckedChangeListener { _, checked -> config.hapticFeedback = checked }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.ttsReadAnswerSwitch).apply {
            isChecked = config.ttsReadAnswer
            setOnCheckedChangeListener { _, checked -> config.ttsReadAnswer = checked }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.startInputModeSwitch).apply {
            isChecked = config.startInputMode
            setOnCheckedChangeListener { _, checked -> config.startInputMode = checked }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.keyboardAfterAnswerSwitch).apply {
            isChecked = config.keyboardInForeground
            setOnCheckedChangeListener { _, checked -> config.keyboardInForeground = checked }
        }
    }
}