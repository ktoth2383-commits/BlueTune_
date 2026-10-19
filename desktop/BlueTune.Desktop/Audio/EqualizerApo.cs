using System.Diagnostics;
using System.Globalization;
using System.Text;
using Microsoft.Win32;
using BlueTune.Desktop.Core;

namespace BlueTune.Desktop.Audio;

/// <summary>
/// A Windows nem ad nyilvános API-t rendszerszintű hangszínszabályzóhoz, ezért a BlueTune
/// az ingyenes, nyílt forrású Equalizer APO-t használja hangmotorként (ugyanígy működik pl. a Peace).
///
/// Működés: a BlueTune a saját „BlueTune.txt” fájlját írja az Equalizer APO config mappájába,
/// a config.txt pedig csak ezt tölti be. Az Equalizer APO a fájl változását azonnal érzékeli.
/// </summary>
public static class EqualizerApo
{
    public const string ConfigFileName = "BlueTune.txt";
    public const string DownloadUrl = "https://sourceforge.net/projects/equalizerapo/";
    private const string IncludeLine = "Include: " + ConfigFileName;

    public enum State { NotInstalled, NeedsSetup, Ready }

    public static string? InstallDir
    {
        get
        {
            var fromReg = ReadReg("InstallPath");
            if (!string.IsNullOrEmpty(fromReg) && Directory.Exists(fromReg)) return fromReg;
            var def = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "EqualizerAPO");
            return Directory.Exists(def) ? def : null;
        }
    }

    public static string? ConfigDir
    {
        get
        {
            var fromReg = ReadReg("ConfigPath");
            if (!string.IsNullOrEmpty(fromReg) && Directory.Exists(fromReg)) return fromReg;
            var inst = InstallDir;
            if (inst == null) return null;
            var cfg = Path.Combine(inst, "config");
            return Directory.Exists(cfg) ? cfg : null;
        }
    }

    public static string? OurFile => ConfigDir is { } d ? Path.Combine(d, ConfigFileName) : null;

    public static State GetState()
    {
        var dir = ConfigDir;
        if (dir == null) return State.NotInstalled;
        var cfg = Path.Combine(dir, "config.txt");
        var ours = Path.Combine(dir, ConfigFileName);
        try
        {
            var included = File.Exists(cfg) && File.ReadAllLines(cfg)
                .Any(l => l.Trim().Equals(IncludeLine, StringComparison.OrdinalIgnoreCase));
            if (!included || !File.Exists(ours) || !CanWrite(ours)) return State.NeedsSetup;
            return State.Ready;
        }
        catch
        {
            return State.NeedsSetup;
        }
    }

    /// <summary>Az Equalizer APO Configurator (itt lehet kiválasztani, mely eszközökre hasson).</summary>
    public static void OpenConfigurator()
    {
        var exe = InstallDir is { } d ? Path.Combine(d, "Configurator.exe") : null;
        if (exe != null && File.Exists(exe))
            Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true, Verb = "runas" });
    }

    // ------------------------------------------------------------------ beállítás (rendszergazdaként)

    /// <summary>
    /// Elindítja önmagát rendszergazdaként a „--setup-apo” kapcsolóval. Igaz, ha sikerült.
    /// </summary>
    public static bool RunElevatedSetup()
    {
        try
        {
            var p = Process.Start(new ProcessStartInfo(Environment.ProcessPath!, "--setup-apo")
            {
                UseShellExecute = true,
                Verb = "runas",
            });
            p?.WaitForExit();
            return p?.ExitCode == 0;
        }
        catch (System.ComponentModel.Win32Exception)
        {
            return false; // a felhasználó a UAC ablakban a „Nem”-et választotta
        }
    }

    /// <summary>
    /// A rendszergazdai példány ezt futtatja:
    ///  1. a régi config.txt-t elmenti (config.txt.bluetune-backup)
    ///  2. új config.txt: csak a BlueTune.txt-t tölti be
    ///  3. BlueTune.txt létrehozása + írási jog a normál felhasználóknak (csak erre az egy fájlra)
    /// </summary>
    public static int SetupAsAdmin()
    {
        var dir = ConfigDir;
        if (dir == null) return 2;
        try
        {
            var cfg = Path.Combine(dir, "config.txt");
            var ours = Path.Combine(dir, ConfigFileName);
            var backup = cfg + ".bluetune-backup";
            if (File.Exists(cfg) && !File.Exists(backup)) File.Copy(cfg, backup);

            File.WriteAllText(cfg,
                "# Ezt a fájlt a BlueTune kezeli.\r\n" +
                "# A korábbi beállításod: config.txt.bluetune-backup\r\n" +
                IncludeLine + "\r\n", new UTF8Encoding(false));

            if (!File.Exists(ours)) File.WriteAllText(ours, "# BlueTune\r\n", new UTF8Encoding(false));

            // Írási jog a „Felhasználók” csoportnak (S-1-5-32-545) – csak a BlueTune.txt-re
            var icacls = Process.Start(new ProcessStartInfo("icacls.exe", $"\"{ours}\" /grant *S-1-5-32-545:M")
            {
                CreateNoWindow = true,
                UseShellExecute = false,
            });
            icacls?.WaitForExit();
            return icacls?.ExitCode ?? 1;
        }
        catch
        {
            return 1;
        }
    }

    /// <summary>A BlueTune eltávolítása az Equalizer APO-ból (a régi config.txt visszaállítása).</summary>
    public static int RestoreAsAdmin()
    {
        var dir = ConfigDir;
        if (dir == null) return 2;
        try
        {
            var cfg = Path.Combine(dir, "config.txt");
            var backup = cfg + ".bluetune-backup";
            if (File.Exists(backup)) File.Copy(backup, cfg, overwrite: true);
            var ours = Path.Combine(dir, ConfigFileName);
            if (File.Exists(ours)) File.Delete(ours);
            return 0;
        }
        catch
        {
            return 1;
        }
    }

    // ------------------------------------------------------------------ írás (normál felhasználóként)

    /// <summary>Kiírja a hangbeállítást. Hamis, ha nem sikerült (pl. nincs beállítva).</summary>
    public static bool Apply(EqSettings s, string? deviceName)
    {
        var path = OurFile;
        if (path == null) return false;
        try
        {
            File.WriteAllText(path, BuildConfig(s, deviceName), new UTF8Encoding(false));
            return true;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>Equalizer APO konfiguráció a beállításból.</summary>
    public static string BuildConfig(EqSettings s, string? deviceName)
    {
        var ci = CultureInfo.InvariantCulture;
        string F(float v) => v.ToString("0.0", ci);

        var sb = new StringBuilder();
        sb.Append("# BlueTune – automatikusan generálva, ne szerkeszd kézzel\r\n");
        if (deviceName != null) sb.Append($"# Eszköz: {deviceName.Replace('\r', ' ').Replace('\n', ' ')}\r\n");
        if (s.PresetName != null) sb.Append($"# Preset: {s.PresetName.Replace('\r', ' ').Replace('\n', ' ')}\r\n");

        if (!s.Enabled)
        {
            sb.Append("# EQ kikapcsolva\r\n");
            return sb.ToString();
        }

        // Előerősítés: preamp + hangerő-boost; túlvezérlés-védelem esetén levonjuk az EQ csúcsát
        var pre = s.Preamp + s.Loudness;
        if (s.Limiter) pre -= s.PeakBoostDb();
        sb.Append($"Preamp: {F(pre)} dB\r\n");

        // 10 sávos grafikus EQ, a basszus/treble súlyozás már benne van
        var g = s.EffectiveGains();
        var pairs = Bands.Centers.Select((hz, i) => $"{hz.ToString("0", ci)} {F(g[i])}");
        sb.Append("GraphicEQ: ").Append(string.Join("; ", pairs)).Append("\r\n");

        // Bass Boost: extra mély polcszűrő
        if (s.BassBoost > 0)
            sb.Append($"Filter: ON LS Fc 80 Hz Gain {F(s.BassBoostDb)} dB\r\n");

        // Balansz: a másik csatorna csillapítása
        if (Math.Abs(s.Balance) > 0.01f)
        {
            static float Att(float x) => x >= 0.999f ? -60f : Math.Max(-60f, 20f * MathF.Log10(1f - x));
            if (s.Balance > 0)
                sb.Append($"Channel: L\r\nPreamp: {F(Att(s.Balance))} dB\r\n");
            else
                sb.Append($"Channel: R\r\nPreamp: {F(Att(-s.Balance))} dB\r\n");
            sb.Append("Channel: all\r\n");
        }
        return sb.ToString();
    }

    // ------------------------------------------------------------------ segédek

    private static string? ReadReg(string name)
    {
        try
        {
            using var k = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64)
                .OpenSubKey(@"SOFTWARE\EqualizerAPO");
            return k?.GetValue(name) as string;
        }
        catch
        {
            return null;
        }
    }

    private static bool CanWrite(string path)
    {
        try
        {
            using var fs = new FileStream(path, FileMode.Open, FileAccess.Write, FileShare.ReadWrite);
            return true;
        }
        catch
        {
            return false;
        }
    }
}
