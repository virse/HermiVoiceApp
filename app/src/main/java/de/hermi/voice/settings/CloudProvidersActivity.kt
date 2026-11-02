package de.hermi.voice.settings

import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import de.hermi.voice.R
import de.hermi.voice.config.CloudModelFetcher
import de.hermi.voice.config.CloudProvider
import de.hermi.voice.config.CloudProviders
import de.hermi.voice.config.CloudTools
import de.hermi.voice.config.ModelPickerDialog
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Cloud-KI-Anbieter: mehrere OpenAI-kompatible Dienste (OpenRouter, OpenAI,
 * Groq, Mistral, DeepSeek …) mit eigenem API-Key und eigener Modellwahl.
 * Unbegrenzt anlegbar (wie die Bots). Die Basis-URL kommt fix aus der
 * Provider-Tabelle — kein Nutzer-URL-Feld. Wird sofort gespeichert
 * (config.saveCloudProviders bei jeder Aktion).
 */
class CloudProvidersActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_cloud
    override val titleRes: Int = R.string.cloud_section_title

    private lateinit var cloudContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cloudContainer = findViewById(R.id.cloudContainer)
        findViewById<Button>(R.id.addCloudButton).setOnClickListener { showCloudEditor(null) }
        renderClouds()
    }

    private fun renderClouds() {
        cloudContainer.removeAllViews()
        val providers = config.getCloudProviders()
        if (providers.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.cloud_empty)
            empty.setTextColor(resources.getColor(R.color.text_secondary, theme))
            empty.textSize = 13f
            empty.setPadding(0, 12, 0, 0)
            cloudContainer.addView(empty)
            return
        }
        for (p in providers) {
            val row = layoutInflater.inflate(R.layout.item_cloud_setting, cloudContainer, false)
            row.findViewById<TextView>(R.id.cloudNameText).text = p.name.ifBlank {
                p.providerName()
            }
            row.findViewById<TextView>(R.id.cloudInfoText).text = cloudInfoLabel(p)
            row.findViewById<Button>(R.id.editCloudButton).setOnClickListener { showCloudEditor(p) }
            row.findViewById<Button>(R.id.deleteCloudButton).setOnClickListener { confirmDelete(p) }
            cloudContainer.addView(row)
        }
    }

    private fun cloudInfoLabel(p: CloudProvider): String {
        val keyText = if (p.isUsable()) getString(R.string.cloud_key_set)
            else getString(R.string.cloud_key_missing)
        val sb = StringBuilder(getString(R.string.bot_info_key, keyText))
        sb.append('\n').append(getString(R.string.cloud_info_provider, p.providerName()))
        val prim = p.primaryPair()
        val fb = p.fallbackPair()
        if (prim != null) {
            sb.append('\n').append(getString(R.string.cloud_info_primary, prim.second))
        }
        if (fb != null) {
            sb.append('\n').append(getString(R.string.cloud_info_fallback, fb.second))
        }
        if (prim == null && fb == null) {
            sb.append('\n').append(getString(R.string.cloud_info_no_model))
        }
        return sb.toString()
    }

    private fun showCloudEditor(existing: CloudProvider?) {
        val isNew = existing == null
        val editor = existing?.let {
            CloudProvider(
                id = it.id, name = it.name, provider = it.provider, apiKey = it.apiKey,
                primaryModel = it.primaryModel, fallbackModel = it.fallbackModel,
                tools = it.tools
            )
        } ?: CloudProvider(
            id = UUID.randomUUID().toString(),
            name = "",
            provider = CloudProviders.allSlugs().firstOrNull() ?: ""
        )

        val view = layoutInflater.inflate(R.layout.dialog_cloud_editor, null)
        val providerSpinner = view.findViewById<Spinner>(R.id.cloudEditProvider)
        val keyInput = view.findViewById<EditText>(R.id.cloudEditKey)
        val primaryBtn = view.findViewById<Button>(R.id.cloudEditPrimary)
        val fallbackBtn = view.findViewById<Button>(R.id.cloudEditFallback)
        val hint = view.findViewById<TextView>(R.id.cloudEditHint)

        // Anbieter-Dropdown: Nur Tabellen-Einträge, "Name (Base-URL)".
        val slugs = CloudProviders.allSlugs()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            slugs.map { CloudProviders.providerName(it) })
        providerSpinner.adapter = adapter
        val startIdx = slugs.indexOf(editor.provider).let { if (it >= 0) it else 0 }
        providerSpinner.setSelection(startIdx)

        keyInput.setText(editor.apiKey)

        fun labelFor(pair: Pair<String, String>?): String = if (pair == null)
            getString(R.string.primary_model_default) else pair.second

        fun refreshModelButtons() {
            primaryBtn.text = if (editor.primaryPair() != null)
                labelFor(editor.primaryPair()) else getString(R.string.primary_model_default)
            fallbackBtn.text = if (editor.fallbackPair() != null)
                labelFor(editor.fallbackPair()) else getString(R.string.fallback_model_default)
        }
        refreshModelButtons()

        // Aktivierbare Server-Tools (aus der Registry) als Checkboxen erzeugen.
        // Neue Tools tauchen hier automatisch auf, sobald sie in CloudTools.available
        // hinzugefügt werden — kein weiterer UI-Eingriff nötig (erweiterbarer Aufbau).
        // Anbieterabhängig: Tools, die der gewählte Anbieter über /v1/chat/completions
        // nicht unterstützt, werden ausgegraut (isEnabled=false) und nicht gespeichert.
        val toolsContainer = view.findViewById<LinearLayout>(R.id.cloudEditTools)
        var currentTools = editor.tools
        val toolBoxes = mutableMapOf<String, CheckBox>()
        fun refreshToolAvailability(providerSlug: String) {
            for (def in CloudTools.available) {
                val cb = toolBoxes[def.slug] ?: continue
                val supported = CloudTools.isEnabledFor(providerSlug, def.slug)
                cb.isEnabled = supported
                cb.alpha = if (supported) 1f else 0.45f
                cb.text = if (supported) getString(def.labelRes) else
                    "${getString(def.labelRes)} (${getString(R.string.cloud_tool_not_supported)})"
            }
        }
        for (def in CloudTools.available) {
            val cb = CheckBox(this).apply {
                id = View.generateViewId()
                text = getString(def.labelRes)
                setTextColor(resources.getColor(if (resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
                    R.color.text_primary else R.color.text_primary, null))
                isChecked = def.slug in currentTools
                setOnCheckedChangeListener { _, checked ->
                    currentTools = if (checked) currentTools + def.slug else currentTools - def.slug
                }
            }
            toolBoxes[def.slug] = cb
            toolsContainer.addView(cb, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        // Die Tool-Verfügbarkeit folgt dem gewählten Anbieter im Spinner.
        refreshToolAvailability(slugs.getOrNull(providerSpinner.selectedItemPosition) ?: "")
        providerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?,
                                        position: Int, id: Long) {
                refreshToolAvailability(slugs.getOrNull(position) ?: "")
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        fun openCloudPicker(pickFallback: Boolean) {
            val slug = slugs.getOrNull(providerSpinner.selectedItemPosition)
                ?: run { hint.text = getString(R.string.model_list_error); return }
            val key = keyInput.text.toString().trim()
            // API-Key: ggf. im Editor neu getippt oder bereits gespeicherter Key verwenden.
            val apiKey = key.ifBlank { editor.apiKey }
            if (apiKey.isBlank()) {
                hint.text = getString(R.string.cloud_required)
                return
            }
            hint.text = getString(R.string.model_list_loading)
            thread {
                val provs = try {
                    CloudModelFetcher.refresh(slug, apiKey)
                    CloudModelFetcher.getProvider(slug)
                } catch (e: Exception) {
                    null
                }
                runOnUiThread {
                    if (provs == null) {
                        hint.text = getString(R.string.cloud_models_invalid)
                        return@runOnUiThread
                    }
                    hint.text = getString(R.string.cloud_edit_hint)
                    val initial = if (pickFallback) editor.fallbackPair()
                        else editor.primaryPair()
                    val exclude = if (pickFallback) editor.primaryPair() else null
                    ModelPickerDialog(
                        context = this,
                        providers = listOf(provs),
                        initial = initial,
                        excludeKey = exclude,
                        confirmTitle = if (pickFallback) getString(R.string.pick_fallback_model)
                            else getString(R.string.pick_primary_model),
                        scope = editor.id,
                        onSelect = { sel ->
                            if (pickFallback) {
                                editor.fallbackModel = sel?.second ?: ""
                            } else {
                                editor.primaryModel = sel?.second ?: ""
                            }
                            refreshModelButtons()
                        }
                    ).show()
                }
            }
        }

        primaryBtn.setOnClickListener { openCloudPicker(false) }
        fallbackBtn.setOnClickListener { openCloudPicker(true) }

        AlertDialog.Builder(this)
            .setTitle(if (isNew) R.string.cloud_add else R.string.cloud_edit)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (isNew) R.string.cloud_add_confirm else R.string.cloud_edit_confirm, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val slug = slugs.getOrNull(providerSpinner.selectedItemPosition) ?: ""
                        val key = keyInput.text.toString().trim()
                        if (slug.isBlank() || key.isBlank()) {
                            Toast.makeText(this@CloudProvidersActivity, R.string.cloud_required, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        editor.provider = slug
                        editor.apiKey = key
                        // Nur Tools speichern, die der gewählte Anbieter tatsächlich
                        // über /v1/chat/completions unterstützt (Registry-basiert).
                        editor.tools = currentTools.filterTo(mutableSetOf()) {
                            CloudTools.isEnabledFor(slug, it)
                        }
                        if (editor.name.isBlank()) editor.name = CloudProviders.providerName(slug)
                        val list = config.getCloudProviders().toMutableList()
                        val idx = list.indexOfFirst { it.id == editor.id }
                        if (idx >= 0) list[idx] = editor else list.add(editor)
                        config.saveCloudProviders(list)
                        renderClouds()
                        dismiss()
                    }
                }
            }
            .show()
    }

    private fun confirmDelete(p: CloudProvider) {
        AlertDialog.Builder(this)
            .setTitle(R.string.cloud_delete_confirm_title)
            .setMessage(R.string.cloud_delete_confirm_msg)
            .setPositiveButton(R.string.cloud_delete) { _, _ ->
                config.saveCloudProviders(config.getCloudProviders().filterNot { it.id == p.id })
                renderClouds()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}