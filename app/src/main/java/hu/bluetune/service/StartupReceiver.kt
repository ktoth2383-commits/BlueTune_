package hu.bluetune.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import hu.bluetune.data.AppPrefs

/** Indítás bekapcsoláskor, frissítés után, és amikor Bluetooth eszköz csatlakozik. */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AppPrefs.serviceWanted) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> if (AppPrefs.startOnBoot) EqService.start(context)
            BluetoothDevice.ACTION_ACL_CONNECTED -> if (!EqService.running.value) EqService.start(context)
        }
    }
}
