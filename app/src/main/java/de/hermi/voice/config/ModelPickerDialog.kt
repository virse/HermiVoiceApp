package de.hermi.voice.config

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import de.hermi.voice.R

/**
 * Durchsuchbarer Modell-Auswahldialog, nach Provider gruppiert.
 *
 * Verhalten (Umsetzungs-Entscheidungen Q4/Q5/Q6 + Dedup + Favoriten):
 *  - Oben eine Sektion "Modellvorgabe des Gateways" mit der Zeile [confirmTitle] →
 *    Reset auf den Gateway-Default (kein Modell-Paarkonfig-Override).
 *  - Darunter, sofern vorhanden, die Gruppe "Favoriten" (ganz oben, alphabetisch
 *    nach Provider → Modellname sortiert). Markierte Modelle erscheinen ausschließlich
 *    dort, nicht nochmals in ihrer Provider-Sektion (keine Duplikate).
 *  - Danach Modelle nach Provider gruppiert; pro Provider ein farbiger Header
 *    "Name (slug)" und darunter je Modell auswählbare Radio-Zeilen.
 *  - Jede Modellzeile: [Stern-ImageButton (ganz links)] [RadioButton] [Modellname].
 *    Stern gefüllt = Favorit (Akzentfarbe), outline = kein Favorit; ein Klick toggelt
 *    sofort und verschiebt die Zeile live zwischen Favoriten-Gruppe und Provider-Sektion.
 *  - Suchfeld blendet das passende Set live ein (TextWatcher, Filter über
 *    Modell-ID + Providername + Slug).
 *  - `excludeKey` = Pair(provider, model), das ausgeblendet wird (Dedup beim
 *    Fallback-Picker: Fallback darf nie identisch zum primären Modell sein).
 *  - Ein gespeichertes Modell, das nicht mehr in der Liste auftaucht, wird über
 *    eine Warnzeile "(mit Vorbehalt)" als noch gewählt gekennzeichnet (Q6: Wert
 *    wird behalten und weitergesendet, nicht still zurückgesetzt).
 *  - Die aktuelle Auswahl wird durch Häkchen-Symbol "✓" markiert.
 *  - `scope` bestimmt, welcher Favoriten-Satz gilt (MODEL_SCOPE_STANDARD oder bot.id);
 *    Primär- und Fallback-Auswahl desselben Bots teilen sich denselben Satz.
 */
class ModelPickerDialog(
    private val context: Context,
    private val providers: List<ModelOptionsFetcher.Provider>,
    private val initial: Pair<String, String>?,
    private val excludeKey: Pair<String, String>? = null,
    private val confirmTitle: String,
    private val scope: String = AppConfig.MODEL_SCOPE_STANDARD,
    private val onSelect: (Pair<String, String>?) -> Unit
) {

    private val config = AppConfig(context)

    /** Aktuell gewählte Auswahl; null = Modellvorgabe des Gateways (kein Override). */
    private var current: Pair<String, String>? = initial

    fun show() {
        val builder = AlertDialog.Builder(context)
        builder.setTitle(confirmTitle)
        builder.setNegativeButton(R.string.cancel, null)

        val search = EditText(context)
        search.hint = context.getString(R.string.model_search_hint)
        search.setSingleLine(true)
        search.setPadding(d(16), d(8), d(16), d(8))
        search.textSize = 15f

        val list = LinearLayout(context)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(d(8), d(4), d(8), d(8))

        val scroll = ScrollView(context)
        scroll.isFillViewport = true
        scroll.addView(list)
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, d(460))

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.addView(search)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, d(460)))

        builder.setView(root)

        // "Standard" + Favoriten + Provider-Sektionen + Warnung rendern
        fun render() {
            list.removeAllViews()
            val filter = search.text.toString().trim()

            // Q6: gespeicherte Auswahl nicht (mehr) in der Liste → Warnhinweis
            val stillListed = current == null || providers.any { p ->
                p.slug == current!!.first && current!!.second in p.models
            }
            if (current != null && !stillListed) {
                val warn = TextView(context)
                warn.text = context.getString(R.string.model_not_listed, current!!.second)
                warn.setTextColor(0xFFFFB300.toInt())
                warn.setPadding(d(16), d(8), d(16), d(6))
                warn.textSize = 13f
                list.addView(warn)
            }

            addStandardSection(list) { render() }

            // Favoriten-Gruppe (nur Modelle, die aktuell im gefilterten/exkludierten Set liegen)
            data class FEntry(val provider: String, val providerName: String, val model: String)
            val favs = mutableListOf<FEntry>()
            for (p in providers) {
                for (m in p.models) {
                    if (excludeKey == Pair(p.slug, m)) continue
                    if (!filter.isEmpty() &&
                        !"$m ${p.name} ${p.slug}".lowercase().contains(filter.lowercase())) continue
                    if (config.isModelFavorite(scope, p.slug, m)) {
                        favs.add(FEntry(p.slug, p.name, m))
                    }
                }
            }
            favs.sortWith(compareBy({ it.provider.lowercase() }, { it.model.lowercase() }))

            var hasContent = false
            if (favs.isNotEmpty()) {
                hasContent = true
                list.addView(sectionHeader("Favoriten"))
                for (f in favs) {
                    list.addView(modelRow(Pair(f.provider, f.model), f.model) { render() })
                }
            }

            // Provider-Sektionen (Favoriten nicht doppelt anzeigen)
            val sorted = providers.sortedBy { it.name.lowercase() }
            for (p in sorted) {
                val filtered = p.models
                    .filter { m -> excludeKey != Pair(p.slug, m) }
                    .filter { m -> !config.isModelFavorite(scope, p.slug, m) }
                    .filter { m ->
                        filter.isEmpty() ||
                            "$m ${p.name} ${p.slug}".lowercase().contains(filter.lowercase())
                    }
                if (filtered.isEmpty()) continue
                hasContent = true
                list.addView(sectionHeader("${p.name} (${p.slug})"))
                for (m in filtered) {
                    val key = Pair(p.slug, m)
                    list.addView(modelRow(key, m) { render() })
                }
            }

            if (!hasContent) {
                val empty = TextView(context)
                empty.text = context.getString(R.string.model_none_found)
                empty.setTextColor(0xFF9E9E9E.toInt())
                empty.gravity = Gravity.CENTER
                empty.setPadding(d(16), d(24), d(16), d(24))
                list.addView(empty)
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })

        builder.setPositiveButton("Übernehmen") { _, _ -> onSelect(current) }
        render()
        builder.show()
    }

    private fun addStandardSection(list: LinearLayout, render: () -> Unit) {
        list.addView(sectionHeader(context.getString(R.string.model_default_gateway_label)))
        val rb = RadioButton(context)
        rb.text = confirmTitle
        rb.textSize = 15f
        rb.isChecked = current == null
        if (current == null) rb.setTextColor(StdHl) else rb.setTextColor(TxtDim)
        rb.setOnClickListener {
            current = null
            render()
        }
        list.addView(rb)
    }

    /** Eine Modellzeile: [Stern (ganz links)] [RadioButton] [Modellname]. */
    private fun modelRow(key: Pair<String, String>, model: String, render: () -> Unit): View {
        val selected = current == key
        val fav = config.isModelFavorite(scope, key.first, key.second)

        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, 0, d(8), 0)

        // Stern ganz links: gefüllt = Favorit (Akzentfarbe), outline = kein Favorit
        val star = ImageButton(context)
        star.setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
        star.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        star.imageTintList = ColorStateList.valueOf(if (fav) Acc else TxtDim)
        star.setPadding(d(14), d(8), d(4), d(8))
        star.contentDescription = if (fav) "Als Favorit entfernen" else "Als Favorit markieren"
        star.setOnClickListener {
            config.toggleModelFavorite(scope, key.first, key.second)
            render()
        }
        row.addView(star)

        val rb = RadioButton(context)
        rb.text = if (selected) "$model  ✓" else model
        rb.textSize = 15f
        rb.isChecked = selected
        if (selected) rb.setTextColor(Acc) else rb.setTextColor(TxtDim)
        rb.setOnClickListener {
            current = key
            render()
        }
        row.addView(rb, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun sectionHeader(title: String): TextView {
        val t = TextView(context)
        t.text = title
        t.setTextColor(AccBlue)
        t.setPadding(d(16), d(10), d(16), d(4))
        t.gravity = Gravity.START
        t.typeface = Typeface.DEFAULT_BOLD
        t.textSize = 14f
        return t
    }

    private fun d(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        val Acc = android.graphics.Color.parseColor("#4CAF50")
        val AccBlue = android.graphics.Color.parseColor("#4FC3F7")
        val StdHl = android.graphics.Color.parseColor("#FFD54F")
        val TxtDim = android.graphics.Color.parseColor("#E0E0E0")
    }
}
