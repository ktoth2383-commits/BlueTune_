package hu.bluetune.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import hu.bluetune.bt.BluetoothMonitor
import hu.bluetune.data.AppPrefs
import hu.bluetune.data.DeviceProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil

/** Médiahangerő olvasása/állítása + hallásvédelmi korlát + eszközönkénti mentés. */
object VolumeControl {
    private lateinit var am: AudioManager

    private val _volume = MutableStateFlow(0)
    val volume: StateFlow<Int> = _volume.asStateFlow()

    private val _max = MutableStateFlow(15)
    val max: StateFlow<Int> = _max.asStateFlow()

    fun init(ctx: Context) {
        am = ctx.getSystemService(AudioManager::class.java)
        refresh()
        val main = Handler(Looper.getMainLooper())
        ctx.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true,
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = onExternalChange()
            },
        )
        ContextCompat.registerReceiver(
            ctx,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = onExternalChange()
            },
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun refresh() {
        if (!::am.isInitialized) return
        _max.value = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        _volume.value = am.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    /** A hallásvédelmi korlát lépésben. */
    fun capSteps(): Int {
        val pct = AppPrefs.maxVolumePercent
        return if (pct >= 100) _max.value else ceil(_max.value * pct / 100.0).toInt()
    }

    /** Felhasználói állítás (csúszka) – eszközprofilba is menti. */
    fun set(v: Int) = setRaw(v, saveToProfile = true)

    fun setRaw(v: Int, saveToProfile: Boolean) {
        val target = v.coerceIn(0, capSteps())
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (_: SecurityException) {
            // Ne zavarj mód – a rendszer nem engedi
        }
        refresh()
        if (saveToProfile && AppPrefs.restoreVolume) {
            BluetoothMonitor.active.value?.let { DeviceProfileStore.upsert(it.key, it.name, volume = _volume.value) }
        }
    }

    fun enforceCap() {
        refresh()
        if (AppPrefs.maxVolumePercent < 100 && _volume.value > capSteps()) {
            setRaw(capSteps(), saveToProfile = false)
        }
    }

    private fun onExternalChange() {
        val before = _volume.value
        refresh()
        if (_volume.value != before) enforceCap()
    }
}
