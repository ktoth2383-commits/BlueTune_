import Foundation

/// Kimeneti eszközhöz (Bluetooth fejhallgató, hangszóró, autó…) mentett hangzás.
struct DeviceProfile: Codable, Identifiable, Equatable {
    let id: String
    var name: String
    var settings: EqSettings
    var lastSeen: Date
}

/// Helyi tárolás: presetek, eszközprofilok, beállítások (Application Support / UserDefaults).
final class Store: ObservableObject {
    static let shared = Store()

    @Published private(set) var custom: [Preset] = []
    @Published private(set) var devices: [DeviceProfile] = []

    @Published var autoProfile: Bool {
        didSet { defaults.set(autoProfile, forKey: "autoProfile") }
    }

    private let defaults = UserDefaults.standard
    private let dir: URL

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = base.appendingPathComponent("BlueTune", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        autoProfile = defaults.object(forKey: "autoProfile") as? Bool ?? true
        custom = Store.parsePresets(data: try? Data(contentsOf: dir.appendingPathComponent("presets.json")))
        if let d = try? Data(contentsOf: dir.appendingPathComponent("devices.json")),
           let list = try? Store.decoder.decode([DeviceProfile].self, from: d) {
            devices = list
        }
    }

    // ------------------------------------------------------------ aktuális hangzás

    var current: EqSettings {
        get {
            guard let d = defaults.data(forKey: "current"),
                  let s = try? Store.decoder.decode(EqSettings.self, from: d) else { return EqSettings() }
            return s
        }
        set {
            if let d = try? Store.encoder.encode(newValue) { defaults.set(d, forKey: "current") }
        }
    }

    // ------------------------------------------------------------ presetek

    func savePreset(name raw: String, settings: EqSettings) {
        let name = String(raw.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
        guard !name.isEmpty else { return }
        var s = settings
        s.enabled = true
        s.presetName = name
        custom.removeAll { $0.name == name }
        custom.insert(Preset(name: name, settings: s), at: 0)
        writePresets()
    }

    func deletePreset(_ name: String) {
        custom.removeAll { $0.name == name }
        writePresets()
    }

    func exportPresetsJSON() -> String {
        let dtos = custom.map { PresetDTO(name: $0.name, settings: $0.settings) }
        guard let d = try? Store.prettyEncoder.encode(dtos) else { return "[]" }
        return String(data: d, encoding: .utf8) ?? "[]"
    }

    /// Visszaadja a beolvasott presetek számát.
    @discardableResult
    func importPresets(json: String) -> Int {
        let incoming = Store.parsePresets(data: json.data(using: .utf8))
        guard !incoming.isEmpty else { return 0 }
        let names = Set(incoming.map(\.name))
        custom.removeAll { names.contains($0.name) }
        custom.insert(contentsOf: incoming, at: 0)
        writePresets()
        return incoming.count
    }

    private func writePresets() {
        try? Data(exportPresetsJSON().utf8).write(to: dir.appendingPathComponent("presets.json"), options: .atomic)
    }

    private static func parsePresets(data: Data?) -> [Preset] {
        guard let data else { return [] }
        let dtos: [PresetDTO]
        if let list = try? decoder.decode([PresetDTO].self, from: data) {
            dtos = list
        } else if let one = try? decoder.decode(PresetDTO.self, from: data) {
            dtos = [one]
        } else {
            return []
        }
        return dtos.compactMap { dto in
            let name = String(dto.name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
            guard !name.isEmpty else { return nil }
            var s = dto.settings
            s.presetName = name
            return Preset(name: name, settings: s)
        }
    }

    // ------------------------------------------------------------ eszközprofilok

    func device(_ id: String) -> DeviceProfile? { devices.first { $0.id == id } }

    func upsertDevice(id: String, name: String, settings: EqSettings? = nil) {
        let old = device(id)
        let p = DeviceProfile(id: id, name: name, settings: settings ?? old?.settings ?? EqSettings(), lastSeen: Date())
        devices.removeAll { $0.id == id }
        devices.insert(p, at: 0)
        writeDevices()
    }

    func deleteDevice(_ id: String) {
        devices.removeAll { $0.id == id }
        writeDevices()
    }

    func exportDevicesJSON() -> String {
        guard let d = try? Store.encoder.encode(devices) else { return "[]" }
        return String(data: d, encoding: .utf8) ?? "[]"
    }

    @discardableResult
    func importDevices(json: String) -> Int {
        guard let d = json.data(using: .utf8),
              let list = try? Store.decoder.decode([DeviceProfile].self, from: d), !list.isEmpty else { return 0 }
        let ids = Set(list.map(\.id))
        devices.removeAll { ids.contains($0.id) }
        devices.insert(contentsOf: list, at: 0)
        writeDevices()
        return list.count
    }

    private func writeDevices() {
        guard let d = try? Store.encoder.encode(devices) else { return }
        try? d.write(to: dir.appendingPathComponent("devices.json"), options: .atomic)
    }

    // ------------------------------------------------------------ JSON

    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .millisecondsSince1970
        return e
    }()

    static let prettyEncoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return e
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .millisecondsSince1970
        return d
    }()
}
