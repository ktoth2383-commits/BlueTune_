package hu.bluetune.audio

import android.os.Handler
import android.os.Looper
import hu.bluetune.bt.BluetoothMonitor
import hu.bluetune.data.AppPrefs
import hu.bluetune.data.DeviceProfileStore
import hu.bluetune.model.EqSettings
import hu.bluetune.model.Preset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A hangmotor központja: tárolja az aktuális beállítást, kezeli az effektláncokat
 * (globális + lejátszónkénti sessionök), és eszközváltáskor betölti a profilt.
 */
object AudioEngine {
    const val GLOBAL_SESSION = 0

    data class Status(
        val running: Boolean = false,
        val backend: String = "—",
        val sessions: List<String> = emptyList(),
        val errors: List<String> = emptyList(),
        val hasControl: Boolean = true,
    )

    private val main = Handler(Looper.getMainLooper())
    private val chains = LinkedHashMap<Int, EffectChain>()
    private val knownSessions = LinkedHashMap<Int, String>()
    private var running = false

    private val _settings = MutableStateFlow(EqSettings())
    val settings: StateFlow<EqSettings> = _settings.asStateFlow()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var pendingPersist = false
    private var pendingDevice: BluetoothMonitor.BtDevice? = null
    private val persistRunnable = Runnable { persistNow() }

    fun init() {
        _settings.value = AppPrefs.currentSettings
    }

    // ---------------------------------------------------------------- életciklus

    @Synchronized
    fun start() {
        running = true
        if (AppPrefs.globalMode) ensureChain(GLOBAL_SESSION)
        if (AppPrefs.sessionMode) knownSessions.keys.toList().forEach { ensureChain(it) }
        applyAll()
    }

    @Synchronized
    fun stop() {
        chains.values.forEach { it.release() }
        chains.clear()
        running = false
        publish()
    }

    /** Újraépíti az effekteket (pl. mód váltás után, vagy ha más app elvette az irányítást). */
    @Synchronized
    fun rebuild() {
        if (!running) return
        stop()
        start()
    }

    fun isRunning() = running

    @Synchronized
    fun openSession(sessionId: Int, pkg: String?) {
        if (sessionId == GLOBAL_SESSION) return
        knownSessions[sessionId] = pkg ?: "ismeretlen app"
        if (running && AppPrefs.sessionMode) {
            ensureChain(sessionId)?.apply(_settings.value)
        }
        publish()
    }

    @Synchronized
    fun closeSession(sessionId: Int) {
        knownSessions.remove(sessionId)
        chains.remove(sessionId)?.release()
        publish()
    }

    // ---------------------------------------------------------------- beállítások

    fun update(transform: (EqSettings) -> EqSettings) {
        val old = _settings.value
        val new = transform(old)
        if (new == old) return
        _settings.value = new
        applyAll()
        schedulePersist()
    }

    fun applyPreset(p: Preset) = update { it.withSound(p.settings, p.name) }

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }

    // ---------------------------------------------------------------- eszközváltás

    /** A BluetoothMonitor hívja, ha megváltozott az aktív zenei kimenet. */
    fun onActiveDeviceChanged(dev: BluetoothMonitor.BtDevice?) {
        flushPersist()
        if (dev == null) return
        val profile = DeviceProfileStore.get(dev.key)
        if (AppPrefs.autoProfile && profile != null) {
            val cur = _settings.value
            _settings.value = profile.settings.copy(enabled = cur.enabled)
            AppPrefs.currentSettings = _settings.value
            applyAll()
            if (AppPrefs.restoreVolume) profile.volume?.let { VolumeControl.setRaw(it, saveToProfile = false) }
            DeviceProfileStore.upsert(dev.key, dev.name)
        } else {
            DeviceProfileStore.upsert(
                dev.key, dev.name,
                settings = profile?.settings ?: _settings.value,
                volume = profile?.volume ?: VolumeControl.volume.value,
            )
        }
    }

    // ---------------------------------------------------------------- belső

    @Synchronized
    private fun ensureChain(sessionId: Int): EffectChain? {
        chains[sessionId]?.let { return it }
        val chain = EffectChain(sessionId, AppPrefs.preferDynamics)
        if (chain.isEmpty && sessionId != GLOBAL_SESSION) {
            // Elavult / már lezárt session – eldobjuk.
            chain.release()
            knownSessions.remove(sessionId)
            return null
        }
        chains[sessionId] = chain
        return chain
    }

    @Synchronized
    private fun applyAll() {
        val s = _settings.value
        chains.values.forEach { it.apply(s) }
        publish()
    }

    private fun publish() {
        val backend = chains[GLOBAL_SESSION]?.backend ?: chains.values.firstOrNull()?.backend ?: "—"
        _status.value = Status(
            running = running,
            backend = if (running) backend else "Leállítva",
            sessions = chains.keys.map { id ->
                if (id == GLOBAL_SESSION) "Globális kimenet (session 0)"
                else "${knownSessions[id] ?: "app"} (session $id)"
            },
            errors = chains.values.flatMap { it.errors }.distinct().take(10),
            hasControl = chains.values.all { it.hasControl() },
        )
    }

    private fun schedulePersist() {
        pendingDevice = BluetoothMonitor.active.value
        pendingPersist = true
        main.removeCallbacks(persistRunnable)
        main.postDelayed(persistRunnable, 400)
    }

    private fun flushPersist() {
        if (pendingPersist) {
            main.removeCallbacks(persistRunnable)
            persistNow()
        }
    }

    private fun persistNow() {
        pendingPersist = false
        val s = _settings.value
        AppPrefs.currentSettings = s
        val dev = pendingDevice
        if (AppPrefs.autoProfile && dev != null) {
            DeviceProfileStore.upsert(dev.key, dev.name, settings = s)
        }
    }
}
