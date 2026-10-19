import Foundation

enum Bands {
    static let count = 10
    static let minDb: Float = -15
    static let maxDb: Float = 15
    static let centers: [Float] = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
    static let labels = ["31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"]
    // Ugyanaz a súlyozás, mint Androidon és Windowson
    static let bassWeight: [Float] = [1, 1, 0.8, 0.45, 0.15, 0, 0, 0, 0, 0]
    static let trebleWeight: [Float] = [0, 0, 0, 0, 0, 0.05, 0.2, 0.55, 0.9, 1]
}

/// Hangbeállítás. A JSON formátum megegyezik az Android és a Windows appéval.
struct EqSettings: Codable, Equatable {
    var enabled: Bool = true
    var bands: [Float] = Array(repeating: 0, count: Bands.count)
    var preamp: Float = 0
    var bass: Float = 0
    var treble: Float = 0
    /// 0...1000 – iOS-en extra mély polcszűrő (80 Hz)
    var bassBoost: Int = 0
    /// 0...1000 – iOS-en térhatás (enyhe tér)
    var virtualizer: Int = 0
    /// Hangerő boost dB (0...15)
    var loudness: Float = 0
    /// -1 (bal) ... +1 (jobb)
    var balance: Float = 0
    /// Limiter (túlvezérlés-védelem)
    var limiter: Bool = true
    var presetName: String?

    enum CodingKeys: String, CodingKey {
        case enabled, bands, preamp, bass, treble, bassBoost, virtualizer, loudness, balance, limiter, presetName
    }

    init() {}

    init(bands: [Float], bassBoost: Int = 0, virtualizer: Int = 0, presetName: String? = nil) {
        self.bands = bands
        self.bassBoost = bassBoost
        self.virtualizer = virtualizer
        self.presetName = presetName
    }

    /// Toleráns beolvasás (hiányzó mezők, számként tárolt egészek, túl nagy értékek).
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func num(_ k: CodingKeys) -> Double? {
            if let d = try? c.decode(Double.self, forKey: k) { return d }
            if let s = try? c.decode(String.self, forKey: k) { return Double(s) }
            return nil
        }
        enabled = (try? c.decode(Bool.self, forKey: .enabled)) ?? true
        let raw = (try? c.decode([Double].self, forKey: .bands)) ?? []
        bands = (0..<Bands.count).map { i in
            i < raw.count ? Float(raw[i]).clamped(Bands.minDb, Bands.maxDb) : 0
        }
        preamp = Float(num(.preamp) ?? 0).clamped(-12, 12)
        bass = Float(num(.bass) ?? 0).clamped(-12, 12)
        treble = Float(num(.treble) ?? 0).clamped(-12, 12)
        bassBoost = Int((num(.bassBoost) ?? 0).rounded()).clamped(0, 1000)
        virtualizer = Int((num(.virtualizer) ?? 0).rounded()).clamped(0, 1000)
        loudness = Float(num(.loudness) ?? 0).clamped(0, 15)
        balance = Float(num(.balance) ?? 0).clamped(-1, 1)
        limiter = (try? c.decode(Bool.self, forKey: .limiter)) ?? true
        presetName = try? c.decode(String.self, forKey: .presetName)
    }

    /// Sávonkénti végső erősítés (sáv + basszus + treble).
    func effectiveGains() -> [Float] {
        (0..<Bands.count).map { i in
            let b = i < bands.count ? bands[i] : 0
            return (b + bass * Bands.bassWeight[i] + treble * Bands.trebleWeight[i]).clamped(-24, 24)
        }
    }

    /// Erősítés tetszőleges frekvencián (logaritmikus interpoláció).
    func gainAt(_ hz: Float, _ gains: [Float]) -> Float {
        let c = Bands.centers
        if hz <= c[0] { return gains[0] }
        if hz >= c[c.count - 1] { return gains[gains.count - 1] }
        for i in 0..<(c.count - 1) where hz <= c[i + 1] {
            let t = (log(hz) - log(c[i])) / (log(c[i + 1]) - log(c[i]))
            return gains[i] + (gains[i + 1] - gains[i]) * t
        }
        return gains[gains.count - 1]
    }

    var bassBoostDb: Float { Float(bassBoost) / 1000 * 9 }

    /// Csak a hangzás átvétele (a be/ki állapot marad).
    func withSound(of other: EqSettings, name: String?) -> EqSettings {
        var s = other
        s.enabled = enabled
        s.presetName = name
        return s
    }

    func summary() -> String {
        var parts: [String] = []
        func f(_ v: Float) -> String { (v > 0 ? "+" : "") + String(format: "%.0f", v) }
        if bass != 0 { parts.append("Basszus \(f(bass))") }
        if treble != 0 { parts.append("Magas \(f(treble))") }
        if bassBoost > 0 { parts.append("Boost \(bassBoost / 10)%") }
        if preamp != 0 { parts.append("Pre \(f(preamp))") }
        return parts.isEmpty ? "10 sávos EQ" : parts.joined(separator: " · ")
    }
}

struct Preset: Identifiable, Equatable {
    var id: String { (builtIn ? "b:" : "c:") + name }
    let name: String
    let settings: EqSettings
    var builtIn: Bool = false
}

/// Export/import formátum: [{ "name": ..., "settings": {...} }] – azonos az Android/Windows appéval.
struct PresetDTO: Codable {
    let name: String
    let settings: EqSettings
}

enum BuiltInPresets {
    //                       31  62 125 250 500  1k  2k  4k  8k 16k
    static let all: [Preset] = [
        p("Lapos", [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
        p("Basszus erősítés", [6, 5, 4, 2, 0, 0, 0, 0, 0, 0]),
        p("Mély basszus (sub)", [8, 6, 3, 0, -1, 0, 0, 0, 0, 0], boost: 300),
        p("Magas erősítés", [0, 0, 0, 0, 0, 1, 2, 4, 5, 6]),
        p("Loudness (V-alak)", [5, 4, 1, 0, -2, -2, 0, 2, 4, 5]),
        p("Rock", [4, 3, 2, 0, -1, -1, 1, 3, 4, 4]),
        p("Pop", [-1, 1, 3, 4, 3, 0, -1, -1, 1, 2]),
        p("Hip-hop / Trap", [6, 5, 2, 3, -1, -1, 1, 0, 2, 3], boost: 200),
        p("Elektronikus", [5, 4, 1, 0, -2, 1, 0, 2, 4, 5]),
        p("Jazz", [3, 2, 1, 2, -1, -1, 0, 1, 2, 3]),
        p("Klasszikus", [4, 3, 2, 1, 0, 0, 0, 2, 3, 4]),
        p("Ének / Vokál", [-2, -1, 0, 2, 4, 4, 3, 1, 0, -1]),
        p("Podcast / Beszéd", [-4, -3, -1, 1, 3, 4, 3, 1, -1, -3]),
        p("Autó", [4, 3, 2, 0, 0, 0, 1, 2, 3, 3]),
        p("Fülhallgató korrekció", [4, 3, 1, 0, -1, 0, 1, 2, 0, -2]),
        p("Esti (halk)", [-3, -2, 0, 1, 2, 2, 1, 0, -2, -3]),
        p("Film / Térhatás", [4, 3, 1, 0, 0, 1, 2, 3, 3, 2], virt: 600),
    ]

    private static func p(_ name: String, _ b: [Float], boost: Int = 0, virt: Int = 0) -> Preset {
        Preset(name: name, settings: EqSettings(bands: b, bassBoost: boost, virtualizer: virt, presetName: name), builtIn: true)
    }
}

extension Comparable {
    func clamped(_ lo: Self, _ hi: Self) -> Self { min(max(self, lo), hi) }
}
