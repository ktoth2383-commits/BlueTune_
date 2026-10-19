package hu.bluetune.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log
import hu.bluetune.model.Bands
import hu.bluetune.model.EqSettings
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Egy audio sessionre (0 = globális kimenet) felhelyezett effektlánc:
 *  - DynamicsProcessing: 10 sávos EQ + preamp + balansz + limiter (Android 9+)
 *  - vagy Equalizer fallback (a készülék saját sávjai, általában 5)
 *  - BassBoost, Virtualizer (térhatás), LoudnessEnhancer (hangerő boost)
 */
class EffectChain(val sessionId: Int, preferDynamics: Boolean) {

    private var dp: DynamicsProcessing? = null
    private var eq: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null

    var backend: String = "Nincs"
        private set
    val errors = mutableListOf<String>()

    init {
        if (preferDynamics && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dp = create("DynamicsProcessing") { buildDynamics() }
            if (dp != null) backend = "DynamicsProcessing (10 sáv, preamp, balansz, limiter)"
        }
        if (dp == null) {
            eq = create("Equalizer") { Equalizer(PRIORITY, sessionId) }
            eq?.let { backend = "Equalizer (${it.numberOfBands} sáv)" }
        }
        bassBoost = create("BassBoost") { BassBoost(PRIORITY, sessionId) }
            ?.takeIf { it.strengthSupported }
        virtualizer = create("Virtualizer") { Virtualizer(PRIORITY, sessionId) }
            ?.takeIf { it.strengthSupported }
        loudness = create("LoudnessEnhancer") { LoudnessEnhancer(sessionId) }
    }

    val isEmpty: Boolean
        get() = dp == null && eq == null && bassBoost == null && virtualizer == null && loudness == null

    fun hasControl(): Boolean = listOfNotNull<AudioEffect>(dp, eq, bassBoost, virtualizer, loudness)
        .all { runCatching { it.hasControl() }.getOrDefault(true) }

    fun apply(s: EqSettings) {
        val on = s.enabled
        val gains = s.effectiveGains()

        dp?.let { d ->
            safe("DP apply") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val eqCfg = DynamicsProcessing.Eq(true, true, Bands.COUNT)
                    for (i in 0 until Bands.COUNT) {
                        eqCfg.setBand(i, DynamicsProcessing.EqBand(true, Bands.CUTOFFS[i], gains[i]))
                    }
                    d.setPreEqAllChannelsTo(eqCfg)
                    d.setLimiterAllChannelsTo(
                        DynamicsProcessing.Limiter(
                            true, s.limiter, 0,
                            1f,    // attack ms
                            60f,   // release ms
                            10f,   // ratio
                            -1f,   // threshold dB
                            0f,    // post gain dB
                        )
                    )
                    val (l, r) = balanceDb(s.balance)
                    d.setInputGainbyChannel(0, s.preamp + l)
                    d.setInputGainbyChannel(1, s.preamp + r)
                    d.setEnabled(on)
                }
            }
        }

        eq?.let { e ->
            safe("EQ apply") {
                val range = e.bandLevelRange
                val n = e.numberOfBands.toInt()
                for (b in 0 until n) {
                    val hz = e.getCenterFreq(b.toShort()) / 1000f
                    val mb = ((s.gainAt(hz, gains) + s.preamp) * 100f).roundToInt()
                        .coerceIn(range[0].toInt(), range[1].toInt())
                    e.setBandLevel(b.toShort(), mb.toShort())
                }
                e.setEnabled(on)
            }
        }

        bassBoost?.let { b ->
            safe("BassBoost apply") {
                b.setStrength(s.bassBoost.coerceIn(0, 1000).toShort())
                b.setEnabled(on && s.bassBoost > 0)
            }
        }

        virtualizer?.let { v ->
            safe("Virtualizer apply") {
                v.setStrength(s.virtualizer.coerceIn(0, 1000).toShort())
                v.setEnabled(on && s.virtualizer > 0)
            }
        }

        loudness?.let { le ->
            safe("Loudness apply") {
                le.setTargetGain((s.loudness * 100f).roundToInt())
                le.setEnabled(on && s.loudness > 0f)
            }
        }
    }

    fun release() {
        listOfNotNull<AudioEffect>(dp, eq, bassBoost, virtualizer, loudness).forEach {
            runCatching { it.setEnabled(false) }
            runCatching { it.release() }
        }
        dp = null; eq = null; bassBoost = null; virtualizer = null; loudness = null
    }

    private fun buildDynamics(): DynamicsProcessing {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,              // csatornák (L/R)
            true, Bands.COUNT, // pre-EQ
            false, 0,       // multiband compressor
            false, 0,       // post-EQ
            true,           // limiter
        ).setPreferredFrameDuration(10f).build()
        return DynamicsProcessing(PRIORITY, sessionId, cfg)
    }

    private inline fun <T> create(name: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        errors += "$name (#$sessionId): ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
        Log.w(TAG, "Nem sikerült: $name session=$sessionId", t)
        null
    }

    private inline fun safe(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "$what hiba (session $sessionId)", t)
        }
    }

    companion object {
        private const val TAG = "BlueTune"
        private const val PRIORITY = 1000

        /** Balansz → csatornánkénti csillapítás dB-ben (bal, jobb). */
        fun balanceDb(balance: Float): Pair<Float, Float> {
            fun att(x: Float): Float = if (x >= 0.999f) -60f else (20f * log10(1f - x)).coerceAtLeast(-60f)
            return when {
                balance > 0f -> att(balance) to 0f   // jobbra: bal halkul
                balance < 0f -> 0f to att(-balance)  // balra: jobb halkul
                else -> 0f to 0f
            }
        }
    }
}
