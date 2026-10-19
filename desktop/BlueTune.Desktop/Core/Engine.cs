using BlueTune.Desktop.Audio;

namespace BlueTune.Desktop.Core;

/// <summary>
/// A BlueTune központja Windowson: aktuális hangbeállítás, eszközfigyelés,
/// eszközönkénti profilok, hangerő-korlát, és az Equalizer APO felé írás.
/// Minden metódust a UI-szálról kell hívni.
/// </summary>
public sealed class Engine : IDisposable
{
    private readonly AudioDevices _audio = new();
    private readonly System.Windows.Forms.Timer _poll = new() { Interval = 1000 };
    private readonly System.Windows.Forms.Timer _debounce = new() { Interval = 120 };
    private readonly System.Windows.Forms.Timer _persist = new() { Interval = 600 };
    private int _tick;

    public EqSettings Settings { get; private set; }
    public OutputDevice? Device { get; private set; }
    public EqualizerApo.State ApoState { get; private set; }
    public float Volume { get; private set; }
    public bool Muted { get; private set; }

    /// <summary>Hangbeállítás változott (UI frissítéshez).</summary>
    public event Action? SettingsChanged;
    /// <summary>Kimeneti eszköz változott.</summary>
    public event Action? DeviceChanged;
    /// <summary>Hangerő vagy némítás változott.</summary>
    public event Action? VolumeChanged;
    /// <summary>Equalizer APO állapota változott.</summary>
    public event Action? ApoStateChanged;

    public Engine()
    {
        Settings = Store.Prefs.Current;
        ApoState = EqualizerApo.GetState();
        _poll.Tick += (_, _) => Poll();
        _debounce.Tick += (_, _) => { _debounce.Stop(); ApplyNow(); };
        _persist.Tick += (_, _) => { _persist.Stop(); PersistNow(); };
    }

    public void Start()
    {
        Poll();
        ApplyNow();
        _poll.Start();
    }

    // ------------------------------------------------------------------ beállítások

    public void Update(Func<EqSettings, EqSettings> transform)
    {
        var next = transform(Settings);
        if (next.SameSound(Settings) && next.PresetName == Settings.PresetName) return;
        Settings = next;
        SettingsChanged?.Invoke();
        _debounce.Stop(); _debounce.Start();
        _persist.Stop(); _persist.Start();
    }

    public void ApplyPreset(Preset p) => Update(s => s.WithSound(p.Settings, p.Name));

    public void SetEnabled(bool on) => Update(s => s with { Enabled = on });

    /// <summary>Az Equalizer APO beállítása (UAC ablakot nyit).</summary>
    public bool SetupApo()
    {
        var ok = EqualizerApo.RunElevatedSetup();
        RefreshApoState();
        if (ok) ApplyNow();
        return ok;
    }

    public void RefreshApoState()
    {
        var st = EqualizerApo.GetState();
        if (st == ApoState) return;
        ApoState = st;
        ApoStateChanged?.Invoke();
        if (st == EqualizerApo.State.Ready) ApplyNow();
    }

    // ------------------------------------------------------------------ hangerő

    public int CapPercent => Math.Clamp(Store.Prefs.MaxVolumePercent, 10, 100);

    public void SetVolume(float v, bool saveToProfile = true)
    {
        v = Math.Min(v, CapPercent / 100f);
        _audio.SetVolume(v);
        Volume = _audio.GetVolume() ?? v;
        VolumeChanged?.Invoke();
        if (saveToProfile && Store.Prefs.RestoreVolume && Device is { } d)
            Store.UpsertDevice(d.Id, d.Name, volume: Volume);
    }

    public void SetMute(bool mute)
    {
        _audio.SetMute(mute);
        Muted = mute;
        VolumeChanged?.Invoke();
    }

    public List<OutputDevice> AllDevices() => _audio.GetAll();

    // ------------------------------------------------------------------ belső

    private void Poll()
    {
        // 1) eszközváltás (pl. Bluetooth fejhallgató csatlakozott, vagy kiválasztottál másik kimenetet)
        var dev = _audio.GetDefault();
        if (dev?.Id != Device?.Id)
        {
            PersistNow();
            Device = dev;
            OnDeviceChanged(dev);
            DeviceChanged?.Invoke();
        }

        // 2) hangerő + hallásvédelem
        var vol = _audio.GetVolume();
        var mute = _audio.GetMute() ?? false;
        if (vol is { } v)
        {
            var cap = CapPercent / 100f;
            if (CapPercent < 100 && v > cap + 0.005f)
            {
                _audio.SetVolume(cap);
                v = cap;
            }
            if (Math.Abs(v - Volume) > 0.001f || mute != Muted)
            {
                Volume = v;
                Muted = mute;
                VolumeChanged?.Invoke();
            }
        }

        // 3) Equalizer APO telepítés / beállítás változása – elég 5 másodpercenként
        if (++_tick % 5 == 0) RefreshApoState();
    }

    private void OnDeviceChanged(OutputDevice? dev)
    {
        if (dev == null) return;
        var profile = Store.GetDevice(dev.Id);
        if (Store.Prefs.AutoProfile && profile != null)
        {
            var enabled = Settings.Enabled;
            Settings = profile.Settings with { Enabled = enabled };
            Store.Prefs.Current = Settings;
            Store.SavePrefs();
            SettingsChanged?.Invoke();
            ApplyNow();
            if (Store.Prefs.RestoreVolume && profile.Volume is { } pv) SetVolume(pv, saveToProfile: false);
            Store.UpsertDevice(dev.Id, dev.Name);
        }
        else
        {
            Store.UpsertDevice(dev.Id, dev.Name,
                settings: profile?.Settings ?? Settings,
                volume: profile?.Volume ?? _audio.GetVolume());
            ApplyNow();
        }
    }

    private void ApplyNow()
    {
        if (ApoState != EqualizerApo.State.Ready) return;
        EqualizerApo.Apply(Settings, Device?.Name);
    }

    private void PersistNow()
    {
        _persist.Stop();
        Store.Prefs.Current = Settings;
        Store.SavePrefs();
        if (Store.Prefs.AutoProfile && Device is { } d)
            Store.UpsertDevice(d.Id, d.Name, settings: Settings);
    }

    public void Dispose()
    {
        PersistNow();
        _poll.Dispose();
        _debounce.Dispose();
        _persist.Dispose();
        _audio.Dispose();
    }
}
