package hu.bluetune.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.sqrt

/** A 10 sávos grafikus EQ frekvenciái és a basszus/treble súlyozás. */
object Bands {
    const val COUNT = 10
    const val MIN_DB = -15f
    const val MAX_DB = 15f

    val CENTERS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    /** DynamicsProcessing EqBand: a "cutoff" a sáv FELSŐ határa (Hz). */
    val CUTOFFS = FloatArray(COUNT) { i ->
        if (i == COUNT - 1) 20000f else sqrt(CENTERS[i] * CENTERS[i + 1])
    }

    /** Basszus csúszka hatása sávonként (low-shelf jellegű). */
    val BASS_WEIGHT = floatArrayOf(1f, 1f, 0.8f, 0.45f, 0.15f, 0f, 0f, 0f, 0f, 0f)

    /** Treble csúszka hatása sávonként (high-shelf jellegű). */
    val TREBLE_WEIGHT = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0.05f, 0.2f, 0.55f, 0.9f, 1f)
}

/**
 * Minden hangbeállítás egy helyen. Ezt mentjük presetbe, eszközprofilba és a felhőbe.
 */
data class EqSettings(
    val enabled: Boolean = true,
    val bands: List<Float> = List(Bands.COUNT) { 0f },
    /** Előerősítés dB (-12..+12) */
    val preamp: Float = 0f,
    /** Basszus dB (-12..+12) */
    val bass: Float = 0f,
    /** Magas hangok dB (-12..+12) */
    val treble: Float = 0f,
    /** Android BassBoost erősség 0..1000 */
    val bassBoost: Int = 0,
    /** Térhatás (Virtualizer) 0..1000 */
    val virtualizer: Int = 0,
    /** Hangerő boost dB (LoudnessEnhancer) 0..15 */
    val loudness: Float = 0f,
    /** Balansz -1 (bal) .. +1 (jobb) */
    val balance: Float = 0f,
    /** Torzításvédő limiter */
    val limiter: Boolean = true,
    val presetName: String? = null,
) {
    /** Sávonkénti végső erősítés: sáv + basszus + treble. */
    fun effectiveGains(): FloatArray = FloatArray(Bands.COUNT) { i ->
        (bands.getOrElse(i) { 0f } + bass * Bands.BASS_WEIGHT[i] + treble * Bands.TREBLE_WEIGHT[i])
            .coerceIn(-24f, 24f)
    }

    /** Erősítés tetszőleges frekvencián (logaritmikus interpoláció a sávok között). */
    fun gainAt(freqHz: Float, gains: FloatArray = effectiveGains()): Float {
        val c = Bands.CENTERS
        if (freqHz <= c.first()) return gains.first()
        if (freqHz >= c.last()) return gains.last()
        for (i in 0 until Bands.COUNT - 1) {
            if (freqHz <= c[i + 1]) {
                val t = (ln(freqHz) - ln(c[i])) / (ln(c[i + 1]) - ln(c[i]))
                return gains[i] + (gains[i + 1] - gains[i]) * t
            }
        }
        return gains.last()
    }

    /** Átveszi egy preset hangzását, de a be/ki állapot marad. */
    fun withSound(from: EqSettings, name: String?): EqSettings = copy(
        bands = from.bands,
        preamp = from.preamp,
        bass = from.bass,
        treble = from.treble,
        bassBoost = from.bassBoost,
        virtualizer = from.virtualizer,
        loudness = from.loudness,
        balance = from.balance,
        limiter = from.limiter,
        presetName = name,
    )

    fun summary(): String {
        val parts = mutableListOf<String>()
        if (bass != 0f) parts += "Basszus ${fmt(bass)}"
        if (treble != 0f) parts += "Magas ${fmt(treble)}"
        if (bassBoost > 0) parts += "Boost ${bassBoost / 10}%"
        if (virtualizer > 0) parts += "Tér ${virtualizer / 10}%"
        if (preamp != 0f) parts += "Pre ${fmt(preamp)}"
        return if (parts.isEmpty()) "10 sávos EQ" else parts.joinToString(" · ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("bands", JSONArray().apply { bands.forEach { put(it.toDouble()) } })
        put("preamp", preamp.toDouble())
        put("bass", bass.toDouble())
        put("treble", treble.toDouble())
        put("bassBoost", bassBoost)
        put("virtualizer", virtualizer)
        put("loudness", loudness.toDouble())
        put("balance", balance.toDouble())
        put("limiter", limiter)
        presetName?.let { put("presetName", it) }
    }

    companion object {
        private fun fmt(v: Float) = (if (v > 0) "+" else "") + "%.0f".format(v)

        fun fromJson(o: JSONObject): EqSettings {
            val arr = o.optJSONArray("bands")
            val bands = List(Bands.COUNT) { i ->
                (arr?.optDouble(i, 0.0) ?: 0.0).toFloat().coerceIn(Bands.MIN_DB, Bands.MAX_DB)
            }
            return EqSettings(
                enabled = o.optBoolean("enabled", true),
                bands = bands,
                preamp = o.optDouble("preamp", 0.0).toFloat().coerceIn(-12f, 12f),
                bass = o.optDouble("bass", 0.0).toFloat().coerceIn(-12f, 12f),
                treble = o.optDouble("treble", 0.0).toFloat().coerceIn(-12f, 12f),
                bassBoost = o.optInt("bassBoost", 0).coerceIn(0, 1000),
                virtualizer = o.optInt("virtualizer", 0).coerceIn(0, 1000),
                loudness = o.optDouble("loudness", 0.0).toFloat().coerceIn(0f, 15f),
                balance = o.optDouble("balance", 0.0).toFloat().coerceIn(-1f, 1f),
                limiter = o.optBoolean("limiter", true),
                presetName = if (o.has("presetName")) o.optString("presetName") else null,
            )
        }

        fun fromJsonString(s: String): EqSettings? = try {
            fromJson(JSONObject(s))
        } catch (_: Exception) {
            null
        }
    }
}
