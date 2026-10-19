using System.Text.Json;
using System.Text.Json.Nodes;

namespace BlueTune.Desktop.Core;

public sealed record DeviceProfile(string Id, string Name, EqSettings Settings, float? Volume, DateTime LastSeen);

public sealed class AppPrefs
{
    public bool StartWithWindows { get; set; } = true;
    public bool AutoProfile { get; set; } = true;
    public bool RestoreVolume { get; set; } = true;
    /// <summary>Hallásvédelem: max. hangerő százalékban (100 = nincs korlát)</summary>
    public int MaxVolumePercent { get; set; } = 100;
    public bool TrayHintShown { get; set; }
    public EqSettings Current { get; set; } = new();
}

/// <summary>Helyi tárolás: %APPDATA%\BlueTune\*.json</summary>
public static class Store
{
    public static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "BlueTune");

    private static string PrefsPath => Path.Combine(Dir, "settings.json");
    private static string PresetsPath => Path.Combine(Dir, "presets.json");
    private static string DevicesPath => Path.Combine(Dir, "devices.json");

    public static AppPrefs Prefs { get; private set; } = new();
    public static List<Preset> Custom { get; private set; } = [];
    public static List<DeviceProfile> Devices { get; private set; } = [];

    public static event Action? PresetsChanged;
    public static event Action? DevicesChanged;

    public static void Load()
    {
        Directory.CreateDirectory(Dir);
        Prefs = Read<AppPrefs>(PrefsPath) ?? new AppPrefs();
        Prefs.Current = (Prefs.Current ?? new EqSettings()).Sanitized();
        Custom = ParsePresets(File.Exists(PresetsPath) ? File.ReadAllText(PresetsPath) : "[]");
        Devices = Read<List<DeviceProfile>>(DevicesPath) ?? [];
    }

    public static void SavePrefs() => Write(PrefsPath, Prefs);

    // ------------------------------------------------------------ presetek

    public static void SavePreset(string name, EqSettings s)
    {
        name = name.Trim();
        if (name.Length == 0) return;
        if (name.Length > 40) name = name[..40];
        Custom.RemoveAll(p => p.Name == name);
        Custom.Insert(0, new Preset(name, s with { Enabled = true, PresetName = name }));
        WritePresets();
    }

    public static void DeletePreset(string name)
    {
        Custom.RemoveAll(p => p.Name == name);
        WritePresets();
    }

    /// <summary>Az Android app exportjával azonos formátum: [{ "name": ..., "settings": {...} }]</summary>
    public static string ExportPresets()
    {
        var arr = new JsonArray();
        foreach (var p in Custom)
            arr.Add(new JsonObject
            {
                ["name"] = p.Name,
                ["settings"] = JsonSerializer.SerializeToNode(p.Settings, EqSettings.Json),
            });
        return arr.ToJsonString(EqSettings.Json);
    }

    public static int ImportPresets(string json)
    {
        var incoming = ParsePresets(json.Trim());
        if (incoming.Count == 0) return 0;
        var names = incoming.Select(p => p.Name).ToHashSet();
        Custom.RemoveAll(p => names.Contains(p.Name));
        Custom.InsertRange(0, incoming);
        WritePresets();
        return incoming.Count;
    }

    private static void WritePresets()
    {
        try { File.WriteAllText(PresetsPath, ExportPresets()); } catch { /* nem kritikus */ }
        PresetsChanged?.Invoke();
    }

    private static List<Preset> ParsePresets(string json)
    {
        var list = new List<Preset>();
        try
        {
            var node = JsonNode.Parse(json);
            var arr = node is JsonArray a ? a : new JsonArray(node?.DeepClone());
            foreach (var item in arr)
            {
                var name = item?["name"]?.GetValue<string>()?.Trim();
                var s = item?["settings"]?.Deserialize<EqSettings>(EqSettings.Json);
                if (string.IsNullOrEmpty(name) || s == null) continue;
                if (name.Length > 40) name = name[..40];
                list.Add(new Preset(name, s.Sanitized() with { PresetName = name }));
            }
        }
        catch { /* hibás JSON → üres lista */ }
        return list;
    }

    // ------------------------------------------------------------ eszközprofilok

    public static DeviceProfile? GetDevice(string id) => Devices.FirstOrDefault(d => d.Id == id);

    public static void UpsertDevice(string id, string name, EqSettings? settings = null, float? volume = null)
    {
        var old = GetDevice(id);
        var p = new DeviceProfile(id, name, settings ?? old?.Settings ?? new EqSettings(), volume ?? old?.Volume, DateTime.Now);
        Devices.RemoveAll(d => d.Id == id);
        Devices.Insert(0, p);
        Write(DevicesPath, Devices);
        DevicesChanged?.Invoke();
    }

    public static void DeleteDevice(string id)
    {
        Devices.RemoveAll(d => d.Id == id);
        Write(DevicesPath, Devices);
        DevicesChanged?.Invoke();
    }

    // ------------------------------------------------------------ segédek

    private static T? Read<T>(string path)
    {
        try { return File.Exists(path) ? JsonSerializer.Deserialize<T>(File.ReadAllText(path), EqSettings.Json) : default; }
        catch { return default; }
    }

    private static void Write<T>(string path, T value)
    {
        try
        {
            var tmp = path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(value, EqSettings.Json));
            File.Move(tmp, path, overwrite: true);
        }
        catch { /* pl. zárolt fájl – a következő mentés pótolja */ }
    }
}
