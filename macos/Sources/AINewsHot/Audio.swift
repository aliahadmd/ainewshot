import Foundation
import AVFoundation
import AppKit
import CoreAudio
import CryptoKit

enum Log {
    static func info(_ s: String) { NSLog("[AINewsHot] %@", s) }
}

// MARK: - Xiaomi MiMo TTS (OpenAI-compatible chat API that returns audio)

enum MiMoTTS {
    static let voices = ["Mia", "Chloe", "Milo", "Dean", "mimo_default"]

    static let sampleRate: UInt32 = 24_000

    /// Streams 24 kHz PCM16 and wraps it in a WAV. Streaming finishes ~2-3x faster than non-streaming.
    static func synthesize(_ text: String, voice: String, style: String, key: String) async throws -> Data {
        wav(pcm: try await checkedPCM(text: text, style: style, voice: voice, key: key), sampleRate: sampleRate)
    }

    /// Generative TTS occasionally "runs on" (minutes of babble after the script) or drops content.
    /// Judge each take against the word count, retry bad ones, and as a last resort cap the closest take.
    static func checkedPCM(text: String, style: String, voice: String, key: String, attempts: Int = 4) async throws -> Data {
        let spoken = text.replacingOccurrences(of: #"\([^)]*\)|\[[^\]]*\]"#, with: " ", options: .regularExpression)
        let words = Double(max(1, spoken.split(whereSeparator: \.isWhitespace).count))
        let tags = Double(text.components(separatedBy: "[").count - 1)
        let expected = words * 0.42
        let maxSec = words * 0.8 + 4 + tags * 1.5
        let minSec = words * 0.14
        var best: (pcm: Data, err: Double)?
        var lastError: Error?
        for attempt in 0..<attempts {
            try Task.checkCancellation()
            do {
                let raw = trimSilence(try await pcm(text: text, style: style, voice: voice, key: key))
                let secs = Double(raw.count) / 2 / Double(sampleRate)
                if secs <= maxSec && secs >= minSec { return raw }
                Log.info("TTS take \(attempt + 1) rejected: \(Int(secs))s for \(Int(words)) words")
                let err = abs(secs - expected)
                if best == nil || err < best!.err { best = (raw, err) }
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                lastError = error
                try await Task.sleep(for: .seconds(Double(attempt + 1) * 1.5))
            }
        }
        guard let best else { throw lastError ?? DeepSeek.Failure.empty }
        return capped(best.pcm, seconds: maxSec)
    }

    /// Drop leading/trailing near-silence, keeping a short natural margin.
    static func trimSilence(_ pcm: Data, threshold: Int16 = 500, margin: Double = 0.08) -> Data {
        let n = pcm.count / 2
        guard n > 0 else { return pcm }
        return pcm.withUnsafeBytes { raw -> Data in
            let s = raw.bindMemory(to: Int16.self)
            var a = 0, b = n - 1
            while a < n && abs(Int(s[a])) < Int(threshold) { a += 1 }
            while b > a && abs(Int(s[b])) < Int(threshold) { b -= 1 }
            let m = Int(margin * Double(sampleRate))
            a = max(0, a - m); b = min(n - 1, b + m)
            guard b > a else { return Data() }
            return Data(raw[(a * 2)..<((b + 1) * 2)])
        }
    }

    /// Hard cap with a 0.4 s fade-out (last resort for a run-on take).
    static func capped(_ pcm: Data, seconds: Double) -> Data {
        let maxN = Int(seconds * Double(sampleRate))
        var s = pcm.withUnsafeBytes { Array($0.bindMemory(to: Int16.self)) }
        guard s.count > maxN else { return pcm }
        s = Array(s.prefix(maxN))
        let fade = min(s.count, Int(0.4 * Double(sampleRate)))
        for i in 0..<fade { let k = s.count - fade + i; s[k] = Int16(Double(s[k]) * Double(fade - i) / Double(fade)) }
        return s.withUnsafeBufferPointer { Data(buffer: $0) }
    }

    /// Raw 24 kHz mono PCM16 for one utterance.
    /// - `style` goes in the user message (natural-language / director-mode control, never spoken).
    /// - `text` goes in the assistant message and may carry a leading `(style)` tag and inline `[audio tags]`.
    /// - `voice` is a built-in voice id, or `file:<name>` for a reference clip (VoiceClone).
    static func pcm(text: String, style: String, voice: String, key: String) async throws -> Data {
        var model = "mimo-v2.5-tts"
        var voiceParam = voice
        if voice.hasPrefix("file:") {
            model = "mimo-v2.5-tts-voiceclone"
            voiceParam = try VoiceLibrary.dataURI(for: String(voice.dropFirst(5)))
        }
        return try await request(model: model, user: style, assistant: text, audio: ["format": "pcm16", "voice": voiceParam], key: key)
    }

    /// VoiceDesign: invent a voice from a description. Each call designs a *new* voice, so callers
    /// render one sample and reuse it through VoiceClone to keep the voice consistent.
    static func design(description: String, sample: String, key: String) async throws -> Data {
        let pcm = try await request(model: "mimo-v2.5-tts-voicedesign", user: description, assistant: sample,
                                    audio: ["format": "pcm16"], key: key)
        return wav(pcm: pcm, sampleRate: sampleRate)
    }

    private static func request(model: String, user: String, assistant: String, audio: [String: Any], key: String) async throws -> Data {
        var req = URLRequest(url: URL(string: "https://api.xiaomimimo.com/v1/chat/completions")!)
        req.httpMethod = "POST"
        req.timeoutInterval = 120
        req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body: [String: Any] = [
            "model": model,
            "stream": true,
            "messages": [
                ["role": "user", "content": user],
                ["role": "assistant", "content": assistant],
            ],
            "audio": audio,
        ]
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (bytes, resp) = try await URLSession.shared.bytes(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else {
            var err = ""
            for try await line in bytes.lines { err += line; if err.count > 300 { break } }
            throw DeepSeek.Failure.http(code, "MiMo: " + err)
        }
        struct Chunk: Decodable {
            struct C: Decodable { struct D: Decodable { struct A: Decodable { let data: String? }; let audio: A? }; let delta: D? }
            let choices: [C]
        }
        var pcm = Data()
        for try await line in bytes.lines {
            guard line.hasPrefix("data:") else { continue }
            let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
            if payload == "[DONE]" { break }
            guard let chunk = try? JSONDecoder().decode(Chunk.self, from: Data(payload.utf8)),
                  let b64 = chunk.choices.first?.delta?.audio?.data,
                  let d = Data(base64Encoded: b64) else { continue }
            pcm.append(d)
        }
        guard !pcm.isEmpty else { throw DeepSeek.Failure.empty }
        return pcm
    }

    static func wav(pcm: Data, sampleRate: UInt32) -> Data {
        var d = Data()
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        d.append("RIFF".data(using: .ascii)!); u32(36 + UInt32(pcm.count)); d.append("WAVE".data(using: .ascii)!)
        d.append("fmt ".data(using: .ascii)!); u32(16); u16(1); u16(1); u32(sampleRate); u32(sampleRate * 2); u16(2); u16(16)
        d.append("data".data(using: .ascii)!); u32(UInt32(pcm.count)); d.append(pcm)
        return d
    }
}

// MARK: - Speech cache + playback

@MainActor
final class Speaker: NSObject, ObservableObject, AVAudioPlayerDelegate, AVSpeechSynthesizerDelegate {
    static let shared = Speaker()

    @Published private(set) var playingID: String?
    @Published private(set) var preparingID: String?
    private var player: AVAudioPlayer?
    private let synth = AVSpeechSynthesizer()
    private var inflight: [String: Task<URL?, Never>] = [:]

    override init() {
        super.init()
        synth.delegate = self
        purgeOldAudio()
    }

    private func cacheURL(_ text: String, _ cfg: Config) -> URL {
        let h = SHA256.hash(data: Data((cfg.voice + "|" + cfg.voiceStyle + "|" + text).utf8))
        let name = String(h.map { String(format: "%02x", $0) }.joined().prefix(32))
        return Paths.audio.appendingPathComponent(name + ".wav")
    }

    /// Generate (or reuse) the MiMo clip for an item. Returns nil if MiMo is off or fails.
    @discardableResult
    func prepare(_ item: NewsItem) async -> URL? {
        let cfg = ConfigStore.shared.c
        let key = SecretsStore.shared.s.mimoKey
        guard cfg.audioEnabled, cfg.voiceProvider == .mimo, !key.isEmpty else { return nil }
        let text = item.speechText
        let url = cacheURL(text, cfg)
        if FileManager.default.fileExists(atPath: url.path) { return url }
        if let t = inflight[url.path] { return await t.value }
        let task = Task<URL?, Never> {
            do {
                let data = try await MiMoTTS.synthesize(text, voice: cfg.voice, style: cfg.voiceStyle, key: key)
                try data.write(to: url, options: .atomic)
                return url
            } catch {
                Log.info("TTS failed: \(error.localizedDescription)")
                return nil
            }
        }
        inflight[url.path] = task
        let result = await task.value
        inflight[url.path] = nil
        return result
    }

    /// Plays an item: chime, then the MiMo clip (generating it if needed) or the system voice.
    func play(_ item: NewsItem, chime: Bool) {
        stop()
        let cfg = ConfigStore.shared.c
        if chime && cfg.chime { NSSound(named: "Glass")?.play() }
        playingID = item.id
        Task {
            if chime && cfg.chime { try? await Task.sleep(for: .milliseconds(450)) }
            var url: URL?
            if cfg.voiceProvider == .mimo {
                preparingID = item.id
                url = await prepare(item)
                preparingID = nil
            }
            guard playingID == item.id else { return }   // user moved on meanwhile
            if let url, let p = try? AVAudioPlayer(contentsOf: url) {
                p.volume = Float(cfg.volume)
                p.delegate = self
                player = p
                p.play()
            } else {
                speakSystem(item.speechText, volume: cfg.volume)
            }
        }
    }

    func toggle(_ item: NewsItem) {
        if playingID == item.id { stop() } else { play(item, chime: false) }
    }

    func stop() {
        player?.stop(); player = nil
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        playingID = nil
        preparingID = nil
    }

    private func speakSystem(_ text: String, volume: Double) {
        let u = AVSpeechUtterance(string: text)
        let voices = AVSpeechSynthesisVoice.speechVoices().filter { $0.language.hasPrefix("en") }
        u.voice = voices.first { $0.quality == .premium } ?? voices.first { $0.quality == .enhanced } ?? AVSpeechSynthesisVoice(language: "en-US")
        u.volume = Float(volume)
        synth.speak(u)
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in if self.player === player { self.playingID = nil; self.player = nil } }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.playingID = nil }
    }

    private func purgeOldAudio() {
        let fm = FileManager.default
        let cutoff = Date().addingTimeInterval(-3 * 86_400)
        for u in (try? fm.contentsOfDirectory(at: Paths.audio, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
            if let d = try? u.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate, d < cutoff {
                try? fm.removeItem(at: u)
            }
        }
    }

    /// True while any app is recording from the default microphone (calls, meetings, dictation).
    static func microphoneInUse() -> Bool {
        var dev = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultInputDevice,
                                              mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &dev) == noErr,
              dev != 0 else { return false }
        var running: UInt32 = 0
        size = UInt32(MemoryLayout<UInt32>.size)
        addr.mSelector = kAudioDevicePropertyDeviceIsRunningSomewhere
        guard AudioObjectGetPropertyData(dev, &addr, 0, nil, &size, &running) == noErr else { return false }
        return running != 0
    }
}
