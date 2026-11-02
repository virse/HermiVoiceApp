package de.hermi.voice.settings

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import de.hermi.voice.R
import de.hermi.voice.AppLocale
import de.hermi.voice.config.AppConfig

/**
 * Aufnahme-Feintuning: 6 Regler + Reset. Alle Werte speichern beim Loslassen
 * (onStopTrackingTouch), damit nicht bei jedem Ziehen geschrieben wird.
 */
class TuningActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_tuning
    override val titleRes: Int = R.string.tuning_section_title

    private lateinit var micGainSlider: SeekBar
    private lateinit var micGainValue: TextView
    private lateinit var tailMinSlider: SeekBar
    private lateinit var tailMinValue: TextView
    private lateinit var silenceBlocksSlider: SeekBar
    private lateinit var silenceBlocksValue: TextView
    private lateinit var tailMaxSlider: SeekBar
    private lateinit var tailMaxValue: TextView
    private lateinit var silenceFactorSlider: SeekBar
    private lateinit var silenceFactorValue: TextView
    private lateinit var silenceFloorSlider: SeekBar
    private lateinit var silenceFloorValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        micGainSlider = findViewById(R.id.micGainSlider)
        micGainValue = findViewById(R.id.micGainValue)
        tailMinSlider = findViewById(R.id.tailMinSlider)
        tailMinValue = findViewById(R.id.tailMinValue)
        silenceBlocksSlider = findViewById(R.id.silenceBlocksSlider)
        silenceBlocksValue = findViewById(R.id.silenceBlocksValue)
        tailMaxSlider = findViewById(R.id.tailMaxSlider)
        tailMaxValue = findViewById(R.id.tailMaxValue)
        silenceFactorSlider = findViewById(R.id.silenceFactorSlider)
        silenceFactorValue = findViewById(R.id.silenceFactorValue)
        silenceFloorSlider = findViewById(R.id.silenceFloorSlider)
        silenceFloorValue = findViewById(R.id.silenceFloorValue)

        fun gainForProgress(progress: Int): Float = 1.0f + progress / 10f
        fun updateGainLabel() {
            val g = gainForProgress(micGainSlider.progress)
            micGainValue.text = getString(
                R.string.mic_sensitivity_value,
                String.format(AppLocale.effectiveLocale(this), "%.1f", g)
            )
        }
        fun updateTailMinLabel() {
            tailMinValue.text = getString(
                R.string.tail_min_value, tailMinSlider.progress * 100
            )
        }
        fun updateSilenceBlocksLabel() {
            val blocks = silenceBlocksSlider.progress
            silenceBlocksValue.text = getString(
                R.string.silence_blocks_value, blocks, blocks * 100
            )
        }
        fun updateTailMaxLabel() {
            tailMaxValue.text = getString(R.string.tail_max_value, tailMaxSlider.progress * 100)
        }
        fun factorForProgress(progress: Int): Float = (progress + 5) / 100f
        fun updateSilenceFactorLabel() {
            val f = factorForProgress(silenceFactorSlider.progress)
            silenceFactorValue.text = getString(
                R.string.silence_factor_value,
                String.format(AppLocale.effectiveLocale(this), "%.1f", f * 100)
            )
        }
        fun floorForProgress(progress: Int): Float = (progress + 1) / 1000f
        fun updateSilenceFloorLabel() {
            val fl = floorForProgress(silenceFloorSlider.progress)
            silenceFloorValue.text = getString(
                R.string.silence_floor_value,
                String.format(AppLocale.effectiveLocale(this), "%.3f", fl)
            )
        }

        fun setParams(gain: Float, minTail: Int, silence: Int, maxTail: Int, factor: Float, floor: Float) {
            micGainSlider.progress = ((gain - 1f) * 10f).toInt().coerceIn(0, micGainSlider.max)
            tailMinSlider.progress = minTail.coerceIn(0, tailMinSlider.max)
            silenceBlocksSlider.progress = silence.coerceIn(0, silenceBlocksSlider.max)
            tailMaxSlider.progress = maxTail.coerceIn(0, tailMaxSlider.max)
            silenceFactorSlider.progress = ((factor * 100).toInt() - 5).coerceIn(0, silenceFactorSlider.max)
            silenceFloorSlider.progress = ((floor * 1000).toInt() - 1).coerceIn(0, silenceFloorSlider.max)
            updateGainLabel()
            updateTailMinLabel()
            updateSilenceBlocksLabel()
            updateTailMaxLabel()
            updateSilenceFactorLabel()
            updateSilenceFloorLabel()
        }

        setParams(
            config.micGain,
            config.minTailBlocks,
            config.silenceBlocks,
            config.maxTailBlocks,
            config.silenceFactor,
            config.silenceFloorAbs
        )

        // commonListener: aktualisiert das Label live und speichert beim Loslassen.
        fun attach(slider: SeekBar, update: () -> Unit, save: (SeekBar) -> Unit) {
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = update()
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) = save(slider)
            })
        }

        attach(micGainSlider, ::updateGainLabel) {
            config.micGain = gainForProgress(it.progress)
        }
        attach(tailMinSlider, ::updateTailMinLabel) {
            config.minTailBlocks = it.progress
        }
        attach(silenceBlocksSlider, ::updateSilenceBlocksLabel) {
            config.silenceBlocks = it.progress
        }
        attach(tailMaxSlider, ::updateTailMaxLabel) {
            config.maxTailBlocks = it.progress
        }
        attach(silenceFactorSlider, ::updateSilenceFactorLabel) {
            config.silenceFactor = factorForProgress(it.progress)
        }
        attach(silenceFloorSlider, ::updateSilenceFloorLabel) {
            config.silenceFloorAbs = floorForProgress(it.progress)
        }

        findViewById<Button>(R.id.resetDefaultsButton).setOnClickListener {
            config.resetRecordingDefaults()
            setParams(
                AppConfig.DEFAULT_MIC_GAIN,
                AppConfig.DEFAULT_MIN_TAIL,
                AppConfig.DEFAULT_SILENCE_BLOCKS,
                AppConfig.DEFAULT_MAX_TAIL,
                AppConfig.DEFAULT_SILENCE_FACTOR,
                AppConfig.DEFAULT_SILENCE_FLOOR
            )
            Toast.makeText(this, R.string.reset_defaults_done, Toast.LENGTH_SHORT).show()
        }
    }
}