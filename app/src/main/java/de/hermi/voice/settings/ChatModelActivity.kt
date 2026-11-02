package de.hermi.voice.settings

import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import de.hermi.voice.R
import de.hermi.voice.api.HermesApiSender
import de.hermi.voice.config.AppConfig
import de.hermi.voice.config.ModelOptionsFetcher
import de.hermi.voice.config.ModelPickerDialog
import kotlin.concurrent.thread

/**
 * KI-Modell (Antwort): primär + Fallback, Liste, Schwelle, Test.
 * Alle Änderungen (Modellauswahl, Schwelle) werden sofort gespeichert.
 */
class ChatModelActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_chat_model
    override val titleRes: Int = R.string.model_choice_section_title

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val fetcher = ModelOptionsFetcher.get()
        val primaryBtn = findViewById<Button>(R.id.pickPrimaryModelButton)
        val fallbackBtn = findViewById<Button>(R.id.pickFallbackModelButton)
        val listStatus = findViewById<TextView>(R.id.modelListStatusText)

        fun labelFor(pair: Pair<String, String>?): String = if (pair == null)
            getString(R.string.primary_model_default) else "${pair.first} / ${pair.second}"

        fun refreshBadges() {
            primaryBtn.text = labelFor(config.primaryModelPair())
            fallbackBtn.text = if (config.hasFallbackModel())
                labelFor(config.fallbackModelPair())
            else getString(R.string.fallback_model_default)
        }
        refreshBadges()

        fun loadModelList() {
            val url = config.serverUrl
            val key = config.apiKey
            primaryBtn.isEnabled = false
            fallbackBtn.isEnabled = false
            listStatus.text = getString(R.string.model_list_loading)
            listStatus.visibility = TextView.VISIBLE
            thread {
                try {
                    fetcher.refresh(url, key)
                    runOnUiThread {
                        primaryBtn.isEnabled = true
                        fallbackBtn.isEnabled = true
                        val provs = fetcher.getProviders()
                        val nModels = provs?.sumOf { it.models.size } ?: 0
                        listStatus.text = getString(
                            R.string.model_list_loaded, nModels, provs?.size ?: 0
                        )
                        listStatus.setTextColor(resources.getColor(R.color.text_secondary, theme))
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        primaryBtn.isEnabled = true
                        fallbackBtn.isEnabled = true
                        listStatus.text = getString(R.string.model_list_error)
                        listStatus.setTextColor(resources.getColor(R.color.state_error, theme))
                    }
                }
            }
        }

        findViewById<Button>(R.id.refreshModelListButton).setOnClickListener { loadModelList() }

        fun openPicker(pickFallback: Boolean) {
            listStatus.text = getString(R.string.model_list_loading)
            listStatus.visibility = TextView.VISIBLE
            val url = config.serverUrl
            val key = config.apiKey
            thread {
                val provs = try {
                    fetcher.refresh(url, key)
                    fetcher.getProviders()
                } catch (e: Exception) {
                    runOnUiThread {
                        listStatus.text = getString(R.string.model_list_error)
                        listStatus.setTextColor(resources.getColor(R.color.state_error, theme))
                    }
                    null
                }
                runOnUiThread {
                    if (provs.isNullOrEmpty()) {
                        listStatus.text = getString(R.string.model_list_error)
                        listStatus.setTextColor(resources.getColor(R.color.state_error, theme))
                        return@runOnUiThread
                    }
                    val nModels = provs.sumOf { it.models.size }
                    listStatus.text = getString(R.string.model_list_loaded, nModels, provs.size)
                    listStatus.setTextColor(resources.getColor(R.color.text_secondary, theme))

                    val initial = if (pickFallback) config.fallbackModelPair()
                        else config.primaryModelPair()
                    val exclude = if (pickFallback) config.primaryModelPair() else null
                    val title = if (pickFallback) getString(R.string.pick_fallback_model)
                        else getString(R.string.pick_primary_model)

                    ModelPickerDialog(
                        context = this,
                        providers = provs,
                        initial = initial,
                        excludeKey = exclude,
                        confirmTitle = title,
                        scope = AppConfig.MODEL_SCOPE_STANDARD,
                        onSelect = { sel ->
                            if (pickFallback) {
                                config.fallbackProvider = sel?.first ?: ""
                                config.fallbackModel = sel?.second ?: ""
                            } else {
                                config.primaryProvider = sel?.first ?: ""
                                config.primaryModel = sel?.second ?: ""
                            }
                            refreshBadges()
                        }
                    ).show()
                }
            }
        }

        primaryBtn.setOnClickListener { openPicker(pickFallback = false) }
        fallbackBtn.setOnClickListener { openPicker(pickFallback = true) }

        // Fallback-Schwelle (Wert = progress + 1, 1–5), speichert sofort.
        val failThresholdSlider = findViewById<SeekBar>(R.id.primaryFailThresholdSlider)
        val failThresholdValue = findViewById<TextView>(R.id.primaryFailThresholdLabel)
        fun updateThresholdLabel() {
            failThresholdValue.text = getString(
                R.string.primary_fail_threshold_value, failThresholdSlider.progress + 1
            )
        }
        failThresholdSlider.progress = (config.primaryFailThreshold - 1).coerceIn(0, failThresholdSlider.max)
        updateThresholdLabel()
        failThresholdSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateThresholdLabel()
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                config.primaryFailThreshold = failThresholdSlider.progress + 1
            }
        })

        // Modell-Test über das gewählte primäre Modell.
        findViewById<Button>(R.id.testModelButton).setOnClickListener {
            val pair = config.primaryModelPair()
            if (pair == null) {
                Toast.makeText(this, R.string.model_test_no_selection, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val url = config.serverUrl
            val key = config.apiKey
            val testBtn = findViewById<Button>(R.id.testModelButton)
            testBtn.isEnabled = false
            listStatus.text = getString(R.string.model_test_running)
            listStatus.visibility = TextView.VISIBLE
            thread {
                try {
                    HermesApiSender.sendMessage(
                        baseUrl = url,
                        apiKey = key,
                        history = listOf("user" to "Sag nur 'ok'."),
                        modelPair = pair
                    )
                    runOnUiThread {
                        testBtn.isEnabled = true
                        listStatus.text = getString(R.string.model_test_ok)
                        listStatus.setTextColor(accentBaseColor())
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        testBtn.isEnabled = true
                        listStatus.text = getString(R.string.model_test_failed)
                        listStatus.setTextColor(resources.getColor(R.color.state_error, theme))
                    }
                }
            }
        }
    }

    private fun accentBaseColor(): Int =
        runCatching { resources.getColor(config.accentColor().base, theme) }
            .getOrElse { 0xFF000000.toInt() }
}