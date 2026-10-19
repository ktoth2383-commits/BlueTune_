package hu.bluetune.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import hu.bluetune.model.EqSettings

/** Alkalmazás-beállítások (SharedPreferences). */
object AppPrefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("bluetune", Context.MODE_PRIVATE)
    }

    /** A felhasználó szeretné, ha a hangmotor fusson. */
    var serviceWanted: Boolean
        get() = sp.getBoolean("service_wanted", true)
        set(v) = sp.edit { putBoolean("service_wanted", v) }

    /** Globális mód: effekt a 0-s (teljes kimeneti) sessionre. */
    var globalMode: Boolean
        get() = sp.getBoolean("global_mode", true)
        set(v) = sp.edit { putBoolean("global_mode", v) }

    /** Lejátszó-session mód: a zenelejátszók által bejelentett sessionökre is rátesszük. */
    var sessionMode: Boolean
        get() = sp.getBoolean("session_mode", true)
        set(v) = sp.edit { putBoolean("session_mode", v) }

    /** DynamicsProcessing használata (Android 9+), különben klasszikus Equalizer. */
    var preferDynamics: Boolean
        get() = sp.getBoolean("prefer_dp", true)
        set(v) = sp.edit { putBoolean("prefer_dp", v) }

    /** Eszközönként külön hangzás automatikus mentése/betöltése. */
    var autoProfile: Boolean
        get() = sp.getBoolean("auto_profile", true)
        set(v) = sp.edit { putBoolean("auto_profile", v) }

    /** Eszközönkénti hangerő visszaállítása csatlakozáskor. */
    var restoreVolume: Boolean
        get() = sp.getBoolean("restore_volume", true)
        set(v) = sp.edit { putBoolean("restore_volume", v) }

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", true)
        set(v) = sp.edit { putBoolean("start_on_boot", v) }

    /** Hallásvédelem: maximális médiahangerő százalékban (100 = nincs korlát). */
    var maxVolumePercent: Int
        get() = sp.getInt("max_volume_pct", 100)
        set(v) = sp.edit { putInt("max_volume_pct", v.coerceIn(10, 100)) }

    var authorName: String
        get() = sp.getString("author_name", "") ?: ""
        set(v) = sp.edit { putString("author_name", v) }

    var likedCommunityIds: Set<String>
        get() = sp.getStringSet("liked_ids", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit { putStringSet("liked_ids", v) }

    var currentSettings: EqSettings
        get() = sp.getString("current_settings", null)?.let { EqSettings.fromJsonString(it) } ?: EqSettings()
        set(v) = sp.edit { putString("current_settings", v.toJson().toString()) }
}
