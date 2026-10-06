import Foundation
import Combine

enum TranslationProvider: String, Codable, CaseIterable, Identifiable {
    case deepseek, apple, original
    var id: String { rawValue }
    var label: String {
        switch self {
        case .deepseek: "DeepSeek AI (best quality + triage)"
        case .apple: "Apple Translate (on-device, free, no AI)"
        case .original: "No translation (source's English title only)"
        }
    }
}

enum VoiceProvider: String, Codable, CaseIterable, Identifiable {
    case mimo, system
    var id: String { rawValue }
    var label: String { self == .mimo ? "Xiaomi MiMo TTS (natural)" : "macOS system voice (offline)" }
}

struct NewsCategory: Identifiable, Hashable {
    let id: String
    let label: String
    let symbol: String
    static let all: [NewsCategory] = [
        .init(id: "ai-models", label: "Models", symbol: "cpu"),
        .init(id: "ai-products", label: "Products", symbol: "shippingbox"),
        .init(id: "industry", label: "Industry", symbol: "building.2"),
        .init(id: "paper", label: "Research", symbol: "doc.text.magnifyingglass"),
        .init(id: "tip", label: "Tips & Opinion", symbol: "lightbulb"),
    ]
    static func find(_ id: String?) -> NewsCategory? { all.first { $0.id == id } }
}

/// All user preferences. New fields only need a default value here: saved JSON is
/// merged over the defaults, so older saves keep decoding.
struct Config: Codable, Equatable {
    // Feed
    var pollSeconds: Double = 90
    var feedMode = "firstParty"          // "firstParty" (official sources, = web 只看一手) | "selected" | "all"
    var categories: Set<String> = Set(NewsCategory.all.map(\.id))
    var minScore = 0                     // first-hand is already ~8 curated items/day; raise to be pickier
    var maxAlertAgeHours = 12
    var includeKeywords = ""             // comma separated, always alert
    var excludeKeywords = ""             // comma separated, never alert
    var useAIRelevance = false
    var minRelevance = 60
    var interestProfile = "I build macOS and AI apps. I care about frontier model releases (Claude, GPT, Gemini, DeepSeek, Qwen), coding agents and developer tools, open-weight models, and major AI industry moves."

    // Trending (multi-source hot topics)
    var hotEnabled = false               // "hot" list is opt-in; first-hand already covers the big launches
    var hotMaxRank = 5
    var hotMinSources = 8

    // Interruptions
    var maxPopupsPerHour = 6
    var digestThreshold = 3
    var quietEnabled = false
    var quietStart = 23 * 60             // minutes after midnight
    var quietEnd = 7 * 60
    var dimBackground = true
    var autoDismissSeconds = 0           // 0 = stays until you act
    var keyGuardSeconds = 0.8

    // Translation
    var translation: TranslationProvider = .deepseek
    var deepseekModel = "deepseek-flash"

    // Audio
    var audioEnabled = true
    var voiceProvider: VoiceProvider = .mimo
    var voice = "Mia"
    var voiceStyle = "Crisp, confident tech-news anchor. Brisk but clear pace, warm and calm."
    var chime = true
    var volume = 0.9
    var holdAudioWhenMicBusy = true

    // Briefing
    var briefingEnabled = true
    var briefingMinutes = 8 * 60 + 15
    var briefingWeekly = true

    // Podcast
    var podFormat = "duo"                // "solo" | "duo"
    var podLengthMin = 0                 // 0 = auto (1d → 8, 3d → 12, 7d → 18 minutes)
    var podCoverage = "all"              // "all" (AI-curated) | "selected" | "firstParty"
    var podTone = "morning"              // see PodcastStyle.tones
    var podExpressiveness = 2            // 1 subtle, 2 natural, 3 expressive
    var podHostAName = "Mia"
    var podHostAVoice = "Mia"
    var podHostAPersona = "Lead host. A warm, bright, curious woman in her early thirties; a former tech journalist who explains clearly, keeps the show moving and smiles while she talks. Light humour, never over the top."
    var podHostBName = "Dean"
    var podHostBVoice = "Dean"
    var podHostBPersona = "Co-host and analyst. A calm, dry-witted man in his forties; an ex-engineer who adds the 'so what' for builders, asks sharp questions and is healthily sceptical of hype. Lower, relaxed voice."
    var podJingle = false
    var podWriterModel = "deepseek-v4-pro"
    var podAuto = false
    var podAutoMinutes = 7 * 60
    var podAutoWeeklyOnMonday = true
    var podSaveToMusic = true
    var podSpeed = 1.0

    static let defaults = Config()

    func keywords(_ s: String) -> [String] {
        s.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces).lowercased() }.filter { !$0.isEmpty }
    }
}

@MainActor
final class ConfigStore: ObservableObject {
    static let shared = ConfigStore()
    private let key = "config.v1"

    @Published var c: Config { didSet { if c != oldValue { save() } } }

    private init() {
        c = Self.load(key: "config.v1")
    }

    private static func load(key: String) -> Config {
        guard let saved = UserDefaults.standard.data(forKey: key) else { return Config() }
        return decodeOverDefaults(saved, defaults: Config())
    }

    private func save() {
        if let data = try? JSONEncoder().encode(c) { UserDefaults.standard.set(data, forKey: key) }
    }
}

struct Secrets: Codable, Equatable {
    var deepseekKey = ""
    var mimoKey = ""
}

/// API keys live in a 0600 file under Application Support, never in source or UserDefaults.
@MainActor
final class SecretsStore: ObservableObject {
    static let shared = SecretsStore()
    @Published var s: Secrets { didSet { if s != oldValue { save() } } }
    private let url = Paths.support.appendingPathComponent("secrets.json")

    private init() {
        if let saved = try? JSONDecoder().decode(Secrets.self, from: Data(contentsOf: url)) {
            s = saved
        } else {
            // First launch on a new Mac: seed from the keys bundled into the app (see scripts/build-app.sh).
            let bundled = Bundle.main.url(forResource: "secrets", withExtension: "json")
                .flatMap { try? Data(contentsOf: $0) }
                .flatMap { try? JSONDecoder().decode(Secrets.self, from: $0) }
            s = bundled ?? Secrets()
            if bundled != nil { save() }
        }
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(s) else { return }
        try? data.write(to: url, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
}

enum Paths {
    static let support: URL = {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("AINewsHot", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }()
    static let audio: URL = {
        let u = support.appendingPathComponent("audio", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }()
}

/// Decodes `data` on top of `defaults`, so a value saved by an older build (missing newer keys) still loads.
func decodeOverDefaults<T: Codable>(_ data: Data, defaults: T, decoder: JSONDecoder = JSONDecoder(),
                                    encoder: JSONEncoder = JSONEncoder()) -> T {
    guard let savedDict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let defData = try? encoder.encode(defaults),
          var dict = try? JSONSerialization.jsonObject(with: defData) as? [String: Any]
    else { return defaults }
    dict.merge(savedDict) { _, new in new }
    guard let merged = try? JSONSerialization.data(withJSONObject: dict),
          let value = try? decoder.decode(T.self, from: merged) else { return defaults }
    return value
}
