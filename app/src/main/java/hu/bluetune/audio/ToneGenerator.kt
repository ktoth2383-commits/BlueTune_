package hu.bluetune.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Tesztjel-generátor: szinusz adott frekvencián, bal/jobb/mindkét csatornán,
 * vagy 20 Hz → 20 kHz sweep. A globális EQ hatása így közvetlenül hallható.
 */
object ToneGenerator {
    enum class Channel { LEFT, BOTH, RIGHT }

    private const val RATE = 48000
    private const val SWEEP_SECONDS = 12.0
    private const val AMPLITUDE = 0.3

    @Volatile var frequency: Float = 1000f
    @Volatile var channel: Channel = Channel.BOTH
    @Volatile var sweep: Boolean = false
    @Volatile private var running = false

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    fun start() {
        if (running) return
        running = true
        _playing.value = true
        val t = Thread { loop() }
        t.name = "BlueTune-tone"
        t.start()
    }

    fun stop() {
        running = false
    }

    private fun loop() {
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, 8192) * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()

            val frames = 1024
            val buf = ShortArray(frames * 2)
            val silence: Short = 0
            var phase = 0.0
            var sweepT = 0.0
            var ramp = 0
            while (running) {
                for (i in 0 until frames) {
                    val f = if (sweep) {
                        val pos = (sweepT % SWEEP_SECONDS) / SWEEP_SECONDS
                        20.0 * 1000.0.pow(pos)
                    } else {
                        frequency.toDouble()
                    }
                    sweepT += 1.0 / RATE
                    phase += 2.0 * PI * f / RATE
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                    if (ramp < 2400) ramp++
                    val v = (sin(phase) * AMPLITUDE * (ramp / 2400.0) * Short.MAX_VALUE).toInt().toShort()
                    val ch = channel
                    buf[2 * i] = if (ch != Channel.RIGHT) v else silence
                    buf[2 * i + 1] = if (ch != Channel.LEFT) v else silence
                }
                track.write(buf, 0, buf.size)
            }
        } catch (_: Throwable) {
            // pl. nincs audio kimenet
        } finally {
            track?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            running = false
            _playing.value = false
        }
    }
}
