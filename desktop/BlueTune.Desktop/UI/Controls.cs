using System.ComponentModel;
using System.Drawing.Drawing2D;
using BlueTune.Desktop.Core;

namespace BlueTune.Desktop.UI;

/// <summary>
/// Saját rajzolású csúszka (vízszintes vagy függőleges). Húzás, kattintás, egérgörgő,
/// nyilak; dupla kattintás = alapérték.
/// </summary>
internal sealed class DarkSlider : Control
{
    private float _value;
    private bool _dragging;

    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public float Minimum { get; set; }
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public float Maximum { get; set; } = 1f;
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public float Step { get; set; } = 0.5f;
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public float DefaultValue { get; set; }
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public bool Vertical { get; set; }
    /// <summary>Középről (pl. 0 dB-ről) rajzolja a kitöltést.</summary>
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public bool Bipolar { get; set; }
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public Color FillColor { get; set; } = Theme.Accent;

    /// <summary>Felhasználói változtatás (programból állítva nem hívódik).</summary>
    public event Action<float>? ValueChangedByUser;

    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public float Value
    {
        get => _value;
        set
        {
            var v = Math.Clamp(value, Minimum, Maximum);
            if (Math.Abs(v - _value) < 0.0001f) return;
            _value = v;
            Invalidate();
        }
    }

    public DarkSlider()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.UserPaint | ControlStyles.ResizeRedraw | ControlStyles.Selectable, true);
        BackColor = Theme.Card;
        Cursor = Cursors.Hand;
        TabStop = true;
    }

    private float Frac(float v) => Maximum <= Minimum ? 0 : (v - Minimum) / (Maximum - Minimum);

    private const int Knob = 9;

    private float FromPoint(Point p)
    {
        float f = Vertical
            ? 1f - (p.Y - Knob) / (float)Math.Max(1, Height - 2 * Knob)
            : (p.X - Knob) / (float)Math.Max(1, Width - 2 * Knob);
        f = Math.Clamp(f, 0f, 1f);
        var raw = Minimum + f * (Maximum - Minimum);
        return Snap(raw);
    }

    private float Snap(float v) => Step > 0 ? MathF.Round(v / Step) * Step : v;

    private void SetByUser(float v)
    {
        v = Math.Clamp(Snap(v), Minimum, Maximum);
        if (Math.Abs(v - _value) < 0.0001f) return;
        _value = v;
        Invalidate();
        ValueChangedByUser?.Invoke(v);
    }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        base.OnMouseDown(e);
        if (e.Button != MouseButtons.Left) return;
        Focus();
        _dragging = true;
        Capture = true;
        SetByUser(FromPoint(e.Location));
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        if (_dragging) SetByUser(FromPoint(e.Location));
    }

    protected override void OnMouseUp(MouseEventArgs e)
    {
        base.OnMouseUp(e);
        _dragging = false;
        Capture = false;
    }

    protected override void OnMouseDoubleClick(MouseEventArgs e)
    {
        base.OnMouseDoubleClick(e);
        SetByUser(DefaultValue);
    }

    protected override void OnMouseWheel(MouseEventArgs e)
    {
        base.OnMouseWheel(e);
        var step = Step > 0 ? Step : (Maximum - Minimum) / 100f;
        SetByUser(_value + Math.Sign(e.Delta) * step);
    }

    protected override bool IsInputKey(Keys keyData) =>
        keyData is Keys.Up or Keys.Down or Keys.Left or Keys.Right || base.IsInputKey(keyData);

    protected override void OnKeyDown(KeyEventArgs e)
    {
        base.OnKeyDown(e);
        var step = Step > 0 ? Step : (Maximum - Minimum) / 100f;
        if (e.KeyCode is Keys.Up or Keys.Right) SetByUser(_value + step);
        if (e.KeyCode is Keys.Down or Keys.Left) SetByUser(_value - step);
    }

    protected override void OnGotFocus(EventArgs e) { base.OnGotFocus(e); Invalidate(); }
    protected override void OnLostFocus(EventArgs e) { base.OnLostFocus(e); Invalidate(); }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(BackColor);
        var fill = Enabled ? FillColor : Theme.Muted;

        var f = Frac(_value);
        var zero = Bipolar ? Frac(Math.Clamp(0f, Minimum, Maximum)) : 0f;
        using var trackPen = new Pen(Theme.Track, 6) { StartCap = LineCap.Round, EndCap = LineCap.Round };
        using var fillPen = new Pen(fill, 6) { StartCap = LineCap.Round, EndCap = LineCap.Round };

        if (Vertical)
        {
            var cx = Width / 2f;
            float Y(float fr) => Knob + (1 - fr) * (Height - 2 * Knob);
            g.DrawLine(trackPen, cx, Knob, cx, Height - Knob);
            if (Bipolar)
            {
                using var zp = new Pen(Color.FromArgb(70, fill), 1);
                g.DrawLine(zp, cx - Knob, Y(zero), cx + Knob, Y(zero));
            }
            g.DrawLine(fillPen, cx, Y(zero), cx, Y(f));
            DrawKnob(g, new PointF(cx, Y(f)), fill);
        }
        else
        {
            var cy = Height / 2f;
            float X(float fr) => Knob + fr * (Width - 2 * Knob);
            g.DrawLine(trackPen, Knob, cy, Width - Knob, cy);
            g.DrawLine(fillPen, X(zero), cy, X(f), cy);
            DrawKnob(g, new PointF(X(f), cy), fill);
        }
    }

    private void DrawKnob(Graphics g, PointF c, Color fill)
    {
        using var b = new SolidBrush(fill);
        g.FillEllipse(b, c.X - Knob, c.Y - Knob, Knob * 2, Knob * 2);
        using var w = new SolidBrush(Color.FromArgb(230, 255, 255, 255));
        var r = Knob * 0.38f;
        g.FillEllipse(w, c.X - r, c.Y - r, r * 2, r * 2);
        if (Focused)
        {
            using var p = new Pen(Color.FromArgb(120, fill), 2);
            g.DrawEllipse(p, c.X - Knob - 3, c.Y - Knob - 3, (Knob + 3) * 2, (Knob + 3) * 2);
        }
    }
}

/// <summary>Frekvenciamenet-görbe 20 Hz – 20 kHz, ±18 dB.</summary>
internal sealed class CurveView : Control
{
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public EqSettings? Settings { get; set; }
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public bool ShowGrid { get; set; } = true;

    public CurveView()
    {
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer |
                 ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
        BackColor = Theme.Card;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(BackColor);
        var s = Settings;
        if (s == null || Width < 10 || Height < 10) return;

        const float minDb = -18f, maxDb = 18f;
        float W = Width, H = Height;
        float Y(float db) => H * (1 - (db - minDb) / (maxDb - minDb));
        float X(float hz) => W * ((MathF.Log(hz) - MathF.Log(20f)) / (MathF.Log(20000f) - MathF.Log(20f)));

        if (ShowGrid)
        {
            foreach (var db in new[] { -12f, -6f, 0f, 6f, 12f })
            {
                using var p = new Pen(Color.FromArgb(db == 0 ? 220 : 90, Theme.Line), db == 0 ? 1.5f : 1f);
                g.DrawLine(p, 0, Y(db), W, Y(db));
            }
            foreach (var hz in new[] { 100f, 1000f, 10000f })
            {
                using var p = new Pen(Color.FromArgb(90, Theme.Line));
                g.DrawLine(p, X(hz), 0, X(hz), H);
            }
            TextRenderer.DrawText(g, "100", Theme.Small, new Point((int)X(100) + 3, (int)H - 16), Theme.Muted);
            TextRenderer.DrawText(g, "1k", Theme.Small, new Point((int)X(1000) + 3, (int)H - 16), Theme.Muted);
            TextRenderer.DrawText(g, "10k", Theme.Small, new Point((int)X(10000) + 3, (int)H - 16), Theme.Muted);
        }

        var color = s.Enabled ? Theme.Accent : Theme.Muted;
        var gains = s.EffectiveGains();
        const int steps = 120;
        var pts = new PointF[steps + 1];
        for (var i = 0; i <= steps; i++)
        {
            var hz = 20f * MathF.Pow(1000f, i / (float)steps);
            var db = s.GainAt(hz, gains) + s.Preamp;
            if (s.BassBoost > 0) db += s.BassBoostDb / (1 + MathF.Pow(hz / 80f, 2)); // polc közelítése
            pts[i] = new PointF(X(hz), Y(Math.Clamp(db, minDb, maxDb)));
        }

        using (var path = new GraphicsPath())
        {
            path.AddLine(pts[0].X, Y(0), pts[0].X, pts[0].Y);
            path.AddLines(pts);
            path.AddLine(pts[^1].X, pts[^1].Y, pts[^1].X, Y(0));
            path.CloseFigure();
            using var brush = new LinearGradientBrush(new RectangleF(0, 0, W, H),
                Color.FromArgb(90, color), Color.FromArgb(8, color), LinearGradientMode.Vertical);
            g.FillPath(brush, path);
        }
        using var pen = new Pen(color, ShowGrid ? 2.5f : 1.8f) { LineJoin = LineJoin.Round };
        g.DrawLines(pen, pts);
    }
}
