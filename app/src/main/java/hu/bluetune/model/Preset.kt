package hu.bluetune.model

data class Preset(
    val name: String,
    val settings: EqSettings,
    val builtIn: Boolean = false,
)

object BuiltInPresets {
    //                         31  62 125 250 500  1k  2k  4k  8k 16k
    val all: List<Preset> = listOf(
        preset("Lapos", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        preset("Basszus erősítés", 6, 5, 4, 2, 0, 0, 0, 0, 0, 0),
        preset("Mély basszus (sub)", 8, 6, 3, 0, -1, 0, 0, 0, 0, 0, bassBoost = 300),
        preset("Magas erősítés", 0, 0, 0, 0, 0, 1, 2, 4, 5, 6),
        preset("Loudness (V-alak)", 5, 4, 1, 0, -2, -2, 0, 2, 4, 5),
        preset("Rock", 4, 3, 2, 0, -1, -1, 1, 3, 4, 4),
        preset("Pop", -1, 1, 3, 4, 3, 0, -1, -1, 1, 2),
        preset("Hip-hop / Trap", 6, 5, 2, 3, -1, -1, 1, 0, 2, 3, bassBoost = 200),
        preset("Elektronikus", 5, 4, 1, 0, -2, 1, 0, 2, 4, 5),
        preset("Jazz", 3, 2, 1, 2, -1, -1, 0, 1, 2, 3),
        preset("Klasszikus", 4, 3, 2, 1, 0, 0, 0, 2, 3, 4),
        preset("Ének / Vokál", -2, -1, 0, 2, 4, 4, 3, 1, 0, -1),
        preset("Podcast / Beszéd", -4, -3, -1, 1, 3, 4, 3, 1, -1, -3),
        preset("Autó", 4, 3, 2, 0, 0, 0, 1, 2, 3, 3),
        preset("Fülhallgató korrekció", 4, 3, 1, 0, -1, 0, 1, 2, 0, -2),
        preset("Esti (halk)", -3, -2, 0, 1, 2, 2, 1, 0, -2, -3),
        preset("Térhatás / Film", 4, 3, 1, 0, 0, 1, 2, 3, 3, 2, virtualizer = 600),
    )

    private fun preset(
        name: String,
        vararg b: Int,
        bassBoost: Int = 0,
        virtualizer: Int = 0,
    ) = Preset(
        name = name,
        settings = EqSettings(
            bands = b.map { it.toFloat() },
            bassBoost = bassBoost,
            virtualizer = virtualizer,
            presetName = name,
        ),
        builtIn = true,
    )
}
