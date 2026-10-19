using System.Text.Json;
using System.Text.Json.Serialization;

namespace BlueTune.Desktop.Core;

public static class Bands
{
    public const int Count = 10;
    public const float MinDb = -15f;
    public const float MaxDb = 15f;

    public static readonly float[] Centers = [31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f];
    public static readonly string[] Labels = ["31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"];

    // Ugyanaz a súlyozás, mint az Android appban, hogy a görbe azonos legyen
    public static readonly float[] BassWeight = [1f, 1f, 0.8f, 0.45f, 0.15f, 0f, 0f, 0f, 0f, 0f];
    public static readonly float[] TrebleWeight = [0f, 0f, 0f, 0f, 0f, 0.05f, 0.2f, 0.55f, 0.9f, 1f];
}

/// <summary>
/// Hangbeállítás – JSON formátuma megegyezik az Android appéval,
/// így a presetek oda-vissza importálhatók.
/// </summary>
public sealed record EqSettings
{
    public bool Enabled { get; init; } = true;
    public float[] Bands { get; init; } = new float[Core.Bands.Count];
    public float Preamp { get; init; }
    public float Bass { get; init; }
    public float Treble { get; init; }
    /// <summary>0..1000 (Androidon BassBoost; Windowson extra mélysugárzó-polc)</summary>
    public int BassBoost { get; init; }
    /// <summary>0..1000 – csak Androidon van hatása (Windowson: Windows Sonic)</summary>
    public int Virtualizer { get; init; }
    /// <summary>Hangerő boost dB (0..15)</summary>
    public float Loudness { get; init; }
    /// <summary>-1 (bal) .. +1 (jobb)</summary>
    public float Balance { get; init; }
    /// <summary>Androidon limiter, Windowson automatikus túlvezérlés-védelem</summary>
    public bool Limiter { get; init; } = true;
    public string? PresetName { get; init; }

    public float BandAt(int i) => i < Bands.Length ? Bands[i] : 0f;

    /// <summary>Sávonkénti végső erősítés (sáv + basszus + treble), mint Androidon.</summary>
    public float[] EffectiveGains()
    {
        var g = new float[Core.Bands.Count];
        for (var i = 0; i < g.Length; i++)
            g[i] = Math.Clamp(BandAt(i) + Bass * Core.Bands.BassWeight[i] + Treble * Core.Bands.TrebleWeight[i], -24f, 24f);
        return g;
    }

    public float GainAt(float hz, float[] gains)
    {
        var c = Core.Bands.Centers;
        if (hz <= c[0]) return gains[0];
        if (hz >= c[^1]) return gains[^1];
        for (var i = 0; i < c.Length - 1; i++)
        {
            if (hz <= c[i + 1])
            {
                var t = (MathF.Log(hz) - MathF.Log(c[i])) / (MathF.Log(c[i + 1]) - MathF.Log(c[i]));
                return gains[i] + (gains[i + 1] - gains[i]) * t;
            }
        }
        return gains[^1];
    }

    /// <summary>Bass Boost → dB a mély polcszűrőn (Windows).</summary>
    public float BassBoostDb => BassBoost / 1000f * 9f;

    /// <summary>Az EQ legnagyobb pozitív kiemelése (a túlvezérlés-védelemhez).</summary>
    public float PeakBoostDb()
    {
        var g = EffectiveGains();
        var peak = 0f;
        foreach (var v in g) peak = Math.Max(peak, v);
        // a Bass Boost a legmélyebb sávokra rakódik
        peak = Math.Max(peak, Math.Max(g[0], g[1]) + BassBoostDb);
        return peak;
    }

    public EqSettings WithSound(EqSettings from, string? name) => this with
    {
        Bands = (float[])from.Bands.Clone(),
        Preamp = from.Preamp,
        Bass = from.Bass,
        Treble = from.Treble,
        BassBoost = from.BassBoost,
        Virtualizer = from.Virtualizer,
        Loudness = from.Loudness,
        Balance = from.Balance,
        Limiter = from.Limiter,
        PresetName = name
    };

    public EqSettings WithBand(int i, float v)
    {
        var b = new float[Core.Bands.Count];
        for (var k = 0; k < b.Length; k++) b[k] = BandAt(k);
        b[i] = v;
        return this with { Bands = b, PresetName = null };
    }

    /// <summary>Értékhatárok érvényesítése (importnál).</summary>
    public EqSettings Sanitized()
    {
        var b = new float[Core.Bands.Count];
        for (var k = 0; k < b.Length; k++) b[k] = Math.Clamp(BandAt(k), Core.Bands.MinDb, Core.Bands.MaxDb);
        return this with
        {
            Bands = b,
            Preamp = Math.Clamp(Preamp, -12f, 12f),
            Bass = Math.Clamp(Bass, -12f, 12f),
            Treble = Math.Clamp(Treble, -12f, 12f),
            BassBoost = Math.Clamp(BassBoost, 0, 1000),
            Virtualizer = Math.Clamp(Virtualizer, 0, 1000),
            Loudness = Math.Clamp(Loudness, 0f, 15f),
            Balance = Math.Clamp(Balance, -1f, 1f)
        };
    }

    public bool SameSound(EqSettings o) =>
        Enabled == o.Enabled && Preamp == o.Preamp && Bass == o.Bass && Treble == o.Treble &&
        BassBoost == o.BassBoost && Loudness == o.Loudness && Balance == o.Balance && Limiter == o.Limiter &&
        Bands.AsSpan().SequenceEqual(o.Bands);

    public string Summary()
    {
        var parts = new List<string>();
        static string F(float v) => (v > 0 ? "+" : "") + v.ToString("0");
        if (Bass != 0) parts.Add($"Basszus {F(Bass)}");
        if (Treble != 0) parts.Add($"Magas {F(Treble)}");
        if (BassBoost > 0) parts.Add($"Boost {BassBoost / 10}%");
        if (Preamp != 0) parts.Add($"Pre {F(Preamp)}");
        return parts.Count == 0 ? "10 sávos EQ" : string.Join(" · ", parts);
    }

    public static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        WriteIndented = true,
        AllowTrailingCommas = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        NumberHandling = JsonNumberHandling.AllowReadingFromString,
    };
}

public sealed record Preset(string Name, EqSettings Settings, bool BuiltIn = false);

public static class BuiltInPresets
{
    //                    31  62 125 250 500  1k  2k  4k  8k 16k
    public static readonly List<Preset> All =
    [
        P("Lapos", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        P("Basszus erősítés", 6, 5, 4, 2, 0, 0, 0, 0, 0, 0),
        P("Mély basszus (sub)", 8, 6, 3, 0, -1, 0, 0, 0, 0, 0, bassBoost: 300),
        P("Magas erősítés", 0, 0, 0, 0, 0, 1, 2, 4, 5, 6),
        P("Loudness (V-alak)", 5, 4, 1, 0, -2, -2, 0, 2, 4, 5),
        P("Rock", 4, 3, 2, 0, -1, -1, 1, 3, 4, 4),
        P("Pop", -1, 1, 3, 4, 3, 0, -1, -1, 1, 2),
        P("Hip-hop / Trap", 6, 5, 2, 3, -1, -1, 1, 0, 2, 3, bassBoost: 200),
        P("Elektronikus", 5, 4, 1, 0, -2, 1, 0, 2, 4, 5),
        P("Jazz", 3, 2, 1, 2, -1, -1, 0, 1, 2, 3),
        P("Klasszikus", 4, 3, 2, 1, 0, 0, 0, 2, 3, 4),
        P("Ének / Vokál", -2, -1, 0, 2, 4, 4, 3, 1, 0, -1),
        P("Podcast / Beszéd", -4, -3, -1, 1, 3, 4, 3, 1, -1, -3),
        P("Autó", 4, 3, 2, 0, 0, 0, 1, 2, 3, 3),
        P("Fülhallgató korrekció", 4, 3, 1, 0, -1, 0, 1, 2, 0, -2),
        P("Esti (halk)", -3, -2, 0, 1, 2, 2, 1, 0, -2, -3),
        P("Gaming (lépések)", -2, -1, 0, 0, 1, 2, 4, 5, 3, 1),
        P("Film", 4, 3, 1, 0, 0, 1, 2, 3, 3, 2, virtualizer: 600),
    ];

    private static Preset P(string name, float b0, float b1, float b2, float b3, float b4, float b5, float b6, float b7, float b8, float b9,
        int bassBoost = 0, int virtualizer = 0) =>
        new(name, new EqSettings
        {
            Bands = [b0, b1, b2, b3, b4, b5, b6, b7, b8, b9],
            BassBoost = bassBoost,
            Virtualizer = virtualizer,
            PresetName = name,
        }, true);
}
