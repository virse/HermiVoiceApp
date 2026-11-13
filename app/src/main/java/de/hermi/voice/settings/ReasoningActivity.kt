package de.hermi.voice.settings

import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import de.hermi.voice.R
import de.hermi.voice.config.ModelOptionsFetcher
import de.hermi.voice.config.Reasoning

/**
 * Einstellungen → Reasoning: zwei unabhängige Regler (An/Aus + Denk-Stufe).
 *
 *  - lokale Modelle: eigener Endpunkt (ollama, llama.cpp, Strata …)
 *  - Cloud-Modelle im Hermes-Kanal: alles, was über den Gateway läuft
 *
 * Welcher Regler bei einer Anfrage greift, entscheidet das Modell, das den
 * Turn tatsächlich fährt — auch beim Fallback-Modell (siehe [Reasoning]).
 * Bots und Cloud-Anbieter-Einträge können beide Werte übersteuern.
 */
class ReasoningActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_reasoning
    override val titleRes: Int = R.string.reasoning_section_title

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val fetcher = ModelOptionsFetcher.get()

        val localSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.reasoningLocalSwitch)
        val localLevel = findViewById<Spinner>(R.id.reasoningLocalLevel)
        val localLevelLabel = findViewById<TextView>(R.id.reasoningLocalLevelLabel)
        val localNotice = findViewById<TextView>(R.id.reasoningLocalNotice)
        val cloudSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.reasoningCloudSwitch)
        val cloudLevel = findViewById<Spinner>(R.id.reasoningCloudLevel)
        val cloudLevelLabel = findViewById<TextView>(R.id.reasoningCloudLevelLabel)
        val cloudNotice = findViewById<TextView>(R.id.reasoningCloudNotice)

        // „Standard" = kein Effort mitschicken (Agent-/Modell-Default bleibt).
        val levelValues = listOf(Reasoning.LEVEL_DEFAULT) + Reasoning.HERMES_LEVELS
        val levelLabels = levelValues.map {
            if (it == Reasoning.LEVEL_DEFAULT) getString(R.string.reasoning_level_default) else it
        }

        fun setupBlock(
            sw: androidx.appcompat.widget.SwitchCompat,
            spin: Spinner,
            spinLabel: TextView,
            initialEnabled: Boolean,
            initialEffort: String,
            onToggle: (Boolean) -> Unit,
            onEffort: (String) -> Unit
        ) {
            sw.isChecked = initialEnabled
            spin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, levelLabels)
            val idx = levelValues.indexOf(initialEffort).let { if (it >= 0) it else 0 }
            spin.setSelection(idx)
            val syncEnabled = {
                val on = sw.isChecked
                spin.isEnabled = on
                spin.alpha = if (on) 1f else 0.45f
                spinLabel.alpha = if (on) 1f else 0.45f
            }
            syncEnabled()
            sw.setOnCheckedChangeListener { _, checked ->
                onToggle(checked)
                syncEnabled()
            }
            var last = levelValues.getOrElse(idx) { Reasoning.LEVEL_DEFAULT }
            spin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    // Nur echte Änderungen speichern (Aufbau/Wiederholung nicht).
                    val value = levelValues.getOrElse(pos) { Reasoning.LEVEL_DEFAULT }
                    if (value == last) return
                    last = value
                    onEffort(value)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        setupBlock(
            localSwitch, localLevel, localLevelLabel,
            config.reasoningLocalEnabled, config.reasoningLocalEffort,
            { config.reasoningLocalEnabled = it },
            { config.reasoningLocalEffort = it }
        )
        setupBlock(
            cloudSwitch, cloudLevel, cloudLevelLabel,
            config.reasoningCloudEnabled, config.reasoningCloudEffort,
            { config.reasoningCloudEnabled = it },
            { config.reasoningCloudEffort = it }
        )

        // Ausgrauen: kann das gewählte Primärmodell laut Gateway kein Reasoning,
        // bleibt der zugehörige Schalter wirkungslos (es wird nichts gesendet).
        // Die Modellliste ist hier nur gecacht vorhanden — fehlt sie, bleibt
        // alles bedienbar (die Entscheidung fällt ohnehin pro Anfrage).
        val pair = config.primaryModelPair()
        if (pair != null) {
            val local = fetcher.isLocalProvider(pair.first) ?: Reasoning.looksLocal(pair.second)
            val caps = fetcher.capabilitiesFor(pair.first, pair.second)
            if (caps != null && !caps.reasoning) {
                if (local) {
                    localSwitch.isEnabled = false
                    localSwitch.alpha = 0.45f
                    localLevel.isEnabled = false
                    localNotice.visibility = View.VISIBLE
                } else {
                    cloudSwitch.isEnabled = false
                    cloudSwitch.alpha = 0.45f
                    cloudLevel.isEnabled = false
                    cloudNotice.visibility = View.VISIBLE
                }
            }
        }
    }
}
