package hu.bluetune.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import hu.bluetune.model.EqSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Egy Bluetooth eszközhöz mentett hangzás és hangerő. */
data class DeviceProfile(
    val key: String,
    val name: String,
    val settings: EqSettings,
    val volume: Int?,
    val lastSeen: Long,
)

object DeviceProfileStore {
    private lateinit var sp: SharedPreferences
    private val _profiles = MutableStateFlow<List<DeviceProfile>>(emptyList())
    val profiles: StateFlow<List<DeviceProfile>> = _profiles.asStateFlow()

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("device_profiles", Context.MODE_PRIVATE)
        _profiles.value = parse(sp.getString("all", "[]") ?: "[]")
    }

    fun get(key: String): DeviceProfile? = _profiles.value.firstOrNull { it.key == key }

    /**
     * Létrehozza vagy frissíti a profilt. A null paraméterek a meglévő értéket tartják meg.
     */
    fun upsert(key: String, name: String, settings: EqSettings? = null, volume: Int? = null) {
        val old = get(key)
        val p = DeviceProfile(
            key = key,
            name = name,
            settings = settings ?: old?.settings ?: EqSettings(),
            volume = volume ?: old?.volume,
            lastSeen = System.currentTimeMillis(),
        )
        persist(listOf(p) + _profiles.value.filterNot { it.key == key })
    }

    fun delete(key: String) = persist(_profiles.value.filterNot { it.key == key })

    fun exportJson(): String = toJson(_profiles.value).toString()

    fun importJson(json: String): Int {
        val incoming = parse(json)
        if (incoming.isEmpty()) return 0
        val keys = incoming.map { it.key }.toSet()
        persist(incoming + _profiles.value.filterNot { it.key in keys })
        return incoming.size
    }

    private fun persist(list: List<DeviceProfile>) {
        val sorted = list.sortedByDescending { it.lastSeen }
        _profiles.value = sorted
        sp.edit { putString("all", toJson(sorted).toString()) }
    }

    private fun toJson(list: List<DeviceProfile>) = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().apply {
                put("key", p.key)
                put("name", p.name)
                put("settings", p.settings.toJson())
                p.volume?.let { put("volume", it) }
                put("lastSeen", p.lastSeen)
            })
        }
    }

    private fun parse(json: String): List<DeviceProfile> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("key")
            if (key.isEmpty()) return@mapNotNull null
            DeviceProfile(
                key = key,
                name = o.optString("name", "Eszköz"),
                settings = o.optJSONObject("settings")?.let { EqSettings.fromJson(it) } ?: EqSettings(),
                volume = if (o.has("volume")) o.optInt("volume") else null,
                lastSeen = o.optLong("lastSeen", 0L),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
