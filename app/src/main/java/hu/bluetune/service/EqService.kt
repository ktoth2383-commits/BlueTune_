package hu.bluetune.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import hu.bluetune.BlueTuneApp
import hu.bluetune.R
import hu.bluetune.audio.AudioEngine
import hu.bluetune.bt.BluetoothMonitor
import hu.bluetune.data.AppPrefs
import hu.bluetune.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Előtérszolgáltatás: életben tartja a globális effekteket, fogadja a lejátszók
 * session-bejelentéseit, és értesítést mutat gyorsgombokkal.
 */
class EqService : Service() {

    companion object {
        const val CHANNEL_ID = "eq_service"
        const val ACTION_TOGGLE = "hu.bluetune.action.TOGGLE"
        const val ACTION_STOP = "hu.bluetune.action.STOP"
        private const val NOTIF_ID = 42
        private const val TAG = "BlueTune"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, EqService::class.java))
            true
        } catch (t: Throwable) {
            // Android 12+: háttérből nem mindig indítható (kapcsold ki az akku-optimalizálást)
            Log.w(TAG, "Szolgáltatás indítása nem sikerült", t)
            false
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, EqService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessionReceiver = SessionReceiver()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BlueTuneApp.ensureChannel(this)
        startInForeground(buildNotification())

        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(this, sessionReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        AudioEngine.start()
        _running.value = true

        scope.launch {
            combine(AudioEngine.settings, BluetoothMonitor.active) { s, d ->
                listOf(s.enabled, s.presetName, d?.name, d?.battery, d?.codec)
            }.distinctUntilChanged().collect { updateNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> AudioEngine.update { it.copy(enabled = !it.enabled) }
            ACTION_STOP -> {
                AppPrefs.serviceWanted = false
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { unregisterReceiver(sessionReceiver) }
        AudioEngine.stop()
        _running.value = false
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(): Notification {
        val s = AudioEngine.settings.value
        val d = BluetoothMonitor.active.value
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, EqService::class.java).setAction(ACTION_TOGGLE), flags,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, EqService::class.java).setAction(ACTION_STOP), flags,
        )

        val deviceText = d?.let { dev ->
            buildString {
                append(dev.name)
                dev.battery?.let { append(" · $it%") }
                dev.codec?.let { append(" · $it") }
            }
        } ?: "Telefon hangszóró / vezetékes"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_eq)
            .setContentTitle(if (s.enabled) "EQ aktív · ${s.presetName ?: "Egyéni"}" else "EQ szüneteltetve")
            .setContentText(deviceText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setSubText("Háttérben fut")
            .setContentIntent(open)
            // Első gomb: teljes leállítás (a szolgáltatás leáll, az effektek lekerülnek).
            // Újra az app megnyitásával indul.
            .addAction(0, "⏹ Leállítás", stop)
            .addAction(0, if (s.enabled) "EQ ki" else "EQ be", toggle)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
