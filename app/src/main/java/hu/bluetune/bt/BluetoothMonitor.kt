package hu.bluetune.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import hu.bluetune.audio.AudioEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Figyeli a csatlakoztatott Bluetooth hangkimeneteket (A2DP, LE Audio, hallókészülék),
 * kiolvassa a nevet, akkut és kodeket, és szól az AudioEngine-nek eszközváltáskor.
 */
@SuppressLint("MissingPermission")
object BluetoothMonitor {

    data class BtDevice(
        val key: String,
        val name: String,
        val typeLabel: String,
        val typeId: Int,
        val address: String,
        val battery: Int? = null,
        val codec: String? = null,
        val isMusic: Boolean = true,
    )

    // AudioDeviceInfo típusok számmal, hogy régebbi API-n is forduljon
    private const val TYPE_SCO = 7          // TYPE_BLUETOOTH_SCO
    private const val TYPE_A2DP = 8         // TYPE_BLUETOOTH_A2DP
    private const val TYPE_HEARING_AID = 23 // API 28
    private const val TYPE_BLE_HEADSET = 26 // API 31
    private const val TYPE_BLE_SPEAKER = 27 // API 31
    private const val TYPE_BLE_BROADCAST = 30 // API 33
    private val BT_TYPES = setOf(TYPE_SCO, TYPE_A2DP, TYPE_HEARING_AID, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST)

    // Rejtett, de a rendszer által küldött broadcastok
    private const val ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val EXTRA_BATTERY = "android.bluetooth.device.extra.BATTERY_LEVEL"
    private const val ACTION_CODEC = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"

    private lateinit var app: Context
    private lateinit var am: AudioManager
    private var a2dp: BluetoothProfile? = null
    private var started = false
    private val main = Handler(Looper.getMainLooper())
    private val batteryByAddress = HashMap<String, Int>()

    private val _active = MutableStateFlow<BtDevice?>(null)
    val active: StateFlow<BtDevice?> = _active.asStateFlow()

    private val _outputs = MutableStateFlow<List<BtDevice>>(emptyList())
    val outputs: StateFlow<List<BtDevice>> = _outputs.asStateFlow()

    private val _hasPermission = MutableStateFlow(false)
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val refreshRunnable = Runnable { refresh() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == ACTION_BATTERY) {
                val dev = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                val level = i.getIntExtra(EXTRA_BATTERY, -1)
                val addr = runCatching { dev?.address }.getOrNull()
                if (addr != null && level >= 0) batteryByAddress[addr.uppercase()] = level
                scheduleRefresh(100)
            } else {
                // Kodekváltás / csatlakozás – a kodek pár száz ms múlva áll be
                scheduleRefresh(800)
            }
        }
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        am = app.getSystemService(AudioManager::class.java)
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = scheduleRefresh(300)
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = scheduleRefresh(300)
        }, main)
        val filter = IntentFilter().apply {
            addAction(ACTION_BATTERY)
            addAction(ACTION_CODEC)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onPermissionChanged()
    }

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Engedély megadása után hívd – ekkor kérjük el az A2DP proxyt a kodek-infóhoz. */
    fun onPermissionChanged() {
        if (!started) return
        val ok = hasConnectPermission()
        _hasPermission.value = ok
        if (ok && a2dp == null) {
            try {
                val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
                adapter?.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.A2DP) {
                            a2dp = proxy
                            scheduleRefresh(100)
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.A2DP) a2dp = null
                    }
                }, BluetoothProfile.A2DP)
            } catch (_: Throwable) {
            }
        }
        scheduleRefresh(100)
    }

    fun refreshNow() = refresh()

    private fun scheduleRefresh(delayMs: Long) {
        main.removeCallbacks(refreshRunnable)
        main.postDelayed(refreshRunnable, delayMs)
    }

    private fun refresh() {
        if (!started) return
        val infos = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        } catch (_: Throwable) {
            emptyList()
        }
        val list = infos.filter { it.type in BT_TYPES }.map { toBtDevice(it) }.distinctBy { it.key + it.typeId }
        _outputs.value = list

        val newActive = list.filter { it.isMusic }.minByOrNull { priority(it.typeId) }
        val prev = _active.value
        _active.value = newActive
        if (prev?.key != newActive?.key) AudioEngine.onActiveDeviceChanged(newActive)
    }

    private fun priority(type: Int) = when (type) {
        TYPE_A2DP -> 0
        TYPE_BLE_HEADSET -> 1
        TYPE_BLE_SPEAKER -> 2
        TYPE_HEARING_AID -> 3
        TYPE_BLE_BROADCAST -> 4
        else -> 9
    }

    private fun typeLabel(type: Int) = when (type) {
        TYPE_A2DP -> "Bluetooth A2DP (zene)"
        TYPE_SCO -> "Bluetooth SCO (hívás)"
        TYPE_HEARING_AID -> "Hallókészülék"
        TYPE_BLE_HEADSET -> "LE Audio fejhallgató"
        TYPE_BLE_SPEAKER -> "LE Audio hangszóró"
        TYPE_BLE_BROADCAST -> "LE Audio broadcast (Auracast)"
        else -> "Bluetooth"
    }

    private fun toBtDevice(info: AudioDeviceInfo): BtDevice {
        val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.address.orEmpty().uppercase() else ""
        val name = info.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth eszköz"
        val validAddr = addr.isNotBlank() && addr != "00:00:00:00:00:00"
        return BtDevice(
            key = if (validAddr) addr else "name:$name",
            name = name,
            typeLabel = typeLabel(info.type),
            typeId = info.type,
            address = addr,
            battery = if (validAddr) batteryFor(addr) else null,
            codec = if (info.type == TYPE_A2DP) codecFor(addr) else null,
            isMusic = info.type != TYPE_SCO,
        )
    }

    private fun batteryFor(addr: String): Int? {
        batteryByAddress[addr]?.let { return it }
        if (!hasConnectPermission()) return null
        return try {
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
            val dev = adapter.getRemoteDevice(addr)
            val level = BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(dev) as Int
            level.takeIf { it in 0..100 }
        } catch (_: Throwable) {
            null
        }
    }

    private fun codecFor(addr: String): String? {
        val proxy = a2dp ?: return null
        if (!hasConnectPermission()) return null
        return try {
            val connected = proxy.connectedDevices
            val dev = connected.firstOrNull { it.address.equals(addr, ignoreCase = true) }
                ?: connected.firstOrNull()
                ?: return null
            val status = proxy.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
                .invoke(proxy, dev) ?: return null
            val cfg = status.javaClass.getMethod("getCodecConfig").invoke(status) ?: return null
            val type = cfg.javaClass.getMethod("getCodecType").invoke(cfg) as Int
            val rate = cfg.javaClass.getMethod("getSampleRate").invoke(cfg) as Int
            val bits = cfg.javaClass.getMethod("getBitsPerSample").invoke(cfg) as Int
            listOfNotNull(codecName(type), rateName(rate), bitsName(bits)).joinToString(" · ")
        } catch (_: Throwable) {
            null
        }
    }

    private fun codecName(t: Int) = when (t) {
        0 -> "SBC"
        1 -> "AAC"
        2 -> "aptX"
        3 -> "aptX HD"
        4 -> "LDAC"
        5 -> "LC3"
        6 -> "Opus"
        else -> "Kodek #$t"
    }

    private fun rateName(r: Int) = when (r) {
        0x1 -> "44,1 kHz"
        0x2 -> "48 kHz"
        0x4 -> "88,2 kHz"
        0x8 -> "96 kHz"
        0x10 -> "176,4 kHz"
        0x20 -> "192 kHz"
        else -> null
    }

    private fun bitsName(b: Int) = when (b) {
        0x1 -> "16 bit"
        0x2 -> "24 bit"
        0x4 -> "32 bit"
        else -> null
    }
}
