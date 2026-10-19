using System.Runtime.InteropServices;
using BlueTune.Desktop.Audio;

namespace BlueTune.Desktop.UI;

internal static class Theme
{
    public static readonly Color Bg = Color.FromArgb(8, 11, 16);
    public static readonly Color Card = Color.FromArgb(18, 25, 34);
    public static readonly Color CardHigh = Color.FromArgb(23, 32, 43);
    public static readonly Color Line = Color.FromArgb(44, 56, 70);
    public static readonly Color Track = Color.FromArgb(26, 34, 45);
    public static readonly Color Text = Color.FromArgb(230, 237, 245);
    public static readonly Color Muted = Color.FromArgb(154, 168, 184);
    public static readonly Color Accent = Color.FromArgb(56, 217, 255);
    public static readonly Color Violet = Color.FromArgb(139, 123, 255);
    public static readonly Color Warn = Color.FromArgb(255, 181, 71);
    public static readonly Color Danger = Color.FromArgb(255, 83, 112);

    public static readonly Font Body = new("Segoe UI", 9.75f);
    public static readonly Font Small = new("Segoe UI", 8.5f);
    public static readonly Font Title = new("Segoe UI Semibold", 11.5f);
    public static readonly Font Big = new("Segoe UI Semibold", 15f);
    public static readonly Font Mono = new("Consolas", 9f);

    /// <summary>Sötét címsor Windows 10 (20H1+) / 11 alatt.</summary>
    public static void DarkTitleBar(Form f)
    {
        try
        {
            var on = 1;
            if (NativeMethods.DwmSetWindowAttribute(f.Handle, 20, ref on, sizeof(int)) != 0)
                NativeMethods.DwmSetWindowAttribute(f.Handle, 19, ref on, sizeof(int));
        }
        catch { /* régebbi Windows – nem baj */ }
    }

    public static Button Button(string text, bool primary = false)
    {
        var b = new Button
        {
            Text = text,
            AutoSize = true,
            FlatStyle = FlatStyle.Flat,
            BackColor = primary ? Accent : CardHigh,
            ForeColor = primary ? Color.FromArgb(0, 34, 44) : Text,
            Font = primary ? new Font("Segoe UI Semibold", 9.75f) : Body,
            Padding = new Padding(10, 4, 10, 4),
            Margin = new Padding(0, 4, 8, 4),
            Cursor = Cursors.Hand,
            UseVisualStyleBackColor = false,
        };
        b.FlatAppearance.BorderColor = primary ? Accent : Line;
        b.FlatAppearance.BorderSize = 1;
        return b;
    }

    public static Label Label(string text, Font? font = null, Color? color = null) => new()
    {
        Text = text,
        AutoSize = true,
        Font = font ?? Body,
        ForeColor = color ?? Text,
        BackColor = Color.Transparent,
        Margin = new Padding(0, 2, 0, 2),
    };

    public static CheckBox Check(string text, bool value) => new()
    {
        Text = text,
        Checked = value,
        AutoSize = true,
        Font = Body,
        ForeColor = Text,
        BackColor = Color.Transparent,
        Margin = new Padding(0, 4, 0, 4),
        Cursor = Cursors.Hand,
    };
}

/// <summary>
/// Lekerekített „kártya” címmel. A sorokat az <see cref="Add{T}"/> egymás alá rakja,
/// a kártya magassága ehhez igazodik (nincs AutoSize-varázslat, így kiszámítható).
/// </summary>
internal sealed class Card : Panel
{
    private const int Pad = 16;
    private const int Gap = 6;
    private int _y = 44;

    [System.ComponentModel.DesignerSerializationVisibility(System.ComponentModel.DesignerSerializationVisibility.Hidden)]
    public string Title { get; set; }

    public Card(string title, int width)
    {
        Title = title;
        Width = width;
        Height = _y + Pad;
        DoubleBuffered = true;
        BackColor = Theme.Card;
        ForeColor = Theme.Text;
        Margin = new Padding(0, 0, 0, 12);
    }

    /// <summary>Belső szélesség (a sorok ennyi helyet kapnak).</summary>
    public int Inner => Width - 2 * Pad;

    public T Add<T>(T c, int? height = null, bool fullWidth = true) where T : Control
    {
        c.Location = new Point(Pad, _y);
        if (fullWidth) c.Width = Inner;
        if (height is { } h) c.Height = h;
        if (c.BackColor == SystemColors.Control || c.BackColor == Color.Transparent) c.BackColor = BackColor;
        Controls.Add(c);
        _y += c.Height + Gap;
        Height = _y + Pad - Gap;
        return c;
    }

    /// <summary>Vízszintes gombsor.</summary>
    public FlowLayoutPanel AddRow(params Control[] items)
    {
        var row = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = true,
            BackColor = BackColor,
            Margin = Padding.Empty,
            Padding = Padding.Empty,
        };
        row.Controls.AddRange(items);
        row.Width = Inner;
        // magasság: a gombok tördelése után
        row.Height = row.GetPreferredSize(new Size(Inner, 0)).Height;
        return Add(row, Math.Max(row.Height, 36));
    }

    public void AddSpace(int px)
    {
        _y += px;
        Height = _y + Pad - Gap;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        TextRenderer.DrawText(e.Graphics, Title, Theme.Title, new Point(Pad, 14), Theme.Text);
    }

    protected override void OnPaintBackground(PaintEventArgs e)
    {
        e.Graphics.Clear(Parent?.BackColor ?? Theme.Bg);
        e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
        using var path = RoundRect(new Rectangle(0, 0, Width - 1, Height - 1), 14);
        using var brush = new SolidBrush(BackColor);
        e.Graphics.FillPath(brush, path);
    }

    public static System.Drawing.Drawing2D.GraphicsPath RoundRect(Rectangle r, int radius)
    {
        var d = radius * 2;
        var p = new System.Drawing.Drawing2D.GraphicsPath();
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }
}

/// <summary>Címke + érték + vízszintes csúszka egy sorban.</summary>
internal sealed class SliderRow : Panel
{
    public DarkSlider Slider { get; }
    private readonly Label _title;
    private readonly Label _value;

    public SliderRow(string title, float min, float max, float step, float def = 0f, bool bipolar = false)
    {
        Height = 50;
        BackColor = Theme.Card;
        _title = Theme.Label(title);
        _title.Location = new Point(0, 0);
        _value = Theme.Label("", Theme.Mono, Theme.Accent);
        _value.AutoSize = false;
        _value.TextAlign = ContentAlignment.MiddleRight;
        _value.Height = 20;
        Slider = new DarkSlider
        {
            Minimum = min, Maximum = max, Step = step, DefaultValue = def, Bipolar = bipolar,
            Height = 26, Location = new Point(0, 22),
        };
        Controls.Add(_title);
        Controls.Add(_value);
        Controls.Add(Slider);
    }

    [System.ComponentModel.DesignerSerializationVisibility(System.ComponentModel.DesignerSerializationVisibility.Hidden)]
    public string ValueText { get => _value.Text; set => _value.Text = value; }

    [System.ComponentModel.DesignerSerializationVisibility(System.ComponentModel.DesignerSerializationVisibility.Hidden)]
    public string TitleText { get => _title.Text; set => _title.Text = value; }

    protected override void OnResize(EventArgs e)
    {
        base.OnResize(e);
        if (Slider == null) return;
        Slider.Width = Width;
        _value.Width = 120;
        _value.Location = new Point(Width - 120, 0);
    }
}
