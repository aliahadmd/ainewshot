import Foundation
import AVFoundation
import AppKit

// MARK: - Episode model

struct StoryLink: Codable, Hashable, Identifiable {
    var id: String { url }
    var title: String
    var source: String
    var url: String
}

struct ShowNote: Codable, Hashable, Identifiable {
    var id: Int
    var title: String
    var importance: Int
    var tier: String          // deep | brief | quick | notes
    var links: [StoryLink]
}

struct PodcastLine: Codable, Hashable, Identifiable {
    var id: Int
    var speaker: String       // "A" | "B" | "J" (jingle)
    var direction: String
    var text: String
    var chapter: Int
    var start: Double = 0
    var end: Double = 0
}

struct PodcastChapter: Codable, Hashable, Identifiable {
    var id: Int
    var title: String
    var start: Double = 0
    var stories: [Int] = []   // ShowNote ids
}

struct Episode: Codable, Hashable, Identifiable {
    var id: String
    var createdAt: Date
    var periodDays: Int
    var coverage: String
    var title: String
    var summary: String
    var hostNames: [String: String]
    var lines: [PodcastLine]
    var chapters: [PodcastChapter]
    var notes: [ShowNote]
    var audioPath: String
    var duration: Double
    var itemsScanned: Int

    var periodLabel: String { periodDays == 1 ? "Last 24 hours" : "Last \(periodDays) days" }
    var audioURL: URL { URL(fileURLWithPath: audioPath) }
    func name(_ speaker: String) -> String { hostNames[speaker] ?? speaker }
}

// MARK: - Style presets (MiMo natural-language + audio-tag control)

enum PodcastStyle {
    static let show = "AI News Hot Daily"

    static let tones: [(id: String, label: String, scene: String)] = [
        ("morning", "Morning coffee", "A relaxed morning show people listen to over coffee. Unhurried, warm and clear; friendly energy without rushing."),
        ("newsroom", "Newsroom", "A professional news briefing. Crisp, authoritative, efficient pace, measured emotion."),
        ("casual", "Casual chat", "Two friends who love tech chatting casually. Playful, conversational, genuine reactions."),
        ("energetic", "Energetic", "A high-energy tech show. Upbeat, quick pace, enthusiastic but always credible."),
    ]

    static func scene(_ tone: String) -> String { tones.first { $0.id == tone }?.scene ?? tones[0].scene }

    static func writerRule(_ level: Int) -> String {
        switch level {
        case 1: return "SUBTLE: about 15% of lines start with a (style) tag; use 2-4 inline tags in the whole episode, only [pause] or a soft [chuckles]. Understated emotion."
        case 3: return "EXPRESSIVE: about 75% of lines start with a vivid, specific (style) tag, e.g. \"(genuinely stunned)\", \"(teasing)\", \"(quietly concerned)\", \"(laughing as she talks)\"; at least one inline reaction or breath every 2-3 lines ([laughs], [sighs], [inhales], [pause], [gasps]); mixed emotions welcome. Stay credible — it is still news."
        default: return "NATURAL: about 40% of lines start with a (style) tag such as \"(amused)\", \"(thoughtful)\", \"(surprised)\", \"(serious)\"; roughly one inline tag every 4 lines where a real host would react ([laughs], [chuckles], [sighs], [pause], [inhales]). Vary emotions across the show."
        }
    }

    static func ttsRule(_ level: Int) -> String {
        switch level {
        case 1: return "Keep the delivery understated and steady."
        case 3: return "Be vivid and performative: let the emotion colour pitch, pace and breath."
        default: return "Natural, lively delivery with real but restrained emotion."
        }
    }

    static let builtInVoices: [(id: String, label: String)] = [
        ("Mia", "Mia — English, female"), ("Chloe", "Chloe — English, female"),
        ("Milo", "Milo — English, male"), ("Dean", "Dean — English, male"),
    ]
}

// MARK: - Custom voices (VoiceDesign once → reuse through VoiceClone)

enum VoiceLibrary {
    static let dir: URL = {
        let u = Paths.support.appendingPathComponent("voices", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }()

    static func list() -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? [])
            .filter { $0.hasSuffix(".wav") || $0.hasSuffix(".mp3") }.sorted()
    }

    static func label(_ voice: String) -> String {
        voice.hasPrefix("file:") ? "Custom — " + (voice.dropFirst(5) as Substring).replacingOccurrences(of: ".wav", with: "").replacingOccurrences(of: ".mp3", with: "") : voice
    }

    static func dataURI(for name: String) throws -> String {
        let url = dir.appendingPathComponent(name)
        let data = try Data(contentsOf: url)
        let mime = name.hasSuffix(".mp3") ? "audio/mpeg" : "audio/wav"
        return "data:\(mime);base64," + data.base64EncodedString()
    }

    /// Copy a reference recording in for VoiceClone (MiMo limit: 10 MB once base64-encoded).
    static func importFile(_ src: URL) throws -> String {
        let size = (try? src.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard size < 7_000_000 else { throw DeepSeek.Failure.http(413, "Voice sample must be under 7 MB (10 MB base64).") }
        let ext = src.pathExtension.lowercased() == "mp3" ? "mp3" : "wav"
        let name = src.deletingPathExtension().lastPathComponent.replacingOccurrences(of: " ", with: "-") + "." + ext
        let dst = dir.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: dst)
        try FileManager.default.copyItem(at: src, to: dst)
        return name
    }

    static func design(name: String, description: String, key: String) async throws -> String {
        let sample = "Good morning, and welcome back to the show. I've got a lot to share with you today, so grab a coffee, get comfortable, and let's dive into everything that happened in AI."
        let wav = try await MiMoTTS.design(description: description, sample: sample, key: key)
        let file = name.replacingOccurrences(of: " ", with: "-") + ".wav"
        try wav.write(to: dir.appendingPathComponent(file), options: .atomic)
        return file
    }
}

// MARK: - Studio: fetch → edit → write → record → mix

@MainActor
final class PodcastStudio: ObservableObject {
    static let shared = PodcastStudio()

    enum Stage: Int, CaseIterable {
        case idle, fetching, editing, writing, recording, mixing, done, failed
        var label: String {
            switch self {
            case .idle: "Ready"
            case .fetching: "Collecting news"
            case .editing: "Editing: merging & ranking stories"
            case .writing: "Writing the script"
            case .recording: "Recording voices"
            case .mixing: "Mixing & exporting"
            case .done: "Done"
            case .failed: "Failed"
            }
        }
    }

    @Published private(set) var stage: Stage = .idle
    @Published private(set) var detail = ""
    @Published private(set) var progress: Double = 0
    @Published private(set) var log: [String] = []
    @Published private(set) var episodes: [Episode] = []
    @Published var selectedID: String?
    @Published private(set) var error: String?
    @Published private(set) var generatingDays = 0

    var isBusy: Bool { (Stage.fetching.rawValue...Stage.mixing.rawValue).contains(stage.rawValue) }
    var selected: Episode? { episodes.first { $0.id == selectedID } }

    private var task: Task<Void, Never>?
    private var newsCache: (key: String, at: Date, items: [APIItem])?
    private let root: URL = {
        let u = Paths.support.appendingPathComponent("podcasts", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }()

    private init() { loadLibrary() }

    // MARK: Library

    private func loadLibrary() {
        let fm = FileManager.default
        let dirs = (try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        episodes = dirs.compactMap { d in
            guard let data = try? Data(contentsOf: d.appendingPathComponent("episode.json")) else { return nil }
            return try? JSONDecoder().decode(Episode.self, from: data)
        }
        .filter { FileManager.default.fileExists(atPath: $0.audioPath) }
        .sorted { $0.createdAt > $1.createdAt }
        selectedID = episodes.first?.id
    }

    func delete(_ ep: Episode) {
        if PodcastPlayer.shared.episode?.id == ep.id { PodcastPlayer.shared.unload() }
        try? FileManager.default.removeItem(at: root.appendingPathComponent(ep.id))
        episodes.removeAll { $0.id == ep.id }
        if selectedID == ep.id { selectedID = episodes.first?.id }
    }

    private func prune() {
        for ep in episodes.dropFirst(40) { delete(ep) }
    }

    // MARK: Generate

    func generate(days: Int, auto: Bool = false) {
        guard !isBusy else { return }
        error = nil
        log = []
        generatingDays = days
        task = Task {
            do {
                let ep = try await run(days: days)
                episodes.insert(ep, at: 0)
                selectedID = ep.id
                stage = .done
                detail = "Ready · \(Self.mmss(ep.duration))"
                prune()
                if auto { announce(ep) }
            } catch is CancellationError {
                stage = .idle
                detail = "Cancelled"
            } catch {
                stage = .failed
                self.error = error.localizedDescription
                note("Error: \(error.localizedDescription)")
            }
        }
    }

    func cancel() { task?.cancel() }

    private func set(_ s: Stage, _ d: String, _ p: Double = 0) { stage = s; detail = d; progress = p }
    private func note(_ s: String) { log.append(s); Log.info("podcast: \(s)") }

    private func announce(_ ep: Episode) {
        var n = NewsItem(id: "podcast-\(ep.id)", kind: .podcast, title: "Your podcast is ready: \(ep.title)",
                         summary: ep.summary)
        n.why = "\(Self.mmss(ep.duration)) · \(ep.periodLabel.lowercased()) · \(ep.notes.count) stories from \(ep.itemsScanned) news items"
        n.sourceName = PodcastStyle.show
        n.episodeID = ep.id
        NewsEngine.shared.route([n], bypassRateLimit: true)
    }

    private func run(days: Int) async throws -> Episode {
        let cfg = ConfigStore.shared.c
        let keys = SecretsStore.shared.s
        guard !keys.deepseekKey.isEmpty else { throw DeepSeek.Failure.http(401, "Add a DeepSeek API key in Settings.") }
        guard !keys.mimoKey.isEmpty else { throw DeepSeek.Failure.http(401, "Add a MiMo API key in Settings › Audio.") }
        let minutes = cfg.podLengthMin > 0 ? cfg.podLengthMin : [1: 8, 3: 12, 7: 18][days] ?? 10
        let since = Date().addingTimeInterval(-Double(days) * 86_400)

        // 1. Fetch
        set(.fetching, "Fetching \(cfg.podCoverage == "all" ? "all" : cfg.podCoverage) news…")
        let cacheKey = "\(days)-\(cfg.podCoverage)"
        let items: [APIItem]
        if let c = newsCache, c.key == cacheKey, Date().timeIntervalSince(c.at) < 1800 {
            items = c.items
        } else {
            items = try await AIHotClient.shared.itemsSince(since, coverage: cfg.podCoverage) { n in
                Task { @MainActor in PodcastStudio.shared.detail = "Fetched \(n) news items…" }
            }
            newsCache = (cacheKey, Date(), items)
        }
        note("Collected \(items.count) items from the \(days == 1 ? "last 24 hours" : "last \(days) days")")
        guard !items.isEmpty else { throw DeepSeek.Failure.http(204, "No news found for this period.") }

        // 2. Edit
        set(.editing, "Merging duplicate coverage across \(items.count) items…")
        let flash = DeepSeek(key: keys.deepseekKey, model: "deepseek-flash")
        var stories = try await PodcastEditor.edit(items, ds: flash) { done, total in
            Task { @MainActor in
                PodcastStudio.shared.progress = Double(done) / Double(total)
                PodcastStudio.shared.detail = "Edited \(done) of \(total) batches"
            }
        }
        try Task.checkCancellation()
        if stories.count > 60 || items.count > 300 {
            detail = "Removing duplicates across batches…"
            stories = (try? await PodcastEditor.merge(stories, ds: flash)) ?? stories
        }
        let plan = PodcastEditor.plan(stories, items: items, minutes: minutes)
        note("\(stories.count) stories kept → \(plan.deep.count) deep dives, \(plan.brief.count) briefs, \(plan.quick.count) quick hits, \(plan.rest.count) in show notes")

        // 3. Write
        set(.writing, "Writing a \(minutes)-minute \(cfg.podFormat == "duo" ? "two-host" : "solo") script…")
        let writer = DeepSeek(key: keys.deepseekKey, model: cfg.podWriterModel)
        var script = try await PodcastWriter.write(plan: plan, items: items, days: days, since: since, minutes: minutes, cfg: cfg, ds: writer)
        try Task.checkCancellation()
        if cfg.podJingle {
            script.lines.insert(PodcastLine(id: -1, speaker: "J", direction: "jingle",
                                            text: "(singing) A I News Hot, every story that you missed, A I News Hot, here's your morning list!",
                                            chapter: 0), at: 0)
        }
        for i in script.lines.indices { script.lines[i].id = i }
        note("Script: \(script.lines.count) lines, \(script.chapters.count) chapters, ~\(script.lines.map { $0.text.split(separator: " ").count }.reduce(0, +)) words")

        // 4. Record
        set(.recording, "Recording \(script.lines.count) lines…")
        let pcms = try await PodcastRecorder.record(script.lines, chapters: script.chapters, cfg: cfg, key: keys.mimoKey) { done in
            Task { @MainActor in
                let s = PodcastStudio.shared
                s.progress = Double(done) / Double(max(1, script.lines.count))
                s.detail = "Recorded \(done) of \(script.lines.count) lines"
            }
        }
        try Task.checkCancellation()

        // 5. Mix
        set(.mixing, "Levelling voices and exporting…")
        let id = Self.episodeID(days: days)
        let dir = root.appendingPathComponent(id, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let audioURL = dir.appendingPathComponent("episode.m4a")
        let lines = script.lines
        let mixed = try await Task.detached(priority: .userInitiated) {
            try PodcastMixer.mix(lines: lines, pcms: pcms, to: audioURL)
        }.value
        var chapters = script.chapters
        for i in chapters.indices {
            chapters[i].start = mixed.lines.first { $0.chapter == chapters[i].id }?.start ?? 0
        }
        let hostNames = ["A": cfg.podHostAName, "B": cfg.podHostBName, "J": "Jingle"]
        let ep = Episode(id: id, createdAt: Date(), periodDays: days, coverage: cfg.podCoverage,
                         title: script.title, summary: script.summary, hostNames: hostNames,
                         lines: mixed.lines, chapters: chapters, notes: plan.notes,
                         audioPath: mixed.url.path, duration: mixed.duration, itemsScanned: items.count)
        try JSONEncoder().encode(ep).write(to: dir.appendingPathComponent("episode.json"), options: .atomic)
        if cfg.podSaveToMusic { exportToMusic(ep) }
        note("Episode ready: \(Self.mmss(ep.duration))")
        return ep
    }

    private func exportToMusic(_ ep: Episode) {
        let music = FileManager.default.urls(for: .musicDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("AI News Hot", isDirectory: true)
        try? FileManager.default.createDirectory(at: music, withIntermediateDirectories: true)
        let df = DateFormatter(); df.locale = Locale(identifier: "en_US_POSIX"); df.dateFormat = "yyyy-MM-dd HHmm"
        let safe = ep.title.replacingOccurrences(of: "/", with: "-").replacingOccurrences(of: ":", with: " -")
        let dst = music.appendingPathComponent("\(df.string(from: ep.createdAt)) \(safe).\(ep.audioURL.pathExtension)")
        try? FileManager.default.copyItem(at: ep.audioURL, to: dst)
    }

    static func episodeID(days: Int) -> String {
        let df = DateFormatter(); df.locale = Locale(identifier: "en_US_POSIX"); df.dateFormat = "yyyyMMdd-HHmmss"
        return "\(df.string(from: Date()))-\(days)d"
    }

    static func mmss(_ t: Double) -> String {
        let s = Int(t.rounded())
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }
}

// MARK: - Editor (map: per-chunk story extraction, reduce: cross-chunk dedupe, then tiering)

struct EditedStory: Codable {
    var t: String          // English headline
    var ids: [Int]         // indices into the fetched items
    var imp: Int           // 1-5
}

struct EpisodePlan {
    var deep: [(key: String, story: EditedStory)]
    var brief: [(key: String, story: EditedStory)]
    var quick: [(key: String, story: EditedStory)]
    var rest: [EditedStory]
    var notes: [ShowNote]
    var keyToNote: [String: Int]
}

enum PodcastEditor {
    static func edit(_ items: [APIItem], ds: DeepSeek, progress: @escaping @Sendable (Int, Int) -> Void) async throws -> [EditedStory] {
        let size = 250
        let chunks = stride(from: 0, to: items.count, by: size).map { $0..<min($0 + size, items.count) }
        let system = """
        You are the news editor of an AI-industry podcast. Below is one batch of raw news items (mostly Chinese), one per line:
        index|score|★ if editor-picked|category|source|title|summary
        \(Translator.guardrail)
        Group items that cover the SAME event into one story. Be inclusive: keep every item with real news value for people following AI
        (launches, model releases, research, funding, policy, security incidents, notable tools, tutorials with substance, notable opinions).
        Drop ONLY: pure ads/promotions, giveaways, trivial social chatter, content-free reposts, and exact duplicates (merge those instead).
        When unsure, keep it with imp 1.
        imp: 5 = industry-moving, 4 = important, 3 = notable, 2 = minor, 1 = small but worth a mention. Use score and ★ as hints, not rules.
        For each story give a concise, factual English headline (max 14 words, keep product/company names exact).
        Reply JSON only: {"stories":[{"t":"headline","ids":[indices],"imp":n}]}
        """
        var all: [EditedStory] = []
        var done = 0
        try await withThrowingTaskGroup(of: [EditedStory].self) { group in
            var it = chunks.makeIterator()
            func add(_ r: Range<Int>) {
                let lines = r.map { i -> String in
                    let a = items[i]
                    let sum = (a.summary ?? "").prefix(110)
                    return "\(i)|\(a.score ?? 0)|\(a.selected == true || a.firstParty == true ? "★" : "")|\(a.category ?? "-")|\(NewsEngine.cleanSource(a.source?.name ?? ""))|\(a.title)|\(sum)"
                }.joined(separator: "\n")
                group.addTask {
                    for attempt in 0..<2 {
                        do {
                            let text = try await ds.chat(system: system, user: lines, json: true, maxTokens: 8000)
                            struct W: Decodable { let stories: [EditedStory] }
                            return try JSONDecoder().decode(W.self, from: Data(text.utf8)).stories
                                .map { s in var s = s; s.ids = s.ids.filter { r.contains($0) }; return s }
                                .filter { !$0.ids.isEmpty }
                        } catch where attempt == 0 { continue }
                    }
                    return []
                }
            }
            for _ in 0..<4 { if let r = it.next() { add(r) } }   // at most 4 batches in flight
            while let batch = try await group.next() {
                all += batch
                done += 1
                progress(done, chunks.count)
                if let r = it.next() { add(r) }
            }
        }
        guard !all.isEmpty else { throw DeepSeek.Failure.empty }
        return all
    }

    /// Cross-batch dedupe: the same event often lands in several batches on long periods.
    static func merge(_ stories: [EditedStory], ds: DeepSeek) async throws -> [EditedStory] {
        let list = stories.enumerated().map { "\($0)|\($1.imp)|\($1.ids.count)|\($1.t)" }.joined(separator: "\n")
        let system = """
        These are story headlines from several editors (key|importance|sources|headline). Many describe the same event.
        Merge entries that are the same event (same launch, same paper, same deal). Do not merge merely related stories. Keep every key exactly once.
        For merged groups give the best headline and the highest importance (+1 if 3 or more entries were merged, max 5).
        Reply JSON only: {"groups":[{"t":"headline","from":[keys],"imp":n}]}
        """
        let text = try await ds.chat(system: system, user: list, json: true, maxTokens: 16000)
        struct G: Decodable { let t: String; let from: [Int]; let imp: Int }
        struct W: Decodable { let groups: [G] }
        let groups = try JSONDecoder().decode(W.self, from: Data(text.utf8)).groups
        var used = Set<Int>()
        var out: [EditedStory] = []
        for g in groups {
            let keys = g.from.filter { stories.indices.contains($0) && !used.contains($0) }
            guard !keys.isEmpty else { continue }
            used.formUnion(keys)
            out.append(EditedStory(t: g.t, ids: Array(Set(keys.flatMap { stories[$0].ids })).sorted(), imp: min(5, max(1, g.imp))))
        }
        for (k, s) in stories.enumerated() where !used.contains(k) { out.append(s) }   // never lose a story
        return out
    }

    static func plan(_ stories: [EditedStory], items: [APIItem], minutes: Int) -> EpisodePlan {
        func weight(_ s: EditedStory) -> Double {
            let best = s.ids.map { Double(items[$0].score ?? 0) }.max() ?? 0
            let picked = s.ids.contains { items[$0].selected == true || items[$0].firstParty == true } ? 8.0 : 0
            return Double(s.imp) * 100 + Double(min(s.ids.count, 8)) * 6 + best + picked
        }
        let ranked = stories.sorted { weight($0) > weight($1) }
        let nDeep = max(2, min(12, Int(Double(minutes) * 0.45)))
        let nBrief = max(3, Int(Double(minutes) * 0.9))
        let nQuick = max(4, Int(Double(minutes) * 1.3))
        var deep: [(String, EditedStory)] = [], brief: [(String, EditedStory)] = [], quick: [(String, EditedStory)] = []
        var rest: [EditedStory] = []
        for s in ranked {
            if deep.count < nDeep && s.imp >= 3 { deep.append(("S\(deep.count + brief.count + quick.count + 1)", s)) }
            else if brief.count < nBrief && s.imp >= 2 { brief.append(("S\(deep.count + brief.count + quick.count + 1)", s)) }
            else if quick.count < nQuick { quick.append(("S\(deep.count + brief.count + quick.count + 1)", s)) }
            else { rest.append(s) }
        }
        var notes: [ShowNote] = []
        var keyToNote: [String: Int] = [:]
        func links(_ s: EditedStory) -> [StoryLink] {
            s.ids.prefix(6).map { i in
                let a = items[i]
                return StoryLink(title: a.originalTitle ?? a.title, source: NewsEngine.cleanSource(a.source?.name ?? ""),
                                 url: a.links.original ?? a.links.aihot ?? "https://aihot.news/items/\(a.id)")
            }
        }
        for (tier, group) in [("deep", deep), ("brief", brief), ("quick", quick)] {
            for (k, s) in group {
                keyToNote[k] = notes.count
                notes.append(ShowNote(id: notes.count, title: s.t, importance: s.imp, tier: tier, links: links(s)))
            }
        }
        for s in rest { notes.append(ShowNote(id: notes.count, title: s.t, importance: s.imp, tier: "notes", links: links(s))) }
        return EpisodePlan(deep: deep, brief: brief, quick: quick, rest: rest, notes: notes, keyToNote: keyToNote)
    }
}

// MARK: - Writer

struct WrittenScript {
    var title: String
    var summary: String
    var chapters: [PodcastChapter]
    var lines: [PodcastLine]
}

enum PodcastWriter {
    static func write(plan: EpisodePlan, items: [APIItem], days: Int, since: Date, minutes: Int, cfg: Config, ds: DeepSeek) async throws -> WrittenScript {
        let duo = cfg.podFormat == "duo"
        let words = minutes * 145          // measured: MiMo voices + gaps ≈ 145 spoken words per minute
        let lineTarget = words / 22        // LLMs follow a line count far better than a word count
        let df = DateFormatter(); df.dateFormat = "EEEE, MMMM d"
        let period = days == 1 ? "the last 24 hours (up to \(df.string(from: Date())))"
            : "the last \(days) days (\(df.string(from: since)) to \(df.string(from: Date())))"

        func report(_ i: Int, full: Bool) -> [String: Any] {
            let a = items[i]
            var r: [String: Any] = ["source": NewsEngine.cleanSource(a.source?.name ?? ""), "title": a.title]
            if let o = a.originalTitle, Translator.isLatin(o) { r["original_title"] = o }
            if full { r["summary"] = a.summary ?? ""; if let why = a.reason { r["why"] = why } }
            return r
        }
        var data: [[String: Any]] = []
        for (k, s) in plan.deep { data.append(["key": k, "tier": "deep", "headline": s.t, "importance": s.imp, "reports": s.ids.prefix(4).map { report($0, full: true) }]) }
        for (k, s) in plan.brief { data.append(["key": k, "tier": "brief", "headline": s.t, "importance": s.imp, "reports": s.ids.prefix(2).map { report($0, full: true) }]) }
        for (k, s) in plan.quick { data.append(["key": k, "tier": "quick", "headline": s.t, "reports": s.ids.prefix(1).map { report($0, full: false) }]) }
        let user = String(decoding: try JSONSerialization.data(withJSONObject: ["stories": data, "more_in_show_notes": plan.rest.count]), as: UTF8.self)

        let hosts = duo
            ? "A = \(cfg.podHostAName): \(cfg.podHostAPersona)\nB = \(cfg.podHostBName): \(cfg.podHostBPersona)"
            : "A = \(cfg.podHostAName): \(cfg.podHostAPersona) (solo host, speaking directly to the listener)"
        let system = """
        You are the head writer of "\(PodcastStyle.show)", a \(duo ? "two-host" : "solo") AI-news podcast. Write the complete script of an episode covering \(period).
        \(Translator.guardrail) The story data is mostly Chinese; the script must be natural spoken American English.

        HOSTS
        \(hosts)
        TONE: \(PodcastStyle.scene(cfg.podTone))
        LENGTH: \(minutes) minutes ≈ \(words) spoken words ≈ \(lineTarget) lines of about 22 words. Write AT LEAST \(lineTarget) lines — scripts that come in short are rejected.
        Rough budget: cold open 5%, deep dives 50%, briefs 20%, quick hits 15%, wrap 10%.

        STRUCTURE (each chapter has a short title)
        1. "Cold open": hook with the single biggest story in one or two lines, then welcome the listener, name the period, preview three headlines.
        2. Deep-dive chapters for tier "deep" stories (you may group related ones by theme): what happened, key numbers, why it matters, what to watch next.\(duo ? " The hosts react, ask each other questions and occasionally disagree." : "")
        3. A "Briefs" chapter for tier "brief" stories, 2-4 lines each.
        4. A "Quick hits" chapter: rapid-fire one-liners for every tier "quick" story\(duo ? ", hosts alternating" : "").
        5. "Wrap": the key takeaway, mention that the show notes list all stories (\(plan.rest.count) more beyond those covered), sign off.

        FACTS
        - Use only facts present in the story data. Attribute claims ("OpenAI says…", "according to The Decoder…"). Flag rumours as rumours.
        - Never invent numbers, dates, quotes, or opinions of real people. Hosts may give their own reactions and analysis.

        WRITING FOR THE EAR
        - Short sentences, contractions, natural transitions. No URLs, markdown, emoji or lists.
        - Write numbers and versions as spoken ("GPT six point one", "about two point five billion dollars", "seventy-one percent").
        - Spell out acronyms that are read letter by letter with spaces only when ambiguous ("M C P" is fine as "MCP").
        - Each line at most 60 words.\(duo ? " Neither host speaks more than 3 lines in a row; keep the exchange balanced." : "")

        DELIVERY MARKUP (for Xiaomi MiMo expressive TTS)
        - "d": a director's note for THIS line: emotion, pace, emphasis. e.g. "amused, a little faster, lean on 'open weights'". Required on every line.
        - Put the emotion in BOTH places: the detailed note in "d", and (per the target below) a short leading tag in "t".
        - "t": the spoken words. It may START with one style tag in parentheses, e.g. "(excited) …", "(thoughtful) …", "(deadpan) …", "(sighs) …".
        - Inline audio tags in square brackets may appear anywhere in "t": [laughs], [chuckles], [sighs], [pause], [inhales], [clears throat], [whispers].
        - \(PodcastStyle.writerRule(cfg.podExpressiveness))
        - Match emotion to content: sober for safety incidents, layoffs, outages or harm; curious for research; upbeat for launches. Never laugh about harm.
        - Do not write stage directions as plain words outside tags.

        OUTPUT: JSON only:
        {"title":"catchy episode title (max 8 words)","summary":"two-sentence episode description",
         "chapters":[{"title":"…","stories":["S1","S2"]}],
         "lines":[{"c":chapterIndex,"s":"A"\(duo ? "|\"B\"" : ""),"d":"…","t":"…"}]}
        """
        struct L: Decodable { let c: Int?; let s: String?; let d: String?; let t: String }
        struct C: Decodable { let title: String; let stories: [String]? }
        struct W: Decodable { let title: String?; let summary: String?; let chapters: [C]?; let lines: [L] }

        var lastError: Error = DeepSeek.Failure.empty
        for attempt in 0..<2 {
            do {
                let text = try await ds.chat(system: system, user: user, json: true, maxTokens: min(24_000, words * 3 + 3000))
                let w = try JSONDecoder().decode(W.self, from: Data(text.utf8))
                let chapters = (w.chapters ?? [C(title: "Episode", stories: nil)]).enumerated().map { i, c in
                    PodcastChapter(id: i, title: c.title, stories: (c.stories ?? []).compactMap { plan.keyToNote[$0] })
                }
                let maxC = chapters.count - 1
                var lines = w.lines.enumerated().compactMap { i, l -> PodcastLine? in
                    let t = l.t.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !t.isEmpty else { return nil }
                    let s = duo && l.s?.uppercased() == "B" ? "B" : "A"
                    return PodcastLine(id: i, speaker: s, direction: l.d ?? "", text: t, chapter: min(max(0, l.c ?? 0), maxC))
                }
                guard lines.count >= 6 else { throw DeepSeek.Failure.empty }
                lines = ensureStyleTags(lines, level: cfg.podExpressiveness)
                return WrittenScript(title: w.title ?? "AI News Hot", summary: w.summary ?? "", chapters: chapters, lines: lines)
            } catch {
                lastError = error
                Log.info("writer attempt \(attempt + 1) failed: \(error)")
            }
        }
        throw lastError
    }
}

extension PodcastWriter {
    /// Safety net: if the writer under-used leading style tags, derive them from the director's note
    /// ("dry, slightly amused" → "(dry, slightly amused)") so MiMo's tag control is actually exercised.
    static func ensureStyleTags(_ lines: [PodcastLine], level: Int) -> [PodcastLine] {
        let target = [1: 0.15, 2: 0.4, 3: 0.75][level] ?? 0.4
        var out = lines
        let tagged = out.filter { $0.text.hasPrefix("(") }.count
        var need = Int(target * Double(out.count)) - tagged
        guard need > 0 else { return out }
        let candidates = out.indices.filter { !out[$0].text.hasPrefix("(") && !out[$0].direction.isEmpty }
        guard !candidates.isEmpty else { return out }
        let step = max(1, candidates.count / need)
        for i in stride(from: 0, to: candidates.count, by: step) where need > 0 {
            let idx = candidates[i]
            let words = out[idx].direction.split(separator: ",").first.map { $0.split(separator: " ").prefix(4).joined(separator: " ") } ?? ""
            guard !words.isEmpty else { continue }
            out[idx].text = "(\(words.lowercased())) " + out[idx].text
            need -= 1
        }
        return out
    }
}

// MARK: - Recorder (one MiMo call per line, director-mode style prompt)

enum PodcastRecorder {
    static func record(_ lines: [PodcastLine], chapters: [PodcastChapter], cfg: Config, key: String,
                       progress: @escaping @Sendable (Int) -> Void) async throws -> [Data] {
        let duo = cfg.podFormat == "duo"
        func style(_ l: PodcastLine) -> String {
            if l.speaker == "J" {
                return "Sing this as a short, bright, catchy radio-show jingle with a confident, upbeat melody."
            }
            let me = l.speaker == "B" ? (cfg.podHostBName, cfg.podHostBPersona) : (cfg.podHostAName, cfg.podHostAPersona)
            let chapter = chapters.first { $0.id == l.chapter }?.title ?? ""
            return """
            [Character] \(me.0). \(me.1)
            [Scene] Recording "\(PodcastStyle.show)". \(PodcastStyle.scene(cfg.podTone)) \(duo ? "Talking with co-host \(l.speaker == "B" ? cfg.podHostAName : cfg.podHostBName), for listeners." : "Talking directly to the listener.") Current segment: \(chapter).
            [Guidance] \(l.direction.isEmpty ? "Natural delivery." : l.direction). \(PodcastStyle.ttsRule(cfg.podExpressiveness)) Natural American English, clear diction, podcast microphone close.
            """
        }
        func voice(_ l: PodcastLine) -> String { l.speaker == "B" ? cfg.podHostBVoice : cfg.podHostAVoice }

        var results = [Data?](repeating: nil, count: lines.count)
        var done = 0
        var failures = 0
        try await withThrowingTaskGroup(of: (Int, Data?).self) { group in
            var next = 0
            func add() {
                guard next < lines.count else { return }
                let i = next; next += 1
                let l = lines[i], st = style(l), v = voice(l)
                group.addTask {
                    do {
                        var pcm = Data()
                        for part in splitForTTS(clean(l.text)) {
                            if !pcm.isEmpty { pcm.append(Data(count: Int(0.12 * Double(MiMoTTS.sampleRate)) * 2)) }
                            pcm.append(try await MiMoTTS.checkedPCM(text: part, style: st, voice: v, key: key))
                        }
                        return (i, pcm)
                    } catch is CancellationError {
                        throw CancellationError()
                    } catch {
                        Log.info("TTS line \(i) failed: \(error.localizedDescription)")
                        return (i, nil)
                    }
                }
            }
            for _ in 0..<5 { add() }   // MiMo allows 100 RPM; 5 in flight is plenty and polite
            while let (i, pcm) = try await group.next() {
                results[i] = pcm
                if pcm == nil { failures += 1 }
                done += 1
                progress(done)
                add()
            }
        }
        if failures > max(2, lines.count / 8) {
            throw DeepSeek.Failure.http(502, "MiMo failed on \(failures) of \(lines.count) lines. Try again in a minute.")
        }
        return results.map { $0 ?? Data() }
    }

    static func clean(_ s: String) -> String {
        s.replacingOccurrences(of: #"https?://\S+"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"[*_#`]"#, with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Keep each request comfortably inside the model's limits; split on sentence boundaries.
    static func splitForTTS(_ s: String, max: Int = 480) -> [String] {
        guard s.count > max else { return [s] }
        var parts: [String] = [], cur = ""
        s.enumerateSubstrings(in: s.startIndex..., options: .bySentences) { sub, _, _, _ in
            guard let sub else { return }
            if cur.count + sub.count > max, !cur.isEmpty { parts.append(cur); cur = "" }
            cur += sub
        }
        if !cur.isEmpty { parts.append(cur) }
        return parts
    }
}

// MARK: - Mixer (level-match, natural gaps, AAC export)

enum PodcastMixer {
    struct Result { var url: URL; var duration: Double; var lines: [PodcastLine] }

    static func mix(lines: [PodcastLine], pcms: [Data], to url: URL) throws -> Result {
        let rate = Double(MiMoTTS.sampleRate)
        var out = [Int16]()
        out.reserveCapacity(pcms.reduce(0) { $0 + $1.count / 2 } + lines.count * 12_000)
        var timed = lines
        func silence(_ sec: Double) { out.append(contentsOf: repeatElement(0, count: Int(sec * rate))) }

        silence(0.35)
        for (i, l) in lines.enumerated() {
            if i > 0 {
                let prev = lines[i - 1]
                silence(l.chapter != prev.chapter ? 0.85 : prev.speaker == "J" ? 0.5 : l.speaker == prev.speaker ? 0.22 : 0.3)
            }
            timed[i].start = Double(out.count) / rate
            out.append(contentsOf: normalized(pcms[i]))
            timed[i].end = Double(out.count) / rate
        }
        silence(0.8)

        let duration = Double(out.count) / rate
        do {
            try writeAAC(out, to: url)
            return Result(url: url, duration: duration, lines: timed)
        } catch {
            // AAC encoder unavailable for some reason: fall back to WAV.
            let wavURL = url.deletingPathExtension().appendingPathExtension("wav")
            let bytes = out.withUnsafeBufferPointer { Data(buffer: $0) }
            try MiMoTTS.wav(pcm: bytes, sampleRate: MiMoTTS.sampleRate).write(to: wavURL)
            return Result(url: wavURL, duration: duration, lines: timed)
        }
    }

    /// Bring every line to the same loudness (≈ -19 dBFS RMS) so hosts and takes sound consistent.
    static func normalized(_ data: Data) -> [Int16] {
        var s = data.withUnsafeBytes { Array($0.bindMemory(to: Int16.self)) }
        guard !s.isEmpty else { return [] }
        let rms = sqrt(s.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(s.count)) / 32768
        guard rms > 0.002 else { return s }
        let gain = min(4.0, 0.112 / rms)
        for i in s.indices {
            var v = Double(s[i]) * gain / 32768
            if abs(v) > 0.9 { v = (v > 0 ? 1 : -1) * (0.9 + 0.1 * tanh((abs(v) - 0.9) / 0.1)) }   // soft limiter
            s[i] = Int16(max(-32767, min(32767, v * 32767)))
        }
        return s
    }

    static func writeAAC(_ samples: [Int16], to url: URL) throws {
        try? FileManager.default.removeItem(at: url)
        let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: Double(MiMoTTS.sampleRate),
                                       AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64_000]
        let file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatInt16, interleaved: true)
        let chunk = Int(MiMoTTS.sampleRate) * 10
        var offset = 0
        while offset < samples.count {
            let n = min(chunk, samples.count - offset)
            guard let buf = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(n)) else { break }
            buf.frameLength = AVAudioFrameCount(n)
            samples.withUnsafeBufferPointer { src in
                buf.int16ChannelData![0].update(from: src.baseAddress! + offset, count: n)
            }
            try file.write(from: buf)
            offset += n
        }
    }
}
