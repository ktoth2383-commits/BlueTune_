import SwiftUI

struct SoundView: View {
    @EnvironmentObject var audio: AudioController
    @EnvironmentObject var store: Store
    @State private var showSave = false
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    header
                    eqCard
                    toneCard
                    quickPresets
                    HStack {
                        Button {
                            newName = audio.settings.presetName ?? ""
                            showSave = true
                        } label: { Label("Mentés presetként", systemImage: "square.and.arrow.down") }
                            .buttonStyle(.borderedProminent)
                            .tint(Theme.accent)
                        Spacer()
                        Button("Alaphelyzet") {
                            audio.settings = audio.settings.withSound(of: EqSettings(), name: nil)
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .padding(16)
            }
            .background(Theme.bg)
            .navigationTitle("Hangzás")
            .alert("Mentés presetként", isPresented: $showSave) {
                TextField("Preset neve", text: $newName)
                Button("Mentés") {
                    let name = String(newName.trimmingCharacters(in: .whitespaces).prefix(40))
                    guard !name.isEmpty else { return }
                    store.savePreset(name: name, settings: audio.settings)
                    audio.settings.presetName = name
                }
                Button("Mégse", role: .cancel) {}
            }
        }
    }

    private var header: some View {
        Card(title: audio.outputName) {
            Text(audio.outputKind).font(.footnote).foregroundStyle(Theme.muted)
            Toggle(isOn: $audio.settings.enabled) {
                Text(audio.settings.enabled ? "EQ aktív · \(audio.settings.presetName ?? "Egyéni")" : "EQ kikapcsolva")
                    .foregroundStyle(audio.settings.enabled ? Theme.accent : Theme.muted)
            }
            .tint(Theme.accent)
            Text("A hangzás a BlueTune lejátszójára hat. Minden kimenet (Bluetooth fejhallgató, autó, hangszóró) "
                 + "saját profilt kap, amit csatlakozáskor automatikusan visszatölt.")
                .font(.caption)
                .foregroundStyle(Theme.muted)
        }
    }

    private var eqCard: some View {
        Card(title: "10 sávos EQ", trailing: AnyView(
            Button("Nullázás") {
                audio.settings.bands = Array(repeating: 0, count: Bands.count)
                audio.settings.preamp = 0
                audio.settings.presetName = nil
            }.font(.subheadline)
        )) {
            EqCurveView(settings: audio.settings).frame(height: 110)
            HStack(spacing: 0) {
                ForEach(0..<Bands.count, id: \.self) { i in
                    VStack(spacing: 4) {
                        Text(shortDb(audio.settings.bands[i]))
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(abs(audio.settings.bands[i]) > 0.05 ? Theme.accent : Theme.muted)
                        VerticalSlider(
                            value: Binding(
                                get: { audio.settings.bands[i] },
                                set: { v in
                                    audio.settings.bands[i] = v
                                    audio.settings.presetName = nil
                                }
                            ),
                            range: Bands.minDb...Bands.maxDb,
                            onReset: {
                                audio.settings.bands[i] = 0
                                audio.settings.presetName = nil
                            }
                        )
                        .frame(height: 190)
                        Text(Bands.labels[i]).font(.system(size: 10)).foregroundStyle(Theme.muted)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            Text("Dupla koppintás egy sávon = nullázás").font(.caption).foregroundStyle(Theme.muted)
            LabeledSlider(title: "Előerősítés (preamp)", value: $audio.settings.preamp, range: -12...12,
                          valueText: formatDb(audio.settings.preamp))
        }
    }

    private var toneCard: some View {
        Card(title: "Hangszín") {
            LabeledSlider(title: "Basszus", value: Binding(
                get: { audio.settings.bass },
                set: { audio.settings.bass = $0; audio.settings.presetName = nil }
            ), range: -12...12, valueText: formatDb(audio.settings.bass))
            LabeledSlider(title: "Magas hangok (treble)", value: Binding(
                get: { audio.settings.treble },
                set: { audio.settings.treble = $0; audio.settings.presetName = nil }
            ), range: -12...12, valueText: formatDb(audio.settings.treble))
            LabeledSlider(title: "Basszuskiemelés (Bass Boost)", value: Binding(
                get: { Float(audio.settings.bassBoost) / 10 },
                set: { audio.settings.bassBoost = Int(($0 * 10).rounded()) }
            ), range: 0...100, step: 1, valueText: "\(audio.settings.bassBoost / 10)%")
            LabeledSlider(title: "Térhatás", value: Binding(
                get: { Float(audio.settings.virtualizer) / 10 },
                set: { audio.settings.virtualizer = Int(($0 * 10).rounded()) }
            ), range: 0...100, step: 1, valueText: "\(audio.settings.virtualizer / 10)%")
            LabeledSlider(title: "Balansz (bal ↔ jobb)", value: Binding(
                get: { audio.settings.balance * 100 },
                set: { audio.settings.balance = abs($0) < 3 ? 0 : $0 / 100 }
            ), range: -100...100, step: 5, valueText: balanceText(audio.settings.balance))
            LabeledSlider(title: "Hangerő boost", value: $audio.settings.loudness, range: 0...15,
                          valueText: String(format: "+%.1f dB", audio.settings.loudness))
            if audio.settings.loudness > 6 {
                Text("⚠ Erős boost torzíthat, és hosszan károsíthatja a hallást.")
                    .font(.caption).foregroundStyle(Theme.warn)
            }
            Toggle(isOn: $audio.settings.limiter) {
                VStack(alignment: .leading) {
                    Text("Torzításvédelem (limiter)").foregroundStyle(Theme.text)
                    Text("Megfogja a csúcsokat erős basszus / boost mellett").font(.caption).foregroundStyle(Theme.muted)
                }
            }
            .tint(Theme.accent)
        }
    }

    private var quickPresets: some View {
        Card(title: "Gyors presetek") {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(store.custom + BuiltInPresets.all) { p in
                        let selected = audio.settings.presetName == p.name
                        Button {
                            audio.settings = audio.settings.withSound(of: p.settings, name: p.name)
                        } label: {
                            Text(p.name)
                                .font(.subheadline)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 7)
                                .background(selected ? Theme.accent.opacity(0.25) : Theme.cardHigh, in: Capsule())
                                .overlay(Capsule().stroke(selected ? Theme.accent : Theme.line, lineWidth: 1))
                                .foregroundStyle(selected ? Theme.accent : Theme.text)
                        }
                    }
                }
            }
        }
    }
}

func balanceText(_ b: Float) -> String {
    if abs(b) < 0.01 { return "Közép" }
    return b < 0 ? "B \(Int((abs(b) * 100).rounded()))%" : "J \(Int((b * 100).rounded()))%"
}
