package de.hermi.voice.settings

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import de.hermi.voice.R
import java.io.File

/**
 * Avatare: eigene Bilder für "Du" und "Hermes". Wird sofort gespeichert.
 */
class AvatarsActivity : SettingsSectionActivity() {

    override val layoutRes: Int = R.layout.activity_avatars
    override val titleRes: Int = R.string.avatar_section_title

    private val userAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            persistAvatarImage(uri, config.userAvatarFile()) {
                config.userAvatarPath = it
            }
            refreshAvatarUi()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.avatar_user_label, Toast.LENGTH_LONG).show()
        }
    }

    private val botAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            persistAvatarImage(uri, config.botAvatarFile()) {
                config.botAvatarPath = it
            }
            refreshAvatarUi()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.avatar_bot_label, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<Button>(R.id.userAvatarPickButton).setOnClickListener {
            userAvatarLauncher.launch("image/*")
        }
        findViewById<Button>(R.id.botAvatarPickButton).setOnClickListener {
            botAvatarLauncher.launch("image/*")
        }
        findViewById<Button>(R.id.userAvatarResetButton).setOnClickListener {
            resetAvatar(config.userAvatarFile(), R.id.userAvatarStatus) {
                config.userAvatarPath = ""
            }
        }
        findViewById<Button>(R.id.botAvatarResetButton).setOnClickListener {
            resetAvatar(config.botAvatarFile(), R.id.botAvatarStatus) {
                config.botAvatarPath = ""
            }
        }
        initNameFields()
        refreshAvatarUi()
    }

    /** Namensfelder: Werte laden, Autosave beim Verlassen (wie Zugangsdaten),
     *  Reset-Buttons analog zum Avatar-Reset. */
    private fun initNameFields() {
        val userNameInput = findViewById<EditText>(R.id.userNameInput)
        val botNameInput = findViewById<EditText>(R.id.botNameInput)
        userNameInput.setText(config.userName)
        botNameInput.setText(config.botName)

        fun refreshNameUi() {
            findViewById<Button>(R.id.userNameResetButton).visibility =
                if (userNameInput.text.isNullOrBlank()) View.GONE else View.VISIBLE
            findViewById<Button>(R.id.botNameResetButton).visibility =
                if (botNameInput.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        // Beim Verlassen eines Feldes sofort speichern.
        userNameInput.onFocusChangeListener = focusSave {
            config.userName = userNameInput.text.toString().trim()
        }
        botNameInput.onFocusChangeListener = focusSave {
            config.botName = botNameInput.text.toString().trim()
        }
        // Reset-Sichtbarkeit live an den Feldinhalt koppeln.
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = refreshNameUi()
        }
        userNameInput.addTextChangedListener(watcher)
        botNameInput.addTextChangedListener(watcher)

        findViewById<Button>(R.id.userNameResetButton).setOnClickListener {
            userNameInput.text.clear()
            config.userName = ""
            refreshNameUi()
            Toast.makeText(this, R.string.avatar_name_reset_done, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.botNameResetButton).setOnClickListener {
            botNameInput.text.clear()
            config.botName = ""
            refreshNameUi()
            Toast.makeText(this, R.string.avatar_name_reset_done, Toast.LENGTH_SHORT).show()
        }
        refreshNameUi()
    }

    /** Speichert beim Verlassen des Feldes (Fokus geht ab). */
    private inline fun focusSave(crossinline save: () -> Unit): View.OnFocusChangeListener =
        View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) save()
        }

    /** Sichert auch halb getippte Werte, falls mit Fokus im Feld zurücknavigiert wird. */
    override fun onPause() {
        super.onPause()
        val userNameInput = findViewById<EditText>(R.id.userNameInput)
        val botNameInput = findViewById<EditText>(R.id.botNameInput)
        config.userName = userNameInput.text?.toString()?.trim().orEmpty()
        config.botName = botNameInput.text?.toString()?.trim().orEmpty()
    }

    private fun resetAvatar(file: File, statusId: Int, clearPath: () -> Unit) {
        file.delete()
        clearPath()
        refreshAvatarUi()
        Toast.makeText(this, R.string.avatar_reset_done, Toast.LENGTH_SHORT).show()
    }

    private fun refreshAvatarUi() {
        val userSet = config.userAvatarFile().exists() && config.userAvatarPath.isNotBlank()
        val botSet = config.botAvatarFile().exists() && config.botAvatarPath.isNotBlank()
        findViewById<TextView>(R.id.userAvatarStatus).text =
            if (userSet) getString(R.string.avatar_chosen)
            else getString(R.string.avatar_none)
        findViewById<TextView>(R.id.botAvatarStatus).text =
            if (botSet) getString(R.string.avatar_chosen)
            else getString(R.string.avatar_none)
        findViewById<Button>(R.id.userAvatarResetButton).visibility =
            if (userSet) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.botAvatarResetButton).visibility =
            if (botSet) View.VISIBLE else View.GONE
    }

    private fun persistAvatarImage(uri: Uri, target: File, setPath: (String) -> Unit) {
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("No input stream")
        setPath(target.absolutePath)
    }
}