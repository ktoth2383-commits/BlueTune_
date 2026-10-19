package hu.bluetune

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import hu.bluetune.audio.AudioEngine
import hu.bluetune.audio.VolumeControl
import hu.bluetune.bt.BluetoothMonitor
import hu.bluetune.cloud.CloudRepo
import hu.bluetune.data.AppPrefs
import hu.bluetune.data.DeviceProfileStore
import hu.bluetune.data.PresetStore
import hu.bluetune.service.EqService

class BlueTuneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        PresetStore.init(this)
        DeviceProfileStore.init(this)
        AudioEngine.init()
        VolumeControl.init(this)
        BluetoothMonitor.start(this)
        CloudRepo.init(this)
        ensureChannel(this)
    }

    companion object {
        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(EqService.CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                EqService.CHANNEL_ID,
                "Hangszínszabályzó szolgáltatás",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Az EQ futása közben látható értesítés gyorsgombokkal"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }
}
