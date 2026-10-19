package hu.bluetune.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import hu.bluetune.audio.AudioEngine
import hu.bluetune.data.AppPrefs

/** Gyorsbeállítás-csempe: EQ be/ki egy koppintással a lehúzható menüből. */
class EqTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile(EqService.running.value && AudioEngine.settings.value.enabled)
    }

    override fun onClick() {
        super.onClick()
        val nowOn: Boolean
        if (!EqService.running.value) {
            AppPrefs.serviceWanted = true
            AudioEngine.setEnabled(true)
            nowOn = EqService.start(this)
        } else {
            nowOn = !AudioEngine.settings.value.enabled
            AudioEngine.setEnabled(nowOn)
        }
        refreshTile(nowOn)
    }

    private fun refreshTile(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "BlueTune EQ"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (on) (AudioEngine.settings.value.presetName ?: "Egyéni") else "Ki"
        }
        tile.updateTile()
    }
}
