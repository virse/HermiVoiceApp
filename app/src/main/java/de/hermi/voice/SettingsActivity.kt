package de.hermi.voice

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import de.hermi.voice.config.AppConfig
import de.hermi.voice.settings.AccentChatBgActivity
import de.hermi.voice.settings.AppearanceActivity
import de.hermi.voice.settings.AutoSessionActivity
import de.hermi.voice.settings.AvatarsActivity
import de.hermi.voice.settings.BackupActivity
import de.hermi.voice.settings.BotsActivity
import de.hermi.voice.settings.ChatModelActivity
import de.hermi.voice.settings.CloudProvidersActivity
import de.hermi.voice.settings.CredentialsActivity
import de.hermi.voice.settings.LanguageActivity
import de.hermi.voice.settings.NotificationActivity
import de.hermi.voice.settings.SettingsSectionActivity
import de.hermi.voice.settings.SttModelActivity
import de.hermi.voice.settings.TuningActivity

/**
 * Einstellungen: Übersichts-Screen mit den Hauptkategorien. Jede Kategorie
 * verzweigt auf einen eigenen Sub-Screen (SettingsSectionActivity).
 */
class SettingsActivity : AppCompatActivity() {

    // Titelstext + Kurzbeschreibung + Ziel-Activity, in gewünschter Reihenfolge.
    private data class Category(
        val title: Int,
        val desc: Int,
        val target: Class<out SettingsSectionActivity>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AppConfig(this)
        setTheme(config.accentTheme())
        setContentView(R.layout.activity_settings)
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById<View>(android.R.id.content)
        ) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        title = getString(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val categories = listOf(
            Category(R.string.model_section_title, R.string.settings_cat_stt_desc, SttModelActivity::class.java),
            Category(R.string.language_section_title, R.string.settings_cat_language_desc, LanguageActivity::class.java),
            Category(R.string.credentials_section_title, R.string.settings_cat_credentials_desc, CredentialsActivity::class.java),
            Category(R.string.bots_section_title, R.string.settings_cat_bots_desc, BotsActivity::class.java),
            Category(R.string.model_choice_section_title, R.string.settings_cat_chatmodel_desc, ChatModelActivity::class.java),
            Category(R.string.cloud_section_title, R.string.settings_cat_cloud_desc, CloudProvidersActivity::class.java),
            Category(R.string.appearance_section_title, R.string.settings_cat_appearance_desc, AppearanceActivity::class.java),
            Category(R.string.accent_chatbg_section_title, R.string.settings_cat_accent_desc, AccentChatBgActivity::class.java),
            Category(R.string.avatar_section_title, R.string.settings_cat_avatars_desc, AvatarsActivity::class.java),
            Category(R.string.notification_section_title, R.string.settings_cat_notify_desc, NotificationActivity::class.java),
            Category(R.string.auto_session_section_title, R.string.settings_cat_autosession_desc, AutoSessionActivity::class.java),
            Category(R.string.tuning_section_title, R.string.settings_cat_tuning_desc, TuningActivity::class.java),
            Category(R.string.backup_section_title, R.string.settings_cat_backup_desc, BackupActivity::class.java)
        )

        val list = findViewById<LinearLayout>(R.id.settingsCategoryList)
        for (cat in categories) {
            val row = layoutInflater.inflate(R.layout.item_settings_category, list, false)
            row.findViewById<TextView>(R.id.categoryTitle).setText(cat.title)
            row.findViewById<TextView>(R.id.categoryDesc).setText(cat.desc)
            row.setOnClickListener {
                startActivity(Intent(this, cat.target))
            }
            list.addView(row)
            // Trennlinie unter jeder Zeile.
            val divider = View(this)
            divider.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = (resources.displayMetrics.density * 0.5f).toInt() }
            divider.setBackgroundColor(
                runCatching { resources.getColor(android.R.color.darker_gray, theme) }
                    .getOrDefault(0x33000000.toInt())
            )
            list.addView(divider)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}