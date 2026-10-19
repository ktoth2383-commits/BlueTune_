using System.Diagnostics;
using BlueTune.Desktop.Audio;
using BlueTune.Desktop.Core;

namespace BlueTune.Desktop.UI;

/// <summary>A BlueTune főablaka. Bezáráskor nem lép ki, csak a tálcára kerül.</summary>
internal sealed class MainForm : Form
{
    private const int LeftW = 600;
    private const int RightW = 420;

    private readonly Engine _engine;
    private readonly Action _exitApp;

    // fejléc
    private readonly Label _devName = Theme.Label("", Theme.Big);
    private readonly Label _devKind = Theme.Label("", Theme.Small, Theme.Muted);
    private readonly CheckBox _enabled = Theme.Check("EQ bekapcsolva", true);
    private readonly Label _apoStatus = Theme.Label("", Theme.Body);
    private readonly Button _apoAction = Theme.Button("", primary: true);
    private readonly Button _apoConfigurator = Theme.Button("Eszközök kiválasztása (Configurator)");

    // hangerő
    private readonly SliderRow _volume = new("Hangerő", 0, 100, 1, 50);
    private readonly CheckBox _mute = Theme.Check("Némítás", false);
    private readonly SliderRow _loudness = new("Hangerő boost", 0, 15, 0.5f);
    private readonly Label _loudWarn = Theme.Label("", Theme.Small, Theme.Warn);

    // EQ
    private readonly CurveView _curve = new();
    private readonly DarkSlider[] _bands = new DarkSlider[Bands.Count];
    private readonly Label[] _bandValues = new Label[Bands.Count];
    private readonly SliderRow _preamp = new("Előerősítés (preamp)", -12, 12, 0.5f, bipolar: true);

    // hangszín
    private readonly SliderRow _bass = new("Basszus", -12, 12, 0.5f, bipolar: true);
    private readonly SliderRow _treble = new("Magas hangok (treble)", -12, 12, 0.5f, bipolar: true);
    private readonly SliderRow _bassBoost = new("Basszuskiemelés (Bass Boost)", 0, 100, 1);
    private readonly SliderRow _balance = new("Balansz (bal ↔ jobb)", -100, 100, 5, bipolar: true);
    private readonly CheckBox _protect = Theme.Check("Túlvezérlés-védelem (automatikus preamp)", true);

    // presetek
    private readonly ListBox _presets = DarkList();
    // eszközök
    private readonly ListBox _devices = DarkList();
    private List<DeviceProfile> _deviceRows = [];
    private List<Preset> _presetRows = [];

    // beállítások
    private readonly CheckBox _autostart = Theme.Check("Indítás a Windows-zal (a tálcán, háttérben)", true);
    private readonly CheckBox _autoProfile = Theme.Check("Eszközönkénti automatikus profil", true);
    private readonly CheckBox _restoreVol = Theme.Check("Hangerő visszaállítása eszközönként", true);
    private readonly SliderRow _maxVol = new("Hallásvédelem: max. hangerő", 30, 100, 5, 100);

    private bool _syncing;

    public MainForm(Engine engine, Action exitApp)
    {
        _engine = engine;
        _exitApp = exitApp;

        // Minden méret 96 DPI-re van megadva; nagyobb kijelzőskálázásnál a WinForms arányosan felnagyítja
        AutoScaleDimensions = new SizeF(96F, 96F);
        Text = "BlueTune";
        Icon = TrayContext.AppIcon;
        BackColor = Theme.Bg;
        ForeColor = Theme.Text;
        Font = Theme.Body;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(LeftW + RightW + 16 * 3 + SystemInformation.VerticalScrollBarWidth, 760);
        MinimumSize = new Size(700, 500);

        var scroll = new Panel { Dock = DockStyle.Fill, AutoScroll = true, BackColor = Theme.Bg };
        Controls.Add(scroll);

        var left = Column(16, LeftW);
        var right = Column(16 + LeftW + 16, RightW);
        scroll.Controls.Add(left);
        scroll.Controls.Add(right);

        Stack(left, BuildHeader(), BuildVolume(), BuildEq());
        Stack(right, BuildTone(), BuildPresets(), BuildDevices(), BuildSettings());

        _engine.SettingsChanged += SyncFromSettings;
        _engine.DeviceChanged += SyncDevice;
        _engine.VolumeChanged += SyncVolume;
        _engine.ApoStateChanged += SyncApo;
        Store.PresetsChanged += SyncPresets;
        Store.DevicesChanged += SyncDevices;

        SyncAll();
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Theme.DarkTitleBar(this);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // Az X gomb nem lép ki – a BlueTune a tálcán fut tovább
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            TrayContext.Current?.ShowHiddenHint();
            return;
        }
        base.OnFormClosing(e);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _engine.SettingsChanged -= SyncFromSettings;
            _engine.DeviceChanged -= SyncDevice;
            _engine.VolumeChanged -= SyncVolume;
            _engine.ApoStateChanged -= SyncApo;
            Store.PresetsChanged -= SyncPresets;
            Store.DevicesChanged -= SyncDevices;
        }
        base.Dispose(disposing);
    }

    // ================================================================== kártyák

    private Card BuildHeader()
    {
        var c = new Card("Aktív hangkimenet", LeftW);
        c.Add(_devName, 32);
        c.Add(_devKind, 20);
        _enabled.Font = Theme.Title;
        _enabled.CheckedChanged += (_, _) => { if (!_syncing) _engine.SetEnabled(_enabled.Checked); };
        c.Add(_enabled, 30);
        c.AddSpace(4);
        _apoStatus.AutoSize = false;
        c.Add(_apoStatus, 40);
        _apoAction.Click += (_, _) => OnApoAction();
        _apoConfigurator.Click += (_, _) => RunSafe(EqualizerApo.OpenConfigurator);
        var stop = Theme.Button("⏹ Leállítás (kilépés)");
        stop.ForeColor = Theme.Danger;
        stop.Click += (_, _) => _exitApp();
        c.AddRow(_apoAction, _apoConfigurator, stop);
        c.Add(Theme.Label("Az ablak bezárása után a BlueTune a tálcán (az óra mellett) fut tovább.", Theme.Small, Theme.Muted), 20);
        return c;
    }

    private Card BuildVolume()
    {
        var c = new Card("Hangerő", LeftW);
        _volume.Slider.ValueChangedByUser += v => _engine.SetVolume(v / 100f);
        c.Add(_volume);
        _mute.CheckedChanged += (_, _) => { if (!_syncing) _engine.SetMute(_mute.Checked); };
        c.Add(_mute, 26);
        _loudness.Slider.ValueChangedByUser += v => _engine.Update(s => s with { Loudness = v });
        c.Add(_loudness);
        _loudWarn.AutoSize = false;
        c.Add(_loudWarn, 34);
        return c;
    }

    private Card BuildEq()
    {
        var c = new Card("10 sávos EQ", LeftW);
        c.Add(_curve, 120);

        var bandsPanel = new Panel { BackColor = Theme.Card };
        const int sliderH = 190;
        var colW = c.Inner / Bands.Count;
        for (var i = 0; i < Bands.Count; i++)
        {
            var idx = i;
            var val = Theme.Label("0", Theme.Mono, Theme.Muted);
            val.AutoSize = false;
            val.TextAlign = ContentAlignment.MiddleCenter;
            val.SetBounds(i * colW, 0, colW, 18);
            var s = new DarkSlider
            {
                Vertical = true, Bipolar = true, Minimum = Bands.MinDb, Maximum = Bands.MaxDb, Step = 0.5f,
                DefaultValue = 0,
            };
            s.SetBounds(i * colW + (colW - 34) / 2, 20, 34, sliderH);
            s.ValueChangedByUser += v => _engine.Update(st => st.WithBand(idx, v));
            var lbl = Theme.Label(Bands.Labels[i], Theme.Small, Theme.Muted);
            lbl.AutoSize = false;
            lbl.TextAlign = ContentAlignment.MiddleCenter;
            lbl.SetBounds(i * colW, 22 + sliderH, colW, 18);
            bandsPanel.Controls.Add(val);
            bandsPanel.Controls.Add(s);
            bandsPanel.Controls.Add(lbl);
            _bands[i] = s;
            _bandValues[i] = val;
        }
        c.Add(bandsPanel, 22 + sliderH + 20);
        c.Add(Theme.Label("Tipp: dupla kattintás egy csúszkán = nullázás · görgővel finoman állítható", Theme.Small, Theme.Muted), 20);

        _preamp.Slider.ValueChangedByUser += v => _engine.Update(s => s with { Preamp = v });
        c.Add(_preamp);

        var reset = Theme.Button("EQ nullázása");
        reset.Click += (_, _) => _engine.Update(s => s with { Bands = new float[Bands.Count], Preamp = 0, PresetName = null });
        var flat = Theme.Button("Minden alaphelyzetbe");
        flat.Click += (_, _) => _engine.Update(s => s.WithSound(new EqSettings(), null));
        c.AddRow(reset, flat);
        return c;
    }

    private Card BuildTone()
    {
        var c = new Card("Hangszín", RightW);
        _bass.Slider.ValueChangedByUser += v => _engine.Update(s => s with { Bass = v, PresetName = null });
        _treble.Slider.ValueChangedByUser += v => _engine.Update(s => s with { Treble = v, PresetName = null });
        _bassBoost.Slider.ValueChangedByUser += v => _engine.Update(s => s with { BassBoost = (int)(v * 10) });
        _balance.Slider.ValueChangedByUser += v => _engine.Update(s => s with { Balance = v / 100f });
        _protect.CheckedChanged += (_, _) => { if (!_syncing) _engine.Update(s => s with { Limiter = _protect.Checked }); };
        c.Add(_bass);
        c.Add(_treble);
        c.Add(_bassBoost);
        c.Add(_balance);
        c.Add(_protect, 26);
        var spatial = Theme.Button("Térhatás (Windows Sonic)…");
        spatial.Click += (_, _) => RunSafe(() => Process.Start(new ProcessStartInfo("ms-settings:sound") { UseShellExecute = true }));
        c.AddRow(spatial);
        return c;
    }

    private Card BuildPresets()
    {
        var c = new Card("Presetek", RightW);
        _presets.DoubleClick += (_, _) => ApplySelectedPreset();
        c.Add(_presets, 210);

        var apply = Theme.Button("Alkalmaz", primary: true);
        apply.Click += (_, _) => ApplySelectedPreset();
        var save = Theme.Button("Mentés…");
        save.Click += (_, _) => SavePreset();
        var del = Theme.Button("Törlés");
        del.Click += (_, _) =>
        {
            if (SelectedPreset() is { BuiltIn: false } p &&
                MessageBox.Show(this, $"Törlöd: „{p.Name}”?", "BlueTune", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes)
                Store.DeletePreset(p.Name);
        };
        var export = Theme.Button("Export");
        export.Click += (_, _) => ExportPresets();
        var import = Theme.Button("Import");
        import.Click += (_, _) => ImportPresets();
        c.AddRow(apply, save, del, export, import);
        c.Add(Theme.Label("Az export/import formátuma azonos a telefonos appéval.", Theme.Small, Theme.Muted), 20);
        return c;
    }

    private Card BuildDevices()
    {
        var c = new Card("Eszközprofilok", RightW);
        c.Add(_devices, 130);
        var load = Theme.Button("Betöltés");
        load.Click += (_, _) =>
        {
            if (SelectedDevice() is { } d) _engine.Update(s => d.Settings with { Enabled = s.Enabled });
        };
        var saveHere = Theme.Button("Mentés az aktív eszközhöz");
        saveHere.Click += (_, _) =>
        {
            if (_engine.Device is { } d)
                Store.UpsertDevice(d.Id, d.Name, settings: _engine.Settings, volume: _engine.Volume);
        };
        var del = Theme.Button("Törlés");
        del.Click += (_, _) => { if (SelectedDevice() is { } d) Store.DeleteDevice(d.Id); };
        c.AddRow(load, saveHere, del);
        return c;
    }

    private Card BuildSettings()
    {
        var c = new Card("Beállítások", RightW);
        _autostart.CheckedChanged += (_, _) =>
        {
            if (_syncing) return;
            Store.Prefs.StartWithWindows = _autostart.Checked;
            Store.SavePrefs();
            Autostart.Sync();
        };
        _autoProfile.CheckedChanged += (_, _) => { if (!_syncing) { Store.Prefs.AutoProfile = _autoProfile.Checked; Store.SavePrefs(); } };
        _restoreVol.CheckedChanged += (_, _) => { if (!_syncing) { Store.Prefs.RestoreVolume = _restoreVol.Checked; Store.SavePrefs(); } };
        _maxVol.Slider.ValueChangedByUser += v =>
        {
            Store.Prefs.MaxVolumePercent = (int)v;
            Store.SavePrefs();
            _maxVol.ValueText = v >= 100 ? "Nincs korlát" : $"{v:0}%";
            if (_engine.Volume > v / 100f) _engine.SetVolume(v / 100f, saveToProfile: false);
        };
        c.Add(_autostart, 26);
        c.Add(_autoProfile, 26);
        c.Add(_restoreVol, 26);
        c.Add(_maxVol);

        var folder = Theme.Button("Adatmappa");
        folder.Click += (_, _) => RunSafe(() => Process.Start(new ProcessStartInfo(Store.Dir) { UseShellExecute = true }));
        var restore = Theme.Button("Equalizer APO visszaállítása");
        restore.Click += (_, _) => RestoreApo();
        c.AddRow(folder, restore);
        var ver = typeof(MainForm).Assembly.GetName().Version;
        c.Add(Theme.Label($"BlueTune {ver?.ToString(3)} · Windows", Theme.Small, Theme.Muted), 20);
        return c;
    }

    // ================================================================== szinkron (motor → UI)

    private void SyncAll()
    {
        SyncFromSettings();
        SyncDevice();
        SyncVolume();
        SyncApo();
        SyncPresets();
        SyncDevices();
        _syncing = true;
        _autostart.Checked = Store.Prefs.StartWithWindows;
        _autoProfile.Checked = Store.Prefs.AutoProfile;
        _restoreVol.Checked = Store.Prefs.RestoreVolume;
        _maxVol.Slider.Value = Store.Prefs.MaxVolumePercent;
        _maxVol.ValueText = Store.Prefs.MaxVolumePercent >= 100 ? "Nincs korlát" : $"{Store.Prefs.MaxVolumePercent}%";
        _syncing = false;
    }

    private void SyncFromSettings()
    {
        var s = _engine.Settings;
        _syncing = true;
        _enabled.Checked = s.Enabled;
        for (var i = 0; i < Bands.Count; i++)
        {
            _bands[i].Value = s.BandAt(i);
            _bandValues[i].Text = ShortDb(s.BandAt(i));
            _bandValues[i].ForeColor = Math.Abs(s.BandAt(i)) > 0.05f ? Theme.Accent : Theme.Muted;
        }
        _preamp.Slider.Value = s.Preamp; _preamp.ValueText = Db(s.Preamp);
        _bass.Slider.Value = s.Bass; _bass.ValueText = Db(s.Bass);
        _treble.Slider.Value = s.Treble; _treble.ValueText = Db(s.Treble);
        _bassBoost.Slider.Value = s.BassBoost / 10f; _bassBoost.ValueText = $"{s.BassBoost / 10}%";
        _balance.Slider.Value = s.Balance * 100f; _balance.ValueText = BalanceText(s.Balance);
        _loudness.Slider.Value = s.Loudness; _loudness.ValueText = $"+{s.Loudness:0.0} dB";
        _loudWarn.Text = s.Loudness > 6
            ? "⚠ Erős boost torzíthat, és károsíthatja a hallást / a hangszórót."
            : "";
        _protect.Checked = s.Limiter;
        _curve.Settings = s;
        _curve.Invalidate();
        var name = s.PresetName;
        if (name != null) _presets.SelectedIndex = _presetRows.FindIndex(p => p.Name == name);
        _syncing = false;
    }

    private void SyncDevice()
    {
        var d = _engine.Device;
        _devName.Text = d != null ? (d.IsBluetooth ? "🎧 " : "🔈 ") + d.Name : "Nincs hangkimenet";
        _devKind.Text = d != null ? $"{d.Kind} · alapértelmezett lejátszóeszköz" : "Csatlakoztass egy eszközt";
        Text = d != null ? $"BlueTune – {d.Name}" : "BlueTune";
        SyncDevices();
    }

    private void SyncVolume()
    {
        _syncing = true;
        var pct = _engine.Volume * 100f;
        _volume.Slider.Value = pct;
        _volume.ValueText = $"{pct:0}%";
        _mute.Checked = _engine.Muted;
        _syncing = false;
    }

    private void SyncApo()
    {
        switch (_engine.ApoState)
        {
            case EqualizerApo.State.NotInstalled:
                _apoStatus.Text = "⚠ A hangmotor (Equalizer APO) nincs telepítve. Telepítsd, a telepítő végén pipáld be a "
                                  + "Bluetooth eszközödet is, indítsd újra a gépet, majd kattints a „Beállítás” gombra.";
                _apoStatus.ForeColor = Theme.Warn;
                _apoAction.Text = "Equalizer APO letöltése";
                _apoAction.Visible = true;
                _apoConfigurator.Visible = false;
                break;
            case EqualizerApo.State.NeedsSetup:
                _apoStatus.Text = "⚠ Az Equalizer APO telepítve van, de még össze kell kötni a BlueTune-nal (egyszeri, rendszergazdai jóváhagyás).";
                _apoStatus.ForeColor = Theme.Warn;
                _apoAction.Text = "Beállítás";
                _apoAction.Visible = true;
                _apoConfigurator.Visible = true;
                break;
            default:
                _apoStatus.Text = "✓ Hangmotor aktív. Ha egy eszközön nem hallod a hatást, a Configuratorban pipáld be azt az eszközt.";
                _apoStatus.ForeColor = Theme.Accent;
                _apoAction.Visible = false;
                _apoConfigurator.Visible = true;
                break;
        }
    }

    private void SyncPresets()
    {
        _presetRows = [.. Store.Custom, .. BuiltInPresets.All];
        _presets.BeginUpdate();
        _presets.Items.Clear();
        foreach (var p in _presetRows)
            _presets.Items.Add((p.BuiltIn ? "" : "★ ") + p.Name + "   —   " + p.Settings.Summary());
        _presets.EndUpdate();
        var name = _engine.Settings.PresetName;
        if (name != null) _presets.SelectedIndex = _presetRows.FindIndex(p => p.Name == name);
    }

    private void SyncDevices()
    {
        _deviceRows = [.. Store.Devices];
        _devices.BeginUpdate();
        _devices.Items.Clear();
        foreach (var d in _deviceRows)
        {
            var active = d.Id == _engine.Device?.Id ? "● " : "   ";
            var vol = d.Volume is { } v ? $" · hangerő {v * 100:0}%" : "";
            _devices.Items.Add($"{active}{d.Name} — {d.Settings.PresetName ?? "Egyéni"}{vol}");
        }
        _devices.EndUpdate();
    }

    // ================================================================== műveletek

    private void OnApoAction()
    {
        if (_engine.ApoState == EqualizerApo.State.NotInstalled)
        {
            RunSafe(() => Process.Start(new ProcessStartInfo(EqualizerApo.DownloadUrl) { UseShellExecute = true }));
            return;
        }
        var ok = _engine.SetupApo();
        if (!ok)
            MessageBox.Show(this,
                "A beállítás nem sikerült, vagy a rendszergazdai jóváhagyást elutasítottad.\n\n" +
                "Próbáld újra, és a felugró ablakban válaszd az „Igen”-t.",
                "BlueTune", MessageBoxButtons.OK, MessageBoxIcon.Warning);
    }

    private void RestoreApo()
    {
        if (MessageBox.Show(this,
                "Visszaállítod az Equalizer APO eredeti beállítását? A BlueTune ezután nem fog hatni a hangra, "
                + "amíg újra nem kattintasz a „Beállítás” gombra.",
                "BlueTune", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
        try
        {
            var p = Process.Start(new ProcessStartInfo(Environment.ProcessPath!, "--restore-apo") { UseShellExecute = true, Verb = "runas" });
            p?.WaitForExit();
        }
        catch (System.ComponentModel.Win32Exception) { /* elutasítva */ }
        _engine.RefreshApoState();
    }

    private Preset? SelectedPreset() =>
        _presets.SelectedIndex >= 0 && _presets.SelectedIndex < _presetRows.Count ? _presetRows[_presets.SelectedIndex] : null;

    private DeviceProfile? SelectedDevice() =>
        _devices.SelectedIndex >= 0 && _devices.SelectedIndex < _deviceRows.Count ? _deviceRows[_devices.SelectedIndex] : null;

    private void ApplySelectedPreset()
    {
        if (SelectedPreset() is { } p) _engine.ApplyPreset(p);
    }

    private void SavePreset()
    {
        var name = InputDialog.Ask(this, "Mentés presetként", "Preset neve:", _engine.Settings.PresetName ?? "");
        if (string.IsNullOrWhiteSpace(name)) return;
        Store.SavePreset(name, _engine.Settings);
        var clean = name.Trim();
        _engine.Update(s => s with { PresetName = clean.Length > 40 ? clean[..40] : clean });
    }

    private void ExportPresets()
    {
        if (Store.Custom.Count == 0)
        {
            MessageBox.Show(this, "Nincs saját preset. Előbb ments egyet.", "BlueTune");
            return;
        }
        using var dlg = new SaveFileDialog
        {
            FileName = "bluetune-presetek.json",
            Filter = "JSON (*.json)|*.json",
        };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;
        File.WriteAllText(dlg.FileName, Store.ExportPresets());
        RunSafe(() => Clipboard.SetText(Store.ExportPresets()));
        MessageBox.Show(this, "Elmentve (és a vágólapra is kimásolva).", "BlueTune");
    }

    private void ImportPresets()
    {
        string? json = null;
        using (var dlg = new OpenFileDialog { Filter = "JSON (*.json)|*.json|Minden fájl|*.*" })
        {
            if (dlg.ShowDialog(this) == DialogResult.OK) json = File.ReadAllText(dlg.FileName);
        }
        if (json == null && Clipboard.ContainsText() &&
            MessageBox.Show(this, "Nem választottál fájlt. Importáljam a vágólap tartalmát?", "BlueTune",
                MessageBoxButtons.YesNo) == DialogResult.Yes)
            json = Clipboard.GetText();
        if (json == null) return;
        var n = Store.ImportPresets(json);
        MessageBox.Show(this, n > 0 ? $"{n} preset importálva." : "Nem találtam érvényes presetet.", "BlueTune");
    }

    // ================================================================== segédek

    private static Panel Column(int x, int width) => new()
    {
        Location = new Point(x, 16),
        Width = width,
        BackColor = Theme.Bg,
    };

    private static void Stack(Panel column, params Card[] cards)
    {
        var y = 0;
        foreach (var c in cards)
        {
            c.Location = new Point(0, y);
            column.Controls.Add(c);
            y += c.Height + 12;
        }
        column.Height = y + 16;
    }

    private static ListBox DarkList() => new()
    {
        BackColor = Theme.CardHigh,
        ForeColor = Theme.Text,
        BorderStyle = BorderStyle.None,
        Font = Theme.Body,
        IntegralHeight = false,
        ItemHeight = 22,
    };

    private static string Db(float v) => (v > 0.04f ? "+" : "") + $"{v:0.0} dB";

    private static string ShortDb(float v)
    {
        if (Math.Abs(v) < 0.05f) return "0";
        return (v > 0 ? "+" : "") + (v % 1 == 0 ? $"{v:0}" : $"{v:0.0}");
    }

    private static string BalanceText(float b) =>
        Math.Abs(b) < 0.01f ? "Közép" : b < 0 ? $"B {Math.Abs(b) * 100:0}%" : $"J {b * 100:0}%";

    private void RunSafe(Action a)
    {
        try { a(); }
        catch (Exception ex) { MessageBox.Show(this, ex.Message, "BlueTune", MessageBoxButtons.OK, MessageBoxIcon.Warning); }
    }
}

/// <summary>Egyszerű szövegbekérő ablak.</summary>
internal static class InputDialog
{
    public static string? Ask(IWin32Window owner, string title, string prompt, string initial)
    {
        using var f = new Form
        {
            Text = title,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = FormStartPosition.CenterParent,
            MinimizeBox = false,
            MaximizeBox = false,
            ShowInTaskbar = false,
            ClientSize = new Size(360, 130),
            BackColor = Theme.Card,
            ForeColor = Theme.Text,
            Font = Theme.Body,
        };
        var lbl = Theme.Label(prompt);
        lbl.Location = new Point(16, 14);
        var box = new TextBox
        {
            Text = initial,
            MaxLength = 40,
            Location = new Point(16, 40),
            Width = 328,
            BackColor = Theme.CardHigh,
            ForeColor = Theme.Text,
            BorderStyle = BorderStyle.FixedSingle,
        };
        var ok = Theme.Button("Mentés", primary: true);
        ok.DialogResult = DialogResult.OK;
        ok.Location = new Point(176, 80);
        var cancel = Theme.Button("Mégse");
        cancel.DialogResult = DialogResult.Cancel;
        cancel.Location = new Point(264, 80);
        f.Controls.AddRange(new Control[] { lbl, box, ok, cancel });
        f.AcceptButton = ok;
        f.CancelButton = cancel;
        f.HandleCreated += (_, _) => Theme.DarkTitleBar(f);
        return f.ShowDialog(owner) == DialogResult.OK ? box.Text : null;
    }
}
