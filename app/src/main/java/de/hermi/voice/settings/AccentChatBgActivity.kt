package de.hermi.voice.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import de.hermi.voice.R
import de.hermi.voice.AppLocale
import de.hermi.voice.config.AccentColor
import de.hermi.voice.config.ChatBackground

/**
 * Akzentfarbe & Chat-Hintergrund (Typ, Verlauf, eigenes Bild).
 * Alle Auswahlen werden sofort gespeichert.
 */
class AccentChatBgActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_accent_chatbg
    override val titleRes: Int = R.string.accent_chatbg_section_title

    private val chatBgImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            persistChatBackgroundImage(uri)
            refreshChatBackgroundUi()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.chat_bg_image_button, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupAccentPalette()
        setupChatBackground()
    }

    // ── Akzentfarbe ────────────────────────────────────────────────────────
    private fun setupAccentPalette() {
        val container = findViewById<LinearLayout>(R.id.accentPalette) ?: return
        container.removeAllViews()
        val current = config.accentColor()
        val density = resources.displayMetrics.density

        for (color in AccentColor.entries) {
            val size = (44f * density + 0.5f).toInt()
            val ring = FrameLayout(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(0, 0, (10f * density + 0.5f).toInt(), 0)
            ring.layoutParams = lp

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                cornerRadius = size / 2f
                setColor(resources.getColor(color.base, theme))
                if (color == current) {
                    setStroke((3f * density + 0.5f).toInt(),
                        runCatching { resources.getColor(color.base, theme) }.getOrElse { 0xFF000000.toInt() })
                }
            }
            ring.background = bg
            ring.isClickable = true
            ring.contentDescription = color.name.replace('_', ' ').lowercase(AppLocale.effectiveLocale(this))
            if (color == current) {
                val check = TextView(this).apply {
                    text = "\u2713"
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setShadowLayer(3f, 0f, 0f, 0x99000000.toInt())
                    includeFontPadding = false
                }
                ring.addView(check, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT))
            }
            ring.setOnClickListener {
                config.accentColorName = color.name
                setupAccentPalette()
                Toast.makeText(this, R.string.accent_color_set, Toast.LENGTH_SHORT).show()
            }
            container.addView(ring)
        }
    }

    // ── Chat-Hintergrund ───────────────────────────────────────────────────
    private fun setupChatBackground() {
        val radio = findViewById<RadioGroup>(R.id.chatBgRadioGroup)
        val gradientPalette = findViewById<LinearLayout>(R.id.chatGradientPalette)
        val gradientLabel = findViewById<TextView>(R.id.chatGradientLabel)
        val imageRow = findViewById<LinearLayout>(R.id.chatBgImageRow)

        radio.setOnCheckedChangeListener { _, checkedId ->
            config.chatBackgroundName = when (checkedId) {
                R.id.chatBgGradient -> ChatBackground.GRADIENT.name
                R.id.chatBgImage -> ChatBackground.IMAGE.name
                else -> ChatBackground.DEFAULT.name
            }
            refreshChatBackgroundUi()
        }
        findViewById<Button>(R.id.chatBgPickImageButton).setOnClickListener {
            chatBgImageLauncher.launch("image/*")
        }

        gradientPalette.removeAllViews()
        val currentGrad = config.chatGradientId
        val density = resources.displayMetrics.density
        for ((id, res) in config.chatGradients()) {
            val size = (44f * density + 0.5f).toInt()
            val sw = FrameLayout(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(0, 0, (10f * density + 0.5f).toInt(), 0)
            sw.layoutParams = lp
            sw.background = ContextCompat.getDrawable(this, res)
            sw.isClickable = true
            if (id == currentGrad) {
                val check = TextView(this).apply {
                    text = "\u2713"
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setShadowLayer(3f, 0f, 0f, 0x99000000.toInt())
                    includeFontPadding = false
                }
                sw.addView(check, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT))
            }
            sw.setOnClickListener {
                config.chatGradientId = id
                setupChatBackground()
            }
            gradientPalette.addView(sw)
        }

        radio.check(
            when (config.chatBackground()) {
                ChatBackground.GRADIENT -> R.id.chatBgGradient
                ChatBackground.IMAGE -> R.id.chatBgImage
                else -> R.id.chatBgDefault
            }
        )
        refreshChatBackgroundUi()
        gradientLabel.visibility = if (config.chatBackground() == ChatBackground.GRADIENT)
            View.VISIBLE else View.GONE
        gradientPalette.visibility = gradientLabel.visibility
        imageRow.visibility = if (config.chatBackground() == ChatBackground.IMAGE)
            View.VISIBLE else View.GONE
    }

    private fun refreshChatBackgroundUi() {
        val type = config.chatBackground()
        findViewById<LinearLayout>(R.id.chatGradientPalette).visibility =
            if (type == ChatBackground.GRADIENT) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.chatGradientLabel).visibility =
            if (type == ChatBackground.GRADIENT) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.chatBgImageRow).visibility =
            if (type == ChatBackground.IMAGE) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.chatBgImageLabel).text =
            if (config.chatImagePath.isBlank()) getString(R.string.chat_bg_image_none)
            else getString(R.string.chat_bg_image_chosen)
    }

    private fun persistChatBackgroundImage(uri: Uri) {
        val target = config.chatBackgroundImageFile()
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("No input stream")
        config.chatImagePath = target.absolutePath
        config.chatBackgroundName = ChatBackground.IMAGE.name
    }
}