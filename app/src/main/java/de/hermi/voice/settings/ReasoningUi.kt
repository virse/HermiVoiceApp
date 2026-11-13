package de.hermi.voice.settings

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Spinner
import de.hermi.voice.R
import de.hermi.voice.config.Reasoning

/**
 * Gemeinsame UI-Bausteine für die Reasoning-Overrides (Bot-Editor und
 * Cloud-Anbieter-Editor): An/Aus/Global-Auswahl und Denk-Stufe.
 *
 * Die Spinner arbeiten mit den persistierten Rohwerten (``"inherit"``, ``"on"``,
 * ``"off"`` bzw. ``""`` = global erben, ``"default"`` = kein Effort senden).
 * Labels kommen aus den Ressourcen, damit beide Sprachen abgedeckt sind.
 *
 * Wichtig: Auswahl-Events werden gegen den zuletzt bekannten Wert geprüft
 * (statt gegen ein „erster Aufruf"-Flag). So geht weder eine echte Nutzer-
 * Auswahl verloren, noch speichert der Aufbau des Dialogs einen Wert.
 */
object ReasoningUi {

    /** Werte in Anzeigereihenfolge: Global → An → Aus. */
    private val MODE_VALUES = listOf(Reasoning.MODE_INHERIT, Reasoning.MODE_ON, Reasoning.MODE_OFF)

    private fun modeLabels(ctx: Context): List<String> = listOf(
        ctx.getString(R.string.reasoning_mode_inherit),
        ctx.getString(R.string.reasoning_mode_on),
        ctx.getString(R.string.reasoning_mode_off)
    )

    /** Stufenwerte inklusive „global erben" (nur fürs Override-UI). */
    private fun levelValues(levels: List<String>): List<String> =
        listOf("", Reasoning.LEVEL_DEFAULT) + levels

    /**
     * Baut den Modus-Spinner (Global/An/Aus) und meldet Änderungen über
     * [onChange] — nur, wenn sich der Wert wirklich ändert.
     */
    fun setupMode(ctx: Context, spinner: Spinner, current: String, onChange: (String) -> Unit) {
        bind(ctx, spinner, MODE_VALUES, modeLabels(ctx), current, Reasoning.MODE_INHERIT, onChange)
    }

    /**
     * Baut den Stufen-Spinner für Override-Oberflächen: „global erben",
     * „Standard" (kein Effort) und die Stufen des Hermes-Kanals.
     */
    fun setupLevel(ctx: Context, spinner: Spinner, current: String, onChange: (String) -> Unit) {
        val labels = listOf(
            ctx.getString(R.string.reasoning_level_inherit),
            ctx.getString(R.string.reasoning_level_default)
        ) + Reasoning.HERMES_LEVELS
        bind(ctx, spinner, levelValues(Reasoning.HERMES_LEVELS), labels, current, "", onChange)
    }

    /**
     * Stufen-Spinner des direkten Cloud-Kanals: nur die Stufen, die der
     * gewählte Anbieter/das Modell wirklich kennt. Leere Liste = Anbieter ohne
     * Leiter (nur an/aus) — dann bleibt ein einzelner, ausgegrauter Eintrag.
     */
    fun setupProviderLevel(
        ctx: Context,
        spinner: Spinner,
        levels: List<String>,
        current: String,
        onChange: (String) -> Unit
    ) {
        val values: List<String>
        val labels: List<String>
        if (levels.isEmpty()) {
            values = listOf("")
            labels = listOf(ctx.getString(R.string.reasoning_level_no_steps))
        } else {
            values = levelValues(levels)
            labels = listOf(
                ctx.getString(R.string.reasoning_level_inherit),
                ctx.getString(R.string.reasoning_level_default)
            ) + levels
        }
        spinner.isEnabled = levels.isNotEmpty()
        spinner.alpha = if (levels.isNotEmpty()) 1f else 0.45f
        bind(ctx, spinner, values, labels, current, "", onChange)
    }

    /** Gemeinsamer Aufbau: Adapter, Auswahl, listener mit Wert-Vergleich. */
    private fun bind(
        ctx: Context,
        spinner: Spinner,
        values: List<String>,
        labels: List<String>,
        current: String,
        fallback: String,
        onChange: (String) -> Unit
    ) {
        spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
        val index = values.indexOf(current).let { if (it >= 0) it else values.indexOf(fallback).coerceAtLeast(0) }
        spinner.setSelection(index)
        var last = values.getOrElse(index) { fallback }
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val value = values.getOrElse(pos) { fallback }
                if (value == last) return
                last = value
                onChange(value)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }
}
