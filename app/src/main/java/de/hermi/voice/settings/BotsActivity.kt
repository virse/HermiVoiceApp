package de.hermi.voice.settings

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.hermi.voice.R
import de.hermi.voice.config.BotProfile
import de.hermi.voice.config.ModelOptionsFetcher
import de.hermi.voice.config.ModelPickerDialog
import de.hermi.voice.config.Reasoning
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Profile/Bots: mehrere Hermes-Profile mit eigenem API-Key und eigener
 * Modellwahl. Wird sofort gespeichert (config.saveBots bei jeder Aktion).
 */
class BotsActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_bots
    override val titleRes: Int = R.string.bots_section_title

    private lateinit var botsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        botsContainer = findViewById(R.id.botsContainer)
        findViewById<Button>(R.id.addBotButton).setOnClickListener { showBotEditor(null) }
        renderBots()
    }

    private fun renderBots() {
        botsContainer.removeAllViews()
        val bots = config.getBots()
        if (bots.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.bots_empty)
            empty.setTextColor(resources.getColor(R.color.text_secondary, theme))
            empty.textSize = 13f
            empty.setPadding(0, 12, 0, 0)
            botsContainer.addView(empty)
            return
        }
        for (b in bots) {
            val row = layoutInflater.inflate(R.layout.item_bot_setting, botsContainer, false)
            row.findViewById<TextView>(R.id.botNameText).text = b.name.ifBlank {
                getString(R.string.bot_name_hint)
            }
            row.findViewById<TextView>(R.id.botInfoText).text = botInfoLabel(b)
            row.findViewById<Button>(R.id.editBotButton).setOnClickListener { showBotEditor(b) }
            row.findViewById<Button>(R.id.deleteBotButton).setOnClickListener { confirmDeleteBot(b) }
            botsContainer.addView(row)
        }
    }

    private fun botInfoLabel(b: BotProfile): String {
        val keyText = if (b.isUsable()) getString(R.string.bot_key_set)
            else getString(R.string.bot_key_missing)
        val sb = StringBuilder(getString(R.string.bot_info_key, keyText))
        val prim = b.primaryModelPair()
        val fb = b.fallbackModelPair()
        if (prim != null) {
            sb.append('\n').append(getString(R.string.bot_info_primary, "${prim.first}/${prim.second}"))
        }
        if (fb != null) {
            sb.append('\n').append(getString(R.string.bot_info_fallback, "${fb.first}/${fb.second}"))
        }
        if (prim == null && fb == null) {
            sb.append('\n').append(getString(R.string.bot_info_no_model))
        }
        sb.append('\n').append(
            getString(R.string.reasoning_bot_local_label) + ": " +
                reasoningLabel(b.localReasoningMode, b.localReasoningEffort)
        )
        sb.append('\n').append(
            getString(R.string.reasoning_bot_cloud_label) + ": " +
                reasoningLabel(b.cloudReasoningMode, b.cloudReasoningEffort)
        )
        return sb.toString()
    }

    /** Kurzlabel „An · medium" / „Aus" / „Global (Standard)" für die Übersicht. */
    private fun reasoningLabel(mode: String, effort: String): String {
        val m = when (mode) {
            Reasoning.MODE_ON -> getString(R.string.reasoning_mode_on)
            Reasoning.MODE_OFF -> getString(R.string.reasoning_mode_off)
            else -> getString(R.string.reasoning_mode_inherit)
        }
        return when {
            mode == Reasoning.MODE_OFF -> m
            effort.isBlank() -> m
            effort == Reasoning.LEVEL_DEFAULT -> "$m · ${getString(R.string.reasoning_level_default)}"
            else -> "$m · $effort"
        }
    }

    private fun showBotEditor(existing: BotProfile?) {
        val isNew = existing == null
        val editor = existing?.let {
            BotProfile(
                id = it.id, name = it.name, apiKey = it.apiKey, serverBase = it.serverBase,
                primaryProvider = it.primaryProvider, primaryModel = it.primaryModel,
                fallbackProvider = it.fallbackProvider, fallbackModel = it.fallbackModel,
                localReasoningMode = it.localReasoningMode,
                localReasoningEffort = it.localReasoningEffort,
                cloudReasoningMode = it.cloudReasoningMode,
                cloudReasoningEffort = it.cloudReasoningEffort
            )
        } ?: BotProfile(id = UUID.randomUUID().toString(), name = "")

        val view = layoutInflater.inflate(R.layout.dialog_bot_editor, null)
        val nameInput = view.findViewById<EditText>(R.id.botEditName)
        val keyInput = view.findViewById<EditText>(R.id.botEditKey)
        val primaryBtn = view.findViewById<Button>(R.id.botEditPrimary)
        val fallbackBtn = view.findViewById<Button>(R.id.botEditFallback)
        val hint = view.findViewById<TextView>(R.id.botEditHint)
        val serverMode = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.botEditServerMode)
        val serverBaseInput = view.findViewById<EditText>(R.id.botEditServerBase)
        nameInput.setText(editor.name)
        keyInput.setText(editor.apiKey)
        serverBaseInput.setText(editor.serverBase)
        serverMode.isChecked = editor.serverBase.isNotBlank()
        serverBaseInput.visibility = if (serverMode.isChecked) TextView.VISIBLE else TextView.GONE
        serverMode.setOnCheckedChangeListener { _, checked ->
            serverBaseInput.visibility = if (checked) TextView.VISIBLE else TextView.GONE
        }

        fun labelFor(pair: Pair<String, String>?): String = if (pair == null)
            getString(R.string.primary_model_default) else "${pair.first} / ${pair.second}"

        fun refreshModelButtons() {
            primaryBtn.text = labelFor(editor.primaryModelPair())
            fallbackBtn.text = if (editor.hasFallbackModel()) labelFor(editor.fallbackModelPair())
                else getString(R.string.fallback_model_default)
        }
        refreshModelButtons()

        val fetcher = ModelOptionsFetcher.get()
        fun openBotPicker(pickFallback: Boolean) {
            hint.text = getString(R.string.model_list_loading)
            val customBase = editor.serverBase.trim()
            val url = if (customBase.isNotEmpty()) customBase else config.serverUrl
            val key = if (customBase.isNotEmpty() && editor.apiKey.isNotBlank()) editor.apiKey else config.apiKey
            thread {
                val provs = try {
                    fetcher.refresh(url, key)
                    fetcher.getProviders()
                } catch (e: Exception) {
                    null
                }
                runOnUiThread {
                    if (provs.isNullOrEmpty()) {
                        hint.text = getString(R.string.model_list_error)
                        return@runOnUiThread
                    }
                    hint.text = getString(R.string.bot_edit_hint)
                    val initial = if (pickFallback) editor.fallbackModelPair()
                        else editor.primaryModelPair()
                    val exclude = if (pickFallback) editor.primaryModelPair() else null
                    ModelPickerDialog(
                        context = this,
                        providers = provs,
                        initial = initial,
                        excludeKey = exclude,
                        confirmTitle = if (pickFallback) getString(R.string.pick_fallback_model)
                            else getString(R.string.pick_primary_model),
                        scope = editor.id,
                        onSelect = { sel ->
                            if (pickFallback) {
                                editor.fallbackProvider = sel?.first ?: ""
                                editor.fallbackModel = sel?.second ?: ""
                            } else {
                                editor.primaryProvider = sel?.first ?: ""
                                editor.primaryModel = sel?.second ?: ""
                            }
                            refreshModelButtons()
                        }
                    ).show()
                }
            }
        }

        primaryBtn.setOnClickListener { openBotPicker(false) }
        fallbackBtn.setOnClickListener { openBotPicker(true) }

        // Reasoning-Overrides dieses Bots (global / an / aus + eigene Stufe).
        ReasoningUi.setupMode(this, view.findViewById(R.id.botEditLocalReasoning), editor.localReasoningMode) {
            editor.localReasoningMode = it
        }
        ReasoningUi.setupLevel(this, view.findViewById(R.id.botEditLocalReasoningLevel), editor.localReasoningEffort) {
            editor.localReasoningEffort = it
        }
        ReasoningUi.setupMode(this, view.findViewById(R.id.botEditCloudReasoning), editor.cloudReasoningMode) {
            editor.cloudReasoningMode = it
        }
        ReasoningUi.setupLevel(this, view.findViewById(R.id.botEditCloudReasoningLevel), editor.cloudReasoningEffort) {
            editor.cloudReasoningEffort = it
        }

        AlertDialog.Builder(this)
            .setTitle(if (isNew) R.string.bot_add else R.string.bot_edit)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (isNew) R.string.bot_add_confirm else R.string.bot_edit_confirm, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = nameInput.text.toString().trim()
                        val key = keyInput.text.toString().trim()
                        if (name.isBlank() && key.isBlank()) {
                            Toast.makeText(this@BotsActivity, R.string.bot_required, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        editor.name = name
                        editor.apiKey = key
                        editor.serverBase = if (serverMode.isChecked)
                            serverBaseInput.text.toString().trim() else ""
                        val list = config.getBots().toMutableList()
                        val idx = list.indexOfFirst { it.id == editor.id }
                        if (idx >= 0) list[idx] = editor else list.add(editor)
                        config.saveBots(list)
                        renderBots()
                        dismiss()
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteBot(b: BotProfile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.bot_delete_confirm_title)
            .setMessage(R.string.bot_delete_confirm_msg)
            .setPositiveButton(R.string.bot_delete) { _, _ ->
                config.saveBots(config.getBots().filterNot { it.id == b.id })
                renderBots()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}