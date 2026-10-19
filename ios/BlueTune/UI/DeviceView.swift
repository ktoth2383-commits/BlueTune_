import SwiftUI

struct DeviceView: View {
    @EnvironmentObject var audio: AudioController
    @EnvironmentObject var store: Store
    @State private var message: String?

    private static let dateFormat: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .short
        return f
    }()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    Card(title: "Aktív kimenet") {
                        HStack(spacing: 12) {
                            Image(systemName: audio.outputIsBluetooth ? "headphones" : "speaker.wave.2.fill")
                                .font(.title)
                                .foregroundStyle(Theme.accent)
                            VStack(alignment: .leading) {
                                Text(audio.outputName).font(.title3.bold()).foregroundStyle(Theme.text)
                                Text(audio.outputKind).font(.footnote).foregroundStyle(Theme.muted)
                            }
                            Spacer()
                            RoutePickerView().frame(width: 40, height: 40)
                        }
                        Text("A jobb oldali ikonnal választhatsz másik kimenetet (Bluetooth, AirPlay, autó).")
                            .font(.caption)
                            .foregroundStyle(Theme.muted)
                        Button {
                            if let id = audio.outputId {
                                store.upsertDevice(id: id, name: audio.outputName, settings: audio.settings)
                                message = "Mentve ehhez: \(audio.outputName)"
                            }
                        } label: { Label("Jelenlegi hangzás mentése ehhez az eszközhöz", systemImage: "square.and.arrow.down") }
                            .buttonStyle(.borderedProminent)
                            .tint(Theme.accent)
                        if let message { StatusText(text: message) }
                    }

                    Card(title: "Hangerő") {
                        SystemVolumeView().frame(height: 34)
                        Text("Ez a telefon hangereje. A hangerő-boostot a Hangzás fülön találod.")
                            .font(.caption)
                            .foregroundStyle(Theme.muted)
                    }

                    Card(title: "Eszközprofilok (\(store.devices.count))") {
                        Toggle(isOn: $store.autoProfile) {
                            VStack(alignment: .leading) {
                                Text("Eszközönkénti automatikus profil").foregroundStyle(Theme.text)
                                Text("Minden fejhallgató / hangszóró / autó a saját hangzását kapja csatlakozáskor")
                                    .font(.caption).foregroundStyle(Theme.muted)
                            }
                        }
                        .tint(Theme.accent)

                        if store.devices.isEmpty {
                            Text("Amint szól valamilyen kimeneten a zene, itt megjelenik a profilja.")
                                .font(.footnote)
                                .foregroundStyle(Theme.muted)
                        }
                        ForEach(store.devices) { d in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text((d.id == audio.outputId ? "● " : "") + d.name)
                                        .foregroundStyle(d.id == audio.outputId ? Theme.accent : Theme.text)
                                    Text("\(d.settings.presetName ?? "Egyéni") · \(DeviceView.dateFormat.string(from: d.lastSeen))")
                                        .font(.caption)
                                        .foregroundStyle(Theme.muted)
                                }
                                Spacer()
                                Menu {
                                    Button("Betöltés") {
                                        audio.settings = audio.settings.withSound(of: d.settings, name: d.settings.presetName)
                                    }
                                    Button("Törlés", role: .destructive) { store.deleteDevice(d.id) }
                                } label: { Image(systemName: "ellipsis.circle").foregroundStyle(Theme.muted) }
                            }
                            .padding(.vertical, 4)
                            Divider().overlay(Theme.line)
                        }
                    }

                    Card(title: "Jó tudni") {
                        Text("Az iOS nem engedi, hogy egy app más appok (Spotify, YouTube…) hangját módosítsa, és a "
                             + "Bluetooth akkuszintet / kodeket sem adja ki. Ezért a BlueTune iPhone-on saját lejátszóval "
                             + "szól, a te zenéidre alkalmazza az EQ-t – háttérben és lezárt képernyőn is.")
                            .font(.footnote)
                            .foregroundStyle(Theme.muted)
                    }
                }
                .padding(16)
            }
            .background(Theme.bg)
            .navigationTitle("Eszköz")
            .onAppear { audio.refreshRoute() }
        }
    }
}
