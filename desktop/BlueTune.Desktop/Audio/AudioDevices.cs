using System.Runtime.InteropServices;

namespace BlueTune.Desktop.Audio;

/// <summary>Egy Windows hangkimenet (pl. Bluetooth fejhallgató, hangszóró, HDMI).</summary>
public sealed record OutputDevice(string Id, string Name, string Kind, bool IsBluetooth);

/// <summary>
/// Hangkimenetek lekérdezése és az alapértelmezett eszköz hangerejének kezelése.
/// Csak a UI-szálról hívd (STA).
/// </summary>
public sealed class AudioDevices : IDisposable
{
    private const int DEVICE_STATE_ACTIVE = 0x1;
    private const int CLSCTX_ALL = 0x17;
    private static readonly Guid IID_IAudioEndpointVolume = new("5CDF2C82-841E-4546-9722-0CF74078229A");

    private static readonly Guid DevPropFmt = new("A45C254E-DF1C-4EFD-8020-67D146A850E0");
    private static PropertyKey PKEY_Device_FriendlyName = new(DevPropFmt, 14);
    private static PropertyKey PKEY_Device_DeviceDesc = new(DevPropFmt, 2);
    private static PropertyKey PKEY_Device_EnumeratorName = new(DevPropFmt, 24);
    private static PropertyKey PKEY_DeviceInterface_FriendlyName = new(new Guid("026E516E-B814-414B-83CD-856D6FEF4822"), 2);

    private readonly IMMDeviceEnumerator _enumerator;
    private Guid _ctx = Guid.Empty;

    // Az utolsó lekért hangerő-vezérlő gyorsítótárazva (eszközváltáskor újrakérjük)
    private string? _volumeDeviceId;
    private IAudioEndpointVolume? _volume;

    public AudioDevices()
    {
        _enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
    }

    /// <summary>Az alapértelmezett lejátszóeszköz (amire a zene szól), vagy null.</summary>
    public OutputDevice? GetDefault()
    {
        if (_enumerator.GetDefaultAudioEndpoint(EDataFlow.Render, ERole.Multimedia, out var dev) != 0 || dev == null)
            return null;
        try { return Describe(dev); }
        finally { Marshal.ReleaseComObject(dev); }
    }

    /// <summary>Az összes aktív lejátszóeszköz.</summary>
    public List<OutputDevice> GetAll()
    {
        var list = new List<OutputDevice>();
        if (_enumerator.EnumAudioEndpoints(EDataFlow.Render, DEVICE_STATE_ACTIVE, out var col) != 0 || col == null)
            return list;
        try
        {
            col.GetCount(out var n);
            for (var i = 0; i < n; i++)
            {
                if (col.Item(i, out var dev) != 0 || dev == null) continue;
                try
                {
                    var d = Describe(dev);
                    if (d != null) list.Add(d);
                }
                finally { Marshal.ReleaseComObject(dev); }
            }
        }
        finally { Marshal.ReleaseComObject(col); }
        return list;
    }

    // ------------------------------------------------------------------ hangerő

    /// <summary>Hangerő 0..1 az alapértelmezett eszközön (null, ha nem elérhető).</summary>
    public float? GetVolume()
    {
        var v = VolumeControl();
        if (v == null) return null;
        return v.GetMasterVolumeLevelScalar(out var level) == 0 ? level : null;
    }

    public void SetVolume(float level)
    {
        var v = VolumeControl();
        v?.SetMasterVolumeLevelScalar(Math.Clamp(level, 0f, 1f), ref _ctx);
    }

    public bool? GetMute()
    {
        var v = VolumeControl();
        if (v == null) return null;
        return v.GetMute(out var m) == 0 ? m : null;
    }

    public void SetMute(bool mute)
    {
        var v = VolumeControl();
        v?.SetMute(mute, ref _ctx);
    }

    private IAudioEndpointVolume? VolumeControl()
    {
        if (_enumerator.GetDefaultAudioEndpoint(EDataFlow.Render, ERole.Multimedia, out var dev) != 0 || dev == null)
        {
            ReleaseVolume();
            return null;
        }
        try
        {
            dev.GetId(out var id);
            if (_volume != null && id == _volumeDeviceId) return _volume;
            ReleaseVolume();
            var iid = IID_IAudioEndpointVolume;
            if (dev.Activate(ref iid, CLSCTX_ALL, IntPtr.Zero, out var obj) != 0) return null;
            _volume = (IAudioEndpointVolume)obj;
            _volumeDeviceId = id;
            return _volume;
        }
        finally { Marshal.ReleaseComObject(dev); }
    }

    private void ReleaseVolume()
    {
        if (_volume != null) Marshal.ReleaseComObject(_volume);
        _volume = null;
        _volumeDeviceId = null;
    }

    // ------------------------------------------------------------------ leírás

    private static OutputDevice? Describe(IMMDevice dev)
    {
        if (dev.GetId(out var id) != 0 || string.IsNullOrEmpty(id)) return null;
        string? friendly = null, desc = null, iface = null, enumerator = null;
        if (dev.OpenPropertyStore(0 /* STGM_READ */, out var store) == 0 && store != null)
        {
            try
            {
                friendly = ReadString(store, PKEY_Device_FriendlyName);
                desc = ReadString(store, PKEY_Device_DeviceDesc);
                iface = ReadString(store, PKEY_DeviceInterface_FriendlyName);
                enumerator = ReadString(store, PKEY_Device_EnumeratorName);
            }
            finally { Marshal.ReleaseComObject(store); }
        }

        var name = friendly ?? iface ?? desc ?? "Hangeszköz";
        var en = (enumerator ?? "").ToUpperInvariant();
        var bt = en.StartsWith("BTH") || en.Contains("BLUETOOTH")
                 || name.Contains("Bluetooth", StringComparison.OrdinalIgnoreCase)
                 || name.Contains("Hands-Free", StringComparison.OrdinalIgnoreCase);
        var kind = en switch
        {
            _ when bt => "Bluetooth",
            "USB" => "USB",
            "HDAUDIO" => (desc ?? "").Contains("HDMI", StringComparison.OrdinalIgnoreCase)
                         || name.Contains("HDMI", StringComparison.OrdinalIgnoreCase) ? "HDMI / kijelző" : "Beépített hangkártya",
            "" => "Hangeszköz",
            _ => en,
        };
        return new OutputDevice(id, name, kind, bt);
    }

    private static string? ReadString(IPropertyStore store, PropertyKey key)
    {
        if (store.GetValue(ref key, out var pv) != 0) return null;
        try
        {
            return pv.vt == 31 /* VT_LPWSTR */ && pv.pointerValue != IntPtr.Zero
                ? Marshal.PtrToStringUni(pv.pointerValue)
                : null;
        }
        finally { NativeMethods.PropVariantClear(ref pv); }
    }

    public void Dispose()
    {
        ReleaseVolume();
        Marshal.ReleaseComObject(_enumerator);
    }
}
