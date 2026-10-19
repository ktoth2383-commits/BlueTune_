import AVFoundation
import Foundation

struct Track: Identifiable, Hashable {
    var id: URL { url }
    let url: URL
    var title: String
    var artist: String?
    var duration: Double
}

/// Zenekönyvtár: az app Dokumentumok mappájában lévő hangfájlok.
/// Ide kerülnek az importált fájlok, és a Fájlok appból / számítógépről bemásoltak is.
final class Library: ObservableObject {
    static let shared = Library()
    static let extensions: Set<String> = ["mp3", "m4a", "aac", "wav", "aif", "aiff", "caf", "flac", "mp4", "alac"]

    @Published private(set) var tracks: [Track] = []

    let documents: URL
    let musicFolder: URL

    private init() {
        documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        musicFolder = documents.appendingPathComponent("Zene", isDirectory: true)
        try? FileManager.default.createDirectory(at: musicFolder, withIntermediateDirectories: true)
        scan()
    }

    /// A Dokumentumok mappa (almappákkal) átnézése.
    func scan() {
        let fm = FileManager.default
        var found: [URL] = []
        if let en = fm.enumerator(at: documents, includingPropertiesForKeys: [.isRegularFileKey],
                                  options: [.skipsHiddenFiles]) {
            for case let url as URL in en where Library.extensions.contains(url.pathExtension.lowercased()) {
                found.append(url)
            }
        }
        found.sort { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }

        let old = Dictionary(uniqueKeysWithValues: tracks.map { ($0.url, $0) })
        tracks = found.map { url in
            old[url] ?? Track(url: url, title: url.deletingPathExtension().lastPathComponent, artist: nil,
                              duration: Library.quickDuration(url))
        }
        for t in tracks where old[t.url] == nil { loadMetadata(for: t.url) }
    }

    /// Fájlok importálása (Fájlok app, iCloud Drive, letöltések…) – bemásoljuk a Zene mappába.
    @discardableResult
    func importFiles(_ urls: [URL]) -> Int {
        var n = 0
        let fm = FileManager.default
        for src in urls {
            let scoped = src.startAccessingSecurityScopedResource()
            defer { if scoped { src.stopAccessingSecurityScopedResource() } }
            var dest = musicFolder.appendingPathComponent(src.lastPathComponent)
            var i = 1
            while fm.fileExists(atPath: dest.path) {
                let base = src.deletingPathExtension().lastPathComponent
                dest = musicFolder.appendingPathComponent("\(base) (\(i)).\(src.pathExtension)")
                i += 1
            }
            do {
                try fm.copyItem(at: src, to: dest)
                n += 1
            } catch {
                continue
            }
        }
        scan()
        return n
    }

    func delete(_ track: Track) {
        try? FileManager.default.removeItem(at: track.url)
        scan()
    }

    private static func quickDuration(_ url: URL) -> Double {
        guard let f = try? AVAudioFile(forReading: url) else { return 0 }
        let rate = f.processingFormat.sampleRate
        return rate > 0 ? Double(f.length) / rate : 0
    }

    private func loadMetadata(for url: URL) {
        Task {
            let asset = AVURLAsset(url: url)
            guard let items = try? await asset.load(.commonMetadata) else { return }
            var title: String?
            var artist: String?
            for item in items {
                if item.commonKey == .commonKeyTitle { title = try? await item.load(.stringValue) }
                if item.commonKey == .commonKeyArtist { artist = try? await item.load(.stringValue) }
            }
            guard title != nil || artist != nil else { return }
            await MainActor.run {
                if let idx = self.tracks.firstIndex(where: { $0.url == url }) {
                    if let title, !title.isEmpty { self.tracks[idx].title = title }
                    self.tracks[idx].artist = artist
                }
            }
        }
    }
}
