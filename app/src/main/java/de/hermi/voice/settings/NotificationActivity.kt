package de.hermi.voice.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import de.hermi.voice.R
import de.hermi.voice.config.AppConfig

/**
 * Zeitfenster (First-Token & Stream) plus Benachrichtigung bei fertiger Antwort.
 * Beim Aktivieren der Benachrichtigung wird auf Android 13+ ggf. die
 * Benachrichtigungs-Berechtigung angefragt.
 */
class NotificationActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_notification
    override val titleRes: Int = R.string.notification_section_title

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val firstTokenSlider = findViewById<SeekBar>(R.id.firstTokenTimeoutSlider)
        val firstTokenValue = findViewById<TextView>(R.id.firstTokenTimeoutValue)
        val streamSlider = findViewById<SeekBar>(R.id.streamTimeoutSlider)
        val streamValue = findViewById<TextView>(R.id.streamTimeoutValue)

        fun secondsForProgress(progress: Int) =
            progress + AppConfig.MIN_FIRST_TOKEN_TIMEOUT_SEC
        fun updateFirstTokenLabel() {
            firstTokenValue.text = getString(
                R.string.first_token_timeout_value,
                secondsForProgress(firstTokenSlider.progress)
            )
        }
        fun minutesForProgress(progress: Int) =
            progress + AppConfig.MIN_STREAM_TIMEOUT_MIN
        fun updateStreamLabel() {
            streamValue.text = getString(
                R.string.stream_timeout_value,
                minutesForProgress(streamSlider.progress)
            )
        }

        firstTokenSlider.progress = (config.firstTokenTimeoutSec - AppConfig.MIN_FIRST_TOKEN_TIMEOUT_SEC)
            .coerceIn(0, firstTokenSlider.max)
        streamSlider.progress = (config.streamTimeoutMin - AppConfig.MIN_STREAM_TIMEOUT_MIN)
            .coerceIn(0, streamSlider.max)
        updateFirstTokenLabel()
        updateStreamLabel()

        fun attach(slider: SeekBar, update: () -> Unit, save: (SeekBar) -> Unit) {
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = update()
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) = save(slider)
            })
        }
        attach(firstTokenSlider, ::updateFirstTokenLabel) {
            config.firstTokenTimeoutSec = secondsForProgress(it.progress)
        }
        attach(streamSlider, ::updateStreamLabel) {
            config.streamTimeoutMin = minutesForProgress(it.progress)
        }

        findViewById<Button>(R.id.resetTimeframeButton).setOnClickListener {
            config.firstTokenTimeoutSec = AppConfig.DEFAULT_FIRST_TOKEN_TIMEOUT_SEC
            config.streamTimeoutMin = AppConfig.DEFAULT_STREAM_TIMEOUT_MIN
            firstTokenSlider.progress =
                AppConfig.DEFAULT_FIRST_TOKEN_TIMEOUT_SEC - AppConfig.MIN_FIRST_TOKEN_TIMEOUT_SEC
            streamSlider.progress =
                AppConfig.DEFAULT_STREAM_TIMEOUT_MIN - AppConfig.MIN_STREAM_TIMEOUT_MIN
            updateFirstTokenLabel()
            updateStreamLabel()
            Toast.makeText(this, R.string.reset_defaults_done, Toast.LENGTH_SHORT).show()
        }

        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.notifyAnswerSwitch).apply {
            isChecked = config.notifyOnAnswer
            setOnCheckedChangeListener { _, checked ->
                config.notifyOnAnswer = checked
                if (checked && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this@NotificationActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}