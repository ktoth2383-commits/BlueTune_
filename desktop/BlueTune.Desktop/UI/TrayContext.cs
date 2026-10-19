using BlueTune.Desktop.Audio;
using BlueTune.Desktop.Core;

namespace BlueTune.Desktop.UI;

/// <summary>
/// Az alkalmazás „gyökere”: a tálcaikon. Az ablak csak egy nézet – bezárva is fut tovább.
/// Kilépni a tálcaikon menüjéből vagy az ablak „Leállítás” gombjával lehet.
/// </summary>
internal sealed class TrayContext : ApplicationContext
{
    public static TrayContext? Current { get; private set; }

    private static Icon? _icon;
    public static Icon AppIcon => _icon ??= LoadIcon();

    private readonly Engine _engine;
    private readonly NotifyIcon _tray;
    private readonly ToolStripMenuItem _miEnabled;
    private readonly ToolStripMenuItem _miPresets;
    private readonly ToolStripMenuItem _miAutostart;
    private readonly SynchronizationContext _ui;
    private MainForm? _form;
    private bool _exiting;

    public TrayContext(bool startHidden)
    {
        Current = this;
        _ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();
        _engine = new Engine();

        var menu = new ContextMenuStrip();
        var open = new ToolStripMenuItem("BlueTune megnyitása", null, (_, _) => ShowMain()) { Font = new Font(menu.Font, FontStyle.Bold) };
        _miEnabled = new ToolStripMenuItem("EQ bekapcsolva", null, (_, _) => _engine.SetEnabled(!_engine.Settings.Enabled));
        _miPresets = new ToolStripMenuItem("Presetek");
        _miAutostart = new ToolStripMenuItem("Indítás a Windows-zal", null, (_, _) =>
        {
            Store.Prefs.StartWithWindows = !Store.Prefs.StartWithWindows;
            Store.SavePrefs();
            Autostart.Sync();
        });
        var stop = new ToolStripMenuItem("⏹ Leállítás (kilépés)", null, (_, _) => Exit());
        menu.Items.AddRange(new ToolStripItem[]
        {
            open, new ToolStripSeparator(), _miEnabled, _miPresets, new ToolStripSeparator(), _miAutostart, new ToolStripSeparator(), stop,
        });
        menu.Opening += (_, _) => RefreshMenu();

        _tray = new NotifyIcon
        {
            Icon = AppIcon,
            Text = "BlueTune",
            ContextMenuStrip = menu,
            Visible = true,
        };
        _tray.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) ShowMain(); };

        _engine.SettingsChanged += UpdateTrayText;
        _engine.DeviceChanged += UpdateTrayText;
        _engine.Start();
        UpdateTrayText();

        // Első indításnál, vagy ha a hangmotor még nincs beállítva, mutassuk az ablakot
        if (!startHidden || _engine.ApoState != EqualizerApo.State.Ready) ShowMain();
    }

    /// <summary>Egy második példány indításakor (pl. újra rákattintasz az exe-re) ez hívódik.</summary>
    public void RequestShow() => _ui.Post(_ => ShowMain(), null);

    public void ShowMain()
    {
        if (_exiting) return;
        if (_form == null || _form.IsDisposed) _form = new MainForm(_engine, Exit);
        if (!_form.Visible) _form.Show();
        if (_form.WindowState == FormWindowState.Minimized) _form.WindowState = FormWindowState.Normal;
        _form.Activate();
        _form.BringToFront();
    }

    /// <summary>Első bezáráskor elmondjuk, hogy a program a tálcán fut tovább.</summary>
    public void ShowHiddenHint()
    {
        if (Store.Prefs.TrayHintShown) return;
        Store.Prefs.TrayHintShown = true;
        Store.SavePrefs();
        _tray.ShowBalloonTip(5000, "A BlueTune a háttérben fut",
            "Az EQ továbbra is aktív. A tálcaikonra kattintva megnyithatod, jobb klikk → Leállítás a kilépéshez.",
            ToolTipIcon.Info);
    }

    private void RefreshMenu()
    {
        _miEnabled.Checked = _engine.Settings.Enabled;
        _miAutostart.Checked = Store.Prefs.StartWithWindows;
        _miPresets.DropDownItems.Clear();
        foreach (var p in Store.Custom.Concat(BuiltInPresets.All))
        {
            var item = new ToolStripMenuItem(p.Name, null, (_, _) => _engine.ApplyPreset(p))
            {
                Checked = _engine.Settings.PresetName == p.Name,
            };
            _miPresets.DropDownItems.Add(item);
        }
    }

    private void UpdateTrayText()
    {
        var s = _engine.Settings;
        var dev = _engine.Device?.Name ?? "nincs eszköz";
        var state = s.Enabled ? (s.PresetName ?? "Egyéni") : "EQ ki";
        var text = $"BlueTune – {state}\n{dev}";
        _tray.Text = text.Length > 127 ? text[..127] : text; // a Windows korlátja
    }

    /// <summary>Teljes leállítás: az effekt lekerül, a program kilép.</summary>
    public void Exit()
    {
        if (_exiting) return;
        _exiting = true;
        try
        {
            // A hangmotor ne maradjon bekapcsolva a BlueTune nélkül
            if (_engine.ApoState == EqualizerApo.State.Ready)
                EqualizerApo.Apply(_engine.Settings with { Enabled = false }, _engine.Device?.Name);
        }
        catch { /* kilépéskor nem kritikus */ }

        _engine.Dispose();
        _tray.Visible = false;
        _tray.Dispose();
        if (_form != null && !_form.IsDisposed)
        {
            _form.Dispose();
        }
        ExitThread();
    }

    private static Icon LoadIcon()
    {
        try
        {
            using var s = typeof(TrayContext).Assembly.GetManifestResourceStream("BlueTune.app.ico");
            if (s != null) return new Icon(s);
        }
        catch { /* tartalék lent */ }
        return SystemIcons.Application;
    }
}
