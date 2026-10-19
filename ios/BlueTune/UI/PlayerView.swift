import SwiftUI
import UniformTypeIdentifiers

struct PlayerView: View {
    @EnvironmentObject var audio: AudioController
    @EnvironmentObject var library: Library
    @State private var showImporter = false
    @State private var message: String?
    @State private var scrubbing = false
    @State private var scrubValue: Double = 0

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    nowPlaying
                    libraryCard
                }
                .padding(16)
            }
            .background(Theme.bg)
            .navigationTitle("Lejátszó")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { showImporter = true } label: { Image(systemName: "plus.circle.fill") }
                }
            }
            .fileImporter(isPresented: $showImporter, allowedContentTypes: [.audio], allowsMultipleSelection: true) { result in
                if case let .success(urls) = result {
                    let n = library.importFiles(urls)
                    message = n > 0 ? "\(n) fájl hozzáadva." : "Nem sikerült a fájlokat hozzáadni."
                }
            }
            .onAppear { library.scan() }
            .refreshable { library.scan() }
        }
    }

    // ------------------------------------------------------------ most szól

    private var nowPlaying: some View {
        Card(title: "Most szól") {
            HStack(spacing: 6) {
                Image(systemName: audio.outputIsBluetooth ? "headphones" : "speaker.wave.2.fill")
                Text(audio.outputName).lineLimit(1)
                Spacer()
                RoutePickerView().frame(width: 32, height: 32)
            }
            .font(.footnote)
            .foregroundStyle(Theme.muted)

            Text(audio.currentTrack?.title ?? "Válassz egy számot")
                .font(.title3.bold())
                .foregroundStyle(Theme.text)
                .lineLimit(2)
            Text(audio.currentTrack?.artist ?? (audio.settings.enabled ? "EQ: \(audio.settings.presetName ?? "Egyéni")" : "EQ kikapcsolva"))
                .font(.subheadline)
                .foregroundStyle(Theme.muted)

            Slider(
                value: Binding(
                    get: { scrubbing ? scrubValue : audio.elapsed },
                    set: { scrubValue = $0 }
                ),
                in: 0...max(audio.duration, 1),
                onEditingChanged: { editing in
                    if editing {
                        scrubValue = audio.elapsed
                        scrubbing = true
                    } else {
                        audio.seek(to: scrubValue)
                        scrubbing = false
                    }
                }
            )
            .tint(Theme.accent)
            .disabled(audio.currentTrack == nil)

            HStack {
                Text(formatTime(scrubbing ? scrubValue : audio.elapsed))
                Spacer()
                Text(formatTime(audio.duration))
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(Theme.muted)

            HStack(spacing: 28) {
                Button { audio.shuffle.toggle() } label: {
                    Image(systemName: "shuffle").foregroundStyle(audio.shuffle ? Theme.accent : Theme.muted)
                }
                Button { audio.previous() } label: { Image(systemName: "backward.fill") }
                Button { audio.togglePlay() } label: {
                    Image(systemName: audio.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 54))
                }
                Button { audio.next() } label: { Image(systemName: "forward.fill") }
                Button {
                    audio.repeatMode = AudioController.RepeatMode(rawValue: (audio.repeatMode.rawValue + 1) % 3) ?? .off
                } label: {
                    Image(systemName: audio.repeatMode == .one ? "repeat.1" : "repeat")
                        .foregroundStyle(audio.repeatMode == .off ? Theme.muted : Theme.accent)
                }
            }
            .font(.title2)
            .foregroundStyle(Theme.text)
            .frame(maxWidth: .infinity)
            .disabled(library.tracks.isEmpty)

            HStack {
                Toggle(isOn: $audio.settings.enabled) { Text("EQ").foregroundStyle(Theme.text) }
                    .toggleStyle(.switch)
                    .tint(Theme.accent)
                    .fixedSize()
                Spacer()
                Button(role: .destructive) { audio.stopAll() } label: {
                    Label("Leállítás", systemImage: "stop.fill")
                }
                .buttonStyle(.bordered)
                .tint(Theme.danger)
                .disabled(!audio.isRunning)
            }
            Text(audio.isRunning
                 ? "Háttérben és lezárt képernyőn is szól. A „Leállítás” mindent leállít."
                 : "A hangmotor áll. Indíts el egy számot.")
                .font(.caption)
                .foregroundStyle(Theme.muted)
            if let e = audio.lastError { Text(e).font(.footnote).foregroundStyle(Theme.danger) }
        }
    }

    // ------------------------------------------------------------ könyvtár

    private var libraryCard: some View {
        Card(title: "Zenék (\(library.tracks.count))") {
            if let message { StatusText(text: message) }
            if library.tracks.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Még nincs zene a BlueTune-ban.").foregroundStyle(Theme.text)
                    Text("• Koppints a + gombra, és válassz hangfájlokat a Fájlok appból / iCloud Drive-ról.\n"
                         + "• Vagy a Fájlok app → Saját iPhone → BlueTune mappába másold őket.\n"
                         + "• Támogatott: MP3, M4A/AAC, WAV, AIFF, FLAC, ALAC (DRM nélkül).")
                        .font(.footnote)
                        .foregroundStyle(Theme.muted)
                    Button { showImporter = true } label: { Label("Zene hozzáadása", systemImage: "plus") }
                        .buttonStyle(.borderedProminent)
                        .tint(Theme.accent)
                }
            } else {
                LazyVStack(spacing: 0) {
                    ForEach(Array(library.tracks.enumerated()), id: \.element.id) { index, track in
                        TrackRow(track: track, active: audio.currentIndex == index, playing: audio.isPlaying)
                            .contentShape(Rectangle())
                            .onTapGesture { audio.play(index: index) }
                            .contextMenu {
                                Button { audio.play(index: index) } label: { Label("Lejátszás", systemImage: "play") }
                                Button(role: .destructive) {
                                    if audio.currentIndex == index { audio.stopAll() }
                                    library.delete(track)
                                } label: { Label("Törlés", systemImage: "trash") }
                            }
                        Divider().overlay(Theme.line)
                    }
                }
                Text("Tipp: tartsd nyomva egy számot a törléshez.")
                    .font(.caption)
                    .foregroundStyle(Theme.muted)
            }
        }
    }
}

private struct TrackRow: View {
    let track: Track
    let active: Bool
    let playing: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: active ? (playing ? "speaker.wave.2.fill" : "pause.fill") : "music.note")
                .foregroundStyle(active ? Theme.accent : Theme.muted)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title).foregroundStyle(active ? Theme.accent : Theme.text).lineLimit(1)
                if let a = track.artist { Text(a).font(.caption).foregroundStyle(Theme.muted).lineLimit(1) }
            }
            Spacer()
            Text(formatTime(track.duration)).font(.caption.monospacedDigit()).foregroundStyle(Theme.muted)
        }
        .padding(.vertical, 10)
    }
}
