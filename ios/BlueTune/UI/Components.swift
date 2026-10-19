import AVKit
import MediaPlayer
import SwiftUI

enum Theme {
    static let bg = Color(red: 8 / 255, green: 11 / 255, blue: 16 / 255)
    static let card = Color(red: 18 / 255, green: 25 / 255, blue: 34 / 255)
    static let cardHigh = Color(red: 23 / 255, green: 32 / 255, blue: 43 / 255)
    static let line = Color(red: 44 / 255, green: 56 / 255, blue: 70 / 255)
    static let track = Color(red: 26 / 255, green: 34 / 255, blue: 45 / 255)
    static let text = Color(red: 230 / 255, green: 237 / 255, blue: 245 / 255)
    static let muted = Color(red: 154 / 255, green: 168 / 255, blue: 184 / 255)
    static let accent = Color(red: 56 / 255, green: 217 / 255, blue: 255 / 255)
    static let violet = Color(red: 139 / 255, green: 123 / 255, blue: 255 / 255)
    static let warn = Color(red: 255 / 255, green: 181 / 255, blue: 71 / 255)
    static let danger = Color(red: 255 / 255, green: 83 / 255, blue: 112 / 255)
}

func formatDb(_ v: Float) -> String { (v > 0.04 ? "+" : "") + String(format: "%.1f dB", v) }

func shortDb(_ v: Float) -> String {
    if abs(v) < 0.05 { return "0" }
    let sign = v > 0 ? "+" : ""
    return v.truncatingRemainder(dividingBy: 1) == 0 ? sign + String(format: "%.0f", v) : sign + String(format: "%.1f", v)
}

func formatTime(_ s: Double) -> String {
    guard s.isFinite, s >= 0 else { return "0:00" }
    let t = Int(s)
    return String(format: "%d:%02d", t / 60, t % 60)
}

/// Lekerekített kártya címmel.
struct Card<Content: View>: View {
    let title: String
    var trailing: AnyView? = nil
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(title).font(.headline).foregroundStyle(Theme.text)
                Spacer()
                if let trailing { trailing }
            }
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

/// Címke + érték + csúszka.
struct LabeledSlider: View {
    let title: String
    @Binding var value: Float
    let range: ClosedRange<Float>
    var step: Float = 0.5
    let valueText: String

    var body: some View {
        VStack(spacing: 2) {
            HStack {
                Text(title).font(.subheadline).foregroundStyle(Theme.text)
                Spacer()
                Text(valueText).font(.system(.subheadline, design: .monospaced)).foregroundStyle(Theme.accent)
            }
            Slider(value: $value, in: range, step: step)
                .tint(Theme.accent)
        }
    }
}

/// Függőleges EQ-csúszka: húzás állít, dupla koppintás nulláz.
struct VerticalSlider: View {
    @Binding var value: Float
    let range: ClosedRange<Float>
    var step: Float = 0.5
    var onReset: () -> Void = {}

    private let knob: CGFloat = 20

    var body: some View {
        GeometryReader { geo in
            let h = geo.size.height
            let w = geo.size.width
            let span = CGFloat(range.upperBound - range.lowerBound)
            let frac = CGFloat(value - range.lowerBound) / span
            let zero = CGFloat(0 - range.lowerBound) / span
            let y = { (f: CGFloat) -> CGFloat in knob / 2 + (1 - f) * (h - knob) }

            ZStack {
                Capsule().fill(Theme.track).frame(width: 6, height: h - knob)
                    .position(x: w / 2, y: h / 2)
                Rectangle().fill(Theme.accent.opacity(0.35)).frame(width: 16, height: 1)
                    .position(x: w / 2, y: y(zero))
                Capsule().fill(Theme.accent)
                    .frame(width: 6, height: max(2, abs(y(frac) - y(zero))))
                    .position(x: w / 2, y: (y(frac) + y(zero)) / 2)
                Circle().fill(Theme.accent).frame(width: knob, height: knob)
                    .overlay(Circle().fill(.white.opacity(0.9)).frame(width: knob * 0.36, height: knob * 0.36))
                    .position(x: w / 2, y: y(frac))
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0).onChanged { g in
                    let f = 1 - (g.location.y - knob / 2) / max(1, h - knob)
                    let raw = range.lowerBound + Float(min(max(f, 0), 1)) * Float(span)
                    let snapped = (raw / step).rounded() * step
                    if snapped != value { value = snapped }
                }
            )
            .simultaneousGesture(TapGesture(count: 2).onEnded { onReset() })
        }
    }
}

/// Frekvenciamenet görbe 20 Hz – 20 kHz, ±18 dB.
struct EqCurveView: View {
    let settings: EqSettings
    var showGrid = true

    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            let minDb: CGFloat = -18
            let maxDb: CGFloat = 18
            func y(_ db: CGFloat) -> CGFloat { h * (1 - (db - minDb) / (maxDb - minDb)) }
            func x(_ hz: CGFloat) -> CGFloat { w * (log(hz) - log(20)) / (log(20000) - log(20)) }

            if showGrid {
                for db: CGFloat in [-12, -6, 0, 6, 12] {
                    var p = Path()
                    p.move(to: CGPoint(x: 0, y: y(db)))
                    p.addLine(to: CGPoint(x: w, y: y(db)))
                    ctx.stroke(p, with: .color(Theme.line.opacity(db == 0 ? 1 : 0.4)), lineWidth: db == 0 ? 1.5 : 1)
                }
                for hz: CGFloat in [100, 1000, 10000] {
                    var p = Path()
                    p.move(to: CGPoint(x: x(hz), y: 0))
                    p.addLine(to: CGPoint(x: x(hz), y: h))
                    ctx.stroke(p, with: .color(Theme.line.opacity(0.4)), lineWidth: 1)
                }
            }

            let color = settings.enabled ? Theme.accent : Theme.muted
            let gains = settings.effectiveGains()
            let steps = 96
            var line = Path()
            var fill = Path()
            for i in 0...steps {
                let hz = 20 * pow(1000, Float(i) / Float(steps))
                var db = settings.gainAt(hz, gains) + settings.preamp
                if settings.bassBoost > 0 { db += settings.bassBoostDb / (1 + pow(hz / 80, 2)) }
                let pt = CGPoint(x: x(CGFloat(hz)), y: y(min(max(CGFloat(db), minDb), maxDb)))
                if i == 0 {
                    line.move(to: pt)
                    fill.move(to: CGPoint(x: pt.x, y: y(0)))
                    fill.addLine(to: pt)
                } else {
                    line.addLine(to: pt)
                    fill.addLine(to: pt)
                }
            }
            fill.addLine(to: CGPoint(x: w, y: y(0)))
            fill.closeSubpath()
            ctx.fill(fill, with: .linearGradient(
                Gradient(colors: [color.opacity(0.35), color.opacity(0.03)]),
                startPoint: .zero, endPoint: CGPoint(x: 0, y: h)))
            ctx.stroke(line, with: .color(color), lineWidth: showGrid ? 2.5 : 1.8)
        }
    }
}

/// Rendszer-hangerő csúszka (az iOS csak ezen keresztül engedi állítani a hangerőt).
struct SystemVolumeView: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        let v = MPVolumeView(frame: .zero)
        v.tintColor = UIColor(red: 56 / 255, green: 217 / 255, blue: 1, alpha: 1)
        return v
    }

    func updateUIView(_ uiView: MPVolumeView, context: Context) {}
}

/// Kimenet választó (AirPlay / Bluetooth eszközök listája).
struct RoutePickerView: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.activeTintColor = UIColor(red: 56 / 255, green: 217 / 255, blue: 1, alpha: 1)
        v.tintColor = .white
        v.prioritizesVideoDevices = false
        return v
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

struct StatusText: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(text.hasPrefix("Hiba") ? Theme.danger : Theme.accent)
    }
}
