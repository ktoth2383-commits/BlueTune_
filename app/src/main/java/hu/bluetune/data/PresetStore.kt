package hu.bluetune.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import hu.bluetune.model.EqSettings
import hu.bluetune.model.Preset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Saját presetek tárolása, export/import JSON-ben. */
object PresetStore {
    private lateinit var sp: SharedPreferences
    private val _custom = MutableStateFlow<List<Preset>>(emptyList())
    val custom: StateFlow<List<Preset>> = _custom.asStateFlow()

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("presets", Context.MODE_PRIVATE)
        _custom.value = parse(sp.getString("custom", "[]") ?: "[]")
    }

    fun save(name: String, settings: EqSettings) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        val p = Preset(clean, settings.copy(enabled = true, presetName = clean))
        persist(_custom.value.filterNot { it.name == clean } + p)
    }

    fun delete(name: String) = persist(_custom.value.filterNot { it.name == name })

    fun exportJson(): String = toJson(_custom.value).toString(2)

    /** Hozzáfűzi (név szerint felülírja) a presetek listáját. Visszaadja a beolvasott darabszámot. */
    fun importJson(json: String): Int {
        val incoming = parse(json.trim())
        if (incoming.isEmpty()) return 0
        val names = incoming.map { it.name }.toSet()
        persist(_custom.value.filterNot { it.name in names } + incoming)
        return incoming.size
    }

    private fun persist(list: List<Preset>) {
        _custom.value = list
        sp.edit { putString("custom", toJson(list).toString()) }
    }

    private fun toJson(list: List<Preset>) = JSONArray().apply {
        list.forEach { p -> put(JSONObject().put("name", p.name).put("settings", p.settings.toJson())) }
    }

    private fun parse(json: String): List<Preset> = try {
        val arr = if (json.startsWith("{")) JSONArray().put(JSONObject(json)) else JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim()
            val s = o.optJSONObject("settings") ?: return@mapNotNull null
            if (name.isEmpty()) null else Preset(name.take(40), EqSettings.fromJson(s).copy(presetName = name))
        }
    } catch (_: Exception) {
        emptyList()
    }
}
