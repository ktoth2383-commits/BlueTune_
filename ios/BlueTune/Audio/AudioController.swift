import AudioToolbox
import AVFoundation
import Foundation
import MediaPlayer

/// A BlueTune hangmotorja iOS-en.
///
/// Az iOS nem engedi más appok (Spotify, YouTube…) hangjának módosítását, ezért a BlueTune
/// saját lejátszóval szól: lejátszó → 10 sávos EQ (+ Bass Boost polc) → limiter → keverő → kimenet.
/// Háttérben és lezárt képernyőn is fut; a „Leállítás” mindent leállít.
final class AudioController: ObservableObject {
    static let shared = AudioController()

    enum RepeatMode: Int { case off, all, one }

    // ------------------------------------------------------------ állapot (UI)

    @Published var settings: EqSettings {
        didSet {
            guard settings != oldValue else { return }
            applyEffects()
            schedulePersist()
        }
    }

    @Published private(set) var currentIndex: Int?
    @Published private(set) var isPlaying = false
    /// Fut-e a hangmotor (ha nem, az app a háttérben nem csinál semmit)
    @Published private(set) var isRunning = false
    @Published private(set) var elapsed: Double = 0
    @Published private(set) var duration: Double = 0
    @Published var shuffle = false
    @Published var repeatMode: RepeatMode = .all

    @Published private(set) var outputName = "—"
    @Published private(set) var outputKind = ""
    @Published private(set) var outputIsBluetooth = false
    @Published private(set) var outputId: String?
    @Published private(set) var lastError: String?

    var currentTrack: Track? {
        guard let i = currentIndex, Library.shared.tracks.indices.contains(i) else { return nil }
        return Library.shared.tracks[i]
    }

    // ------------------------------------------------------------ hangmotor

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let eq = AVAudioUnitEQ(numberOfBands: Bands.count + 1)
    private let reverb = AVAudioUnitReverb()
    private let limiter: AVAudioUnitEffect = {
        let desc = AudioComponentDescription(
            componentType: kAudioUnitType_Effect,
            componentSubType: kAudioUnitSubType_PeakLimiter,
            componentManufacturer: kAudioUnitManufacturer_Apple,
            componentFlags: 0,
            componentFlagsMask: 0)
        return AVAudioUnitEffect(audioComponentDescription: desc)
    }()

    private var file: AVAudioFile?
    private var connectedFormat: AVAudioFormat?
    private var seekFrame: AVAudioFramePosition = 0
    private var generation = 0
    private var ticker: Timer?
    private var persistWork: DispatchWorkItem?

    private init() {
        settings = Store.shared.current

        engine.attach(player)
        engine.attach(eq)
        engine.attach(reverb)
        engine.attach(limiter)

        for i in 0..<Bands.count {
            let b = eq.bands[i]
            b.filterType = .parametric
            b.frequency = Bands.centers[i]
            b.bandwidth = 1.0
            b.bypass = false
        }
        let shelf = eq.bands[Bands.count]
        shelf.filterType = .lowShelf
        shelf.frequency = 80
        shelf.bypass = true

        reverb.loadFactoryPreset(.smallRoom)
        reverb.wetDryMix = 0

        applyEffects()
        setupSession()
        setupRemoteCommands()
        refreshRoute()
    }

    // ------------------------------------------------------------ lejátszás

    func play(index: Int) {
        let tracks = Library.shared.tracks
        guard tracks.indices.contains(index) else { return }
        do {
            let f = try AVAudioFile(forReading: tracks[index].url)
            file = f
            currentIndex = index
            duration = Double(f.length) / f.processingFormat.sampleRate
            try startEngine(format: f.processingFormat)
            restart(from: 0, autoplay: true)
            lastError = nil
        } catch {
            lastError = "Nem lejátszható: \(tracks[index].title)"
            isPlaying = false
        }
    }

    func togglePlay() {
        if isPlaying { pause() } else { resume() }
    }

    func resume() {
        guard file != nil else {
            if !Library.shared.tracks.isEmpty { play(index: currentIndex ?? 0) }
            return
        }
        do {
            try startEngine(format: file!.processingFormat)
            player.play()
            isPlaying = true
            startTicker()
            updateNowPlaying()
        } catch {
            lastError = "A hangmotor nem indult el."
        }
    }

    func pause() {
        player.pause()
        isPlaying = false
        updateElapsed()
        updateNowPlaying()
    }

    func next() {
        let count = Library.shared.tracks.count
        guard count > 0 else { return }
        if shuffle, count > 1 {
            var r = Int.random(in: 0..<count)
            if r == currentIndex { r = (r + 1) % count }
            play(index: r)
        } else {
            play(index: ((currentIndex ?? -1) + 1) % count)
        }
    }

    func previous() {
        let count = Library.shared.tracks.count
        guard count > 0 else { return }
        if elapsed > 3 { seek(to: 0); return }
        play(index: ((currentIndex ?? 0) - 1 + count) % count)
    }

    func seek(to seconds: Double) {
        guard let f = file else { return }
        let frame = AVAudioFramePosition(max(0, min(seconds, duration)) * f.processingFormat.sampleRate)
        restart(from: frame, autoplay: isPlaying)
    }

    /// Teljes leállítás: lejátszás, hangmotor és audio session – az app ezután nem fut a háttérben.
    func stopAll() {
        generation += 1
        player.stop()
        engine.stop()
        isPlaying = false
        isRunning = false
        ticker?.invalidate()
        ticker = nil
        elapsed = 0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        persistNow()
    }

    // ------------------------------------------------------------ belső: lejátszás

    private func startEngine(format: AVAudioFormat) throws {
        try AVAudioSession.sharedInstance().setActive(true)
        if connectedFormat != format {
            if engine.isRunning { engine.stop() }
            engine.connect(player, to: eq, format: format)
            engine.connect(eq, to: reverb, format: format)
            engine.connect(reverb, to: limiter, format: format)
            engine.connect(limiter, to: engine.mainMixerNode, format: format)
            connectedFormat = format
        }
        if !engine.isRunning {
            engine.prepare()
            try engine.start()
        }
        isRunning = true
    }

    private func restart(from frame: AVAudioFramePosition, autoplay: Bool) {
        guard let f = file else { return }
        generation += 1
        let gen = generation
        player.stop()
        seekFrame = frame
        let remaining = f.length - frame
        if remaining > 0 {
            player.scheduleSegment(f, startingFrame: frame, frameCount: AVAudioFrameCount(remaining), at: nil,
                                   completionCallbackType: .dataPlayedBack) { [weak self] _ in
                DispatchQueue.main.async { self?.segmentFinished(gen) }
            }
        }
        elapsed = Double(frame) / f.processingFormat.sampleRate
        if autoplay {
            player.play()
            isPlaying = true
            startTicker()
        }
        updateNowPlaying()
    }

    private func segmentFinished(_ gen: Int) {
        guard gen == generation, isPlaying else { return }
        switch repeatMode {
        case .one:
            restart(from: 0, autoplay: true)
        case .all:
            next()
        case .off:
            if let i = currentIndex, i + 1 < Library.shared.tracks.count, !shuffle {
                play(index: i + 1)
            } else if shuffle {
                next()
            } else {
                isPlaying = false
                updateNowPlaying()
            }
        }
    }

    private func startTicker() {
        guard ticker == nil else { return }
        ticker = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.updateElapsed()
        }
    }

    private func updateElapsed() {
        guard let f = file else { return }
        if let nodeTime = player.lastRenderTime, let pt = player.playerTime(forNodeTime: nodeTime), isPlaying {
            elapsed = min(duration, Double(seekFrame + pt.sampleTime) / f.processingFormat.sampleRate)
        }
    }

    // ------------------------------------------------------------ effektek

    private func applyEffects() {
        let s = settings
        let gains = s.effectiveGains()
        for i in 0..<Bands.count { eq.bands[i].gain = gains[i] }
        let shelf = eq.bands[Bands.count]
        shelf.gain = s.bassBoostDb
        shelf.bypass = s.bassBoost == 0
        eq.globalGain = (s.preamp + s.loudness).clamped(-24, 24)
        eq.bypass = !s.enabled
        reverb.wetDryMix = s.enabled ? Float(s.virtualizer) / 1000 * 25 : 0
        reverb.bypass = !s.enabled || s.virtualizer == 0
        limiter.bypass = !(s.enabled && s.limiter)
        player.pan = s.enabled ? s.balance : 0
    }

    private func schedulePersist() {
        persistWork?.cancel()
        let w = DispatchWorkItem { [weak self] in self?.persistNow() }
        persistWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: w)
    }

    private func persistNow() {
        persistWork?.cancel()
        persistWork = nil
        Store.shared.current = settings
        if Store.shared.autoProfile, let id = outputId {
            Store.shared.upsertDevice(id: id, name: outputName, settings: settings)
        }
    }

    // ------------------------------------------------------------ audio session, útvonal

    private func setupSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: [])
        let nc = NotificationCenter.default
        nc.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { [weak self] n in
            self?.handleInterruption(n)
        }
        nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { [weak self] n in
            self?.handleRouteChange(n)
        }
        nc.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            self?.handleEngineReset()
        }
    }

    private func handleInterruption(_ n: Notification) {
        guard let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        if type == .began {
            isPlaying = false
            updateNowPlaying()
        } else if type == .ended,
                  let optRaw = n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt,
                  AVAudioSession.InterruptionOptions(rawValue: optRaw).contains(.shouldResume) {
            resume()
        }
    }

    private func handleRouteChange(_ n: Notification) {
        if let raw = n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
           AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable, isPlaying {
            // Fejhallgató kihúzva / Bluetooth lecsatlakozott → szünet (Apple irányelv)
            pause()
        }
        refreshRoute()
    }

    /// Ha a rendszer újrakonfigurálja a hangmotort (pl. eszközváltás), újraépítjük.
    private func handleEngineReset() {
        guard let f = file else { return }
        let wasPlaying = isPlaying
        updateElapsed()
        let pos = elapsed
        connectedFormat = nil
        do {
            try startEngine(format: f.processingFormat)
            restart(from: AVAudioFramePosition(pos * f.processingFormat.sampleRate), autoplay: wasPlaying)
        } catch {
            isPlaying = false
        }
    }

    func refreshRoute() {
        let out = AVAudioSession.sharedInstance().currentRoute.outputs.first
        let type = out?.portType
        let bt: [AVAudioSession.Port] = [.bluetoothA2DP, .bluetoothLE, .bluetoothHFP]
        outputName = out?.portName ?? "—"
        outputIsBluetooth = type.map { bt.contains($0) } ?? false
        outputKind = AudioController.label(for: type)
        let newId = out.map { $0.uid.isEmpty ? "name:\($0.portName)" : $0.uid }
        if newId != outputId {
            persistNow()
            outputId = newId
            onDeviceChanged()
        }
    }

    private func onDeviceChanged() {
        guard let id = outputId else { return }
        let store = Store.shared
        if store.autoProfile, let profile = store.device(id) {
            var s = profile.settings
            s.enabled = settings.enabled
            settings = s
            store.upsertDevice(id: id, name: outputName)
        } else {
            store.upsertDevice(id: id, name: outputName, settings: store.device(id)?.settings ?? settings)
        }
    }

    static func label(for type: AVAudioSession.Port?) -> String {
        guard let type else { return "" }
        switch type {
        case .builtInSpeaker: return "Telefon hangszóró"
        case .builtInReceiver: return "Telefon (fülhallgató-hangszóró)"
        case .headphones: return "Vezetékes fejhallgató"
        case .bluetoothA2DP: return "Bluetooth (A2DP)"
        case .bluetoothLE: return "Bluetooth LE"
        case .bluetoothHFP: return "Bluetooth (hívás mód)"
        case .carAudio: return "CarPlay / autó"
        case .airPlay: return "AirPlay"
        case .usbAudio: return "USB"
        case .HDMI: return "HDMI"
        default: return type.rawValue
        }
    }

    // ------------------------------------------------------------ lezárási képernyő / Vezérlőközpont

    private func setupRemoteCommands() {
        let rc = MPRemoteCommandCenter.shared()
        rc.playCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.resume() }
            return .success
        }
        rc.pauseCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.pause() }
            return .success
        }
        rc.togglePlayPauseCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.togglePlay() }
            return .success
        }
        rc.nextTrackCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.next() }
            return .success
        }
        rc.previousTrackCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.previous() }
            return .success
        }
        rc.stopCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.stopAll() }
            return .success
        }
        rc.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            DispatchQueue.main.async { self?.seek(to: e.positionTime) }
            return .success
        }
    }

    private func updateNowPlaying() {
        guard let t = currentTrack else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: t.title,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: elapsed,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        info[MPMediaItemPropertyArtist] = t.artist ?? "BlueTune"
        info[MPMediaItemPropertyAlbumTitle] = settings.enabled ? "EQ: \(settings.presetName ?? "Egyéni")" : "EQ ki"
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}
