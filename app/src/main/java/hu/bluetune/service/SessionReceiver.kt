package hu.bluetune.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import hu.bluetune.audio.AudioEngine

/**
 * A zenelejátszók (Spotify, YouTube Music, Poweramp, VLC…) ezzel jelentik be
 * a saját audio sessionjüket – ezekre is felrakjuk az effekteket.
 */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        if (id <= 0) return
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->
                AudioEngine.openSession(id, intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION ->
                AudioEngine.closeSession(id)
        }
    }
}
