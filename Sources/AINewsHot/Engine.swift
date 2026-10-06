import Foundation
import AppKit

/// Polls AIHOT, translates, filters, and routes alerts while respecting pause, quiet hours,
/// screen lock and a popup rate limit. Anything held back is delivered later as one digest.
@MainActor
final class NewsEngine: ObservableObject {
    static let shared = NewsEngine()

    @Published private(set) var recent: [NewsItem] = []
    @Published private(set) var lastCheck: Date?
    @Published private(set) var lastError: String?
    @Published private(set) var isChecking = false
    @Published private(set) var pausedUntil: Date?
    @Published private(set) var pendingCount = 0
    @Published var unread = 0

    private struct State: Codable {
        var seen: [String] = []
        var alerted: [String] = []
        var recent: [NewsItem] = []
        var pending: [NewsItem] = []
        var bootNews = false
        var bootHot = false
        var lastBriefingDay: String?
        var lastWeekly: String?
        var pausedUntil: Date?
        var bootMode: String?               // feed mode the bootstrap was done for
        var firstPartySources: [String] = [] // learned from the site timeline; powers the fallback
        var lastPodcastDay: String?
    }

    private var state = State()
    private var seenSet = Set<String>()
    private var alertedSet = Set<String>()
    private var popupTimes: [Date] = []
    private var screenLocked = false
    private var loop: Task<Void, Never>?
    private var tickCount = 0
    private var backoffUntil: Date?
    private let stateURL = Paths.support.appendingPathComponent("state.json")

    private var cfg: Config { ConfigStore.shared.c }
    private var keys: Secrets { SecretsStore.shared.s }

    private init() {
        if let data = try? Data(contentsOf: stateURL) { state = decodeOverDefaults(data, defaults: State()) }
        seenSet = Set(state.seen)
        alertedSet = Set(state.alerted)
        recent = state.recent
        pausedUntil = state.pausedUntil.flatMap { $0 > Date() ? $0 : nil }
        pendingCount = state.pending.count
    }

    // MARK: Lifecycle

    func start() {
        let dnc = DistributedNotificationCenter.default()
        dnc.addObserver(forName: .init("com.apple.screenIsLocked"), object: nil, queue: .main) { _ in
            Task { @MainActor in self.screenLocked = true }
        }
        dnc.addObserver(forName: .init("com.apple.screenIsUnlocked"), object: nil, queue: .main) { _ in
            Task { @MainActor in
                self.screenLocked = false
                try? await Task.sleep(for: .seconds(2))
                self.flushPending()
            }
        }
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(8))   // let Wi-Fi reconnect
                await self.tick()
            }
        }
        loop = Task {
            while !Task.isCancelled {
                await tick()
                try? await Task.sleep(for: .seconds(max(60, cfg.pollSeconds)))
            }
        }
    }

    func checkNow() { Task { await tick(force: true) } }

    private func tick(force: Bool = false) async {
        if let b = backoffUntil, b > Date(), !force { return }
        guard !isChecking else { return }
        isChecking = true
        defer { isChecking = false }
        tickCount += 1
        await checkNews()
        if cfg.hotEnabled && (force || tickCount % 2 == 1) { await checkHot() }
        await checkBriefings()
        flushPending()
        save()
    }

    // MARK: News

    @Published private(set) var feedNote: String?
    private var v1Cache: [String: APIItem] = [:]
    private var siteFailures = 0

    /// Exactly the web's "精选 · 只看一手" list. The site timeline decides *which* items;
    /// the official v1 API supplies the English original title and article link for them.
    private func firstPartyFeed() async throws -> [APIItem]? {
        do {
            guard let timeline = try await AIHotClient.shared.firstPartyTimeline() else { return nil }
            siteFailures = 0
            feedNote = nil
            let learned = Set(state.firstPartySources)
            let names = Set(timeline.compactMap { $0.source?.name }).subtracting(learned)
            if !names.isEmpty { state.firstPartySources.append(contentsOf: names) }

            if let v1 = try? await AIHotClient.shared.items(mode: "selected", window: "24h", limit: 50) {
                for i in v1 { v1Cache[i.id] = i }
            }
            return timeline.map { t in
                var a = v1Cache[t.id] ?? APIItem(
                    id: t.id, title: t.title, originalTitle: nil, summary: t.summary, source: t.source,
                    links: .init(aihot: "https://aihot.news/items/\(t.id)", original: nil, story: nil),
                    publishedAt: t.publishedAt, discoveredAt: t.timelineAt, category: t.category,
                    score: t.score, reason: t.reason)
                a.tags = t.tags
                a.firstParty = true
                if a.discoveredAt == nil { a.discoveredAt = t.timelineAt }
                return a
            }
        } catch AIHotClient.Failure.rateLimited(let s) {
            throw AIHotClient.Failure.rateLimited(s)
        } catch {
            // Site endpoint changed or is blocked: official API + the first-hand sources we've learned.
            siteFailures += 1
            Log.info("firstParty timeline failed (\(siteFailures)): \(error.localizedDescription)")
            let learned = Set(state.firstPartySources)
            feedNote = learned.isEmpty ? "First-hand feed unavailable; showing all curated news"
                                       : "First-hand feed unavailable; filtering by \(learned.count) known official sources"
            guard let v1 = try await AIHotClient.shared.items(mode: "selected", window: "24h", limit: 50) else { return nil }
            return learned.isEmpty ? v1 : v1.filter { learned.contains($0.source?.name ?? "") }.map {
                var a = $0; a.firstParty = true; return a
            }
        }
    }

    private func checkNews() async {
        do {
            let items = cfg.feedMode == "firstParty"
                ? try await firstPartyFeed()
                : try await AIHotClient.shared.items(mode: cfg.feedMode)
            lastCheck = Date()
            lastError = nil
            guard let items else { return }   // 304: nothing changed
            let fresh = items.filter { !seenSet.contains($0.id) }
            guard !fresh.isEmpty else { return }

            // A new install, or a switched feed, starts quietly instead of popping a backlog.
            if state.bootMode != cfg.feedMode { state.bootNews = false; state.bootMode = cfg.feedMode }
            let bootstrap = !state.bootNews
            // First run: fill the menu with recent news, but don't fire a burst of popups.
            let batch = bootstrap ? Array(fresh.prefix(12)) : fresh
            let rendered = await Translator.items(batch.map(Self.source), cfg: cfg, keys: keys)
            let news = batch.map { Self.make($0, rendered[$0.id]) }
            for f in fresh { markSeen(f.id) }
            addRecent(news)
            if bootstrap { state.bootNews = true; return }

            // Never interrupt for stale items (backfills, long offline periods); they still land in the menu.
            let maxAge = Date().addingTimeInterval(-Double(cfg.maxAlertAgeHours) * 3600)
            let alerts = news.filter { $0.discoveredAt >= maxAge && shouldAlert($0) }
            Log.info("news: \(fresh.count) new, \(alerts.count) alert(s)")
            await deliver(alerts)
        } catch {
            handle(error)
        }
    }

    func shouldAlert(_ n: NewsItem) -> Bool {
        let tags = (n.tags ?? []).flatMap { [$0, Tags.en($0)] }
        let hay = ([n.title, n.summary, n.zhTitle, n.zhSummary, n.originalTitle ?? "", n.sourceName] + tags)
            .joined(separator: " ").lowercased()
        if cfg.keywords(cfg.excludeKeywords).contains(where: hay.contains) { return false }
        if cfg.keywords(cfg.includeKeywords).contains(where: hay.contains) { return true }
        if let c = n.category, !cfg.categories.contains(c) { return false }
        if let s = n.score, s < cfg.minScore { return false }
        if cfg.useAIRelevance, cfg.translation == .deepseek, let r = n.relevance, r < cfg.minRelevance { return false }
        return true
    }

    // MARK: Trending (multi-source stories climbing the hot list)

    private func checkHot() async {
        do {
            guard let topics = try await AIHotClient.shared.hotTopics() else { return }
            let candidates = topics.filter {
                $0.rank <= cfg.hotMaxRank && ($0.sourceCount ?? 0) >= cfg.hotMinSources
                    && !alertedSet.contains($0.id) && !alertedSet.contains("story:" + ($0.storyID ?? "-"))
            }
            if !state.bootHot {
                for t in topics { markAlerted(t.id); if let s = t.storyID { markAlerted("story:" + s) } }
                state.bootHot = true
                return
            }
            guard !candidates.isEmpty else { return }
            var sources: [SourceText] = []
            for t in candidates {
                let digest = try? await t.storyID.asyncMap { try await AIHotClient.shared.story($0).digest }
                let short = (digest ?? nil).map { String($0.split(separator: "\n").first ?? "") }
                sources.append(SourceText(id: t.id, title: t.title, originalTitle: nil, summary: short, reason: nil))
            }
            let rendered = await Translator.items(sources, cfg: cfg, keys: keys)
            let items: [NewsItem] = candidates.map { t in
                let r = rendered[t.id]
                var n = NewsItem(id: t.id, kind: .hot, zhTitle: t.title,
                                 title: r?.title ?? t.title, summary: r?.summary ?? "")
                n.spoken = r?.spoken
                n.why = "Covered by \(t.sourceCount ?? 0) sources, including " +
                    (t.sourceNames ?? []).prefix(3).map(Self.cleanSource).joined(separator: ", ") + "."
                n.relevance = r?.relevance
                n.sourceName = "Trending #\(t.rank)"
                n.aihotURL = t.links.aihot.flatMap(URL.init(string:))
                n.originalURL = t.links.original.flatMap(URL.init(string:))
                n.storyID = t.storyID
                n.hotSources = t.sourceCount
                n.hotRank = t.rank
                n.publishedAt = t.latestAt
                return n
            }
            for t in candidates { if let s = t.storyID { markAlerted("story:" + s) } }
            addRecent(items)
            let alerts = items.filter { n in
                let hay = (n.title + " " + n.zhTitle).lowercased()
                return !cfg.keywords(cfg.excludeKeywords).contains(where: hay.contains)
            }
            Log.info("hot: \(alerts.count) trending alert(s)")
            await deliver(alerts)
        } catch {
            handle(error)
        }
    }

    // MARK: Briefings

    private func checkBriefings() async {
        let now = Date()
        let comps = Calendar.current.dateComponents([.hour, .minute, .weekday], from: now)
        // Morning podcast: produced in the background so it's waiting when you sit down.
        if cfg.podAuto, (comps.hour ?? 0) * 60 + (comps.minute ?? 0) >= cfg.podAutoMinutes,
           state.lastPodcastDay != Self.dayKey(now), !PodcastStudio.shared.isBusy {
            state.lastPodcastDay = Self.dayKey(now)
            save()
            PodcastStudio.shared.generate(days: comps.weekday == 2 && cfg.podAutoWeeklyOnMonday ? 7 : 1, auto: true)
        }
        guard cfg.briefingEnabled else { return }
        guard (comps.hour ?? 0) * 60 + (comps.minute ?? 0) >= cfg.briefingMinutes else { return }
        let today = Self.dayKey(now)
        if state.lastBriefingDay != today {
            state.lastBriefingDay = today
            if let item = await briefingItem(path: "api/v1/agent/daily", kind: "Daily", link: "https://aihot.news/daily") {
                await deliver([item], bypassRateLimit: true)
            }
        }
        let week = Self.weekKey(now)
        if cfg.briefingWeekly, comps.weekday == 2, (comps.hour ?? 0) >= 11, state.lastWeekly != week {
            state.lastWeekly = week
            if let item = await briefingItem(path: "api/v1/agent/weekly", kind: "Weekly", link: "https://aihot.news/weekly") {
                await deliver([item], bypassRateLimit: true)
            }
        }
    }

    func briefingItem(path: String, kind: String, link: String) async -> NewsItem? {
        do {
            let md = try await AIHotClient.shared.markdown(path)
            let doc = await Translator.document(md, purpose: "\(kind.lowercased()) report", cfg: cfg, keys: keys)
            let df = DateFormatter(); df.dateFormat = "EEEE, MMM d"
            var n = NewsItem(id: "\(kind.lowercased())-\(Self.dayKey(Date()))", kind: .briefing,
                             title: "Your \(kind) AI briefing · \(df.string(from: Date()))",
                             summary: Self.firstSentences(doc.spoken.isEmpty ? Translator.spokenFallback(doc.markdown) : doc.spoken, 2))
            n.spoken = doc.spoken.isEmpty ? nil : doc.spoken
            n.markdown = doc.markdown
            n.sourceName = "AIHOT \(kind)"
            n.aihotURL = URL(string: link)
            return n
        } catch {
            handle(error)
            return nil
        }
    }

    // MARK: Delivery / routing

    private func deliver(_ items: [NewsItem], bypassRateLimit: Bool = false) async {
        guard !items.isEmpty else { return }
        for i in items { markAlerted(i.id) }
        setAlerted(items.map(\.id))
        // Pre-render speech so the voice starts the moment the card appears (MiMo takes 5-20 s).
        if cfg.audioEnabled && cfg.voiceProvider == .mimo {
            await withTaskGroup(of: Void.self) { outer in
                outer.addTask { @MainActor in
                    await withTaskGroup(of: Void.self) { g in
                        for i in items.prefix(4) { g.addTask { @MainActor in _ = await Speaker.shared.prepare(i) } }
                    }
                }
                outer.addTask { try? await Task.sleep(for: .seconds(35)) }
                await outer.next()
                outer.cancelAll()
            }
        }
        route(items, bypassRateLimit: bypassRateLimit)
    }

    func route(_ items: [NewsItem], bypassRateLimit: Bool = false) {
        if holdReason != nil || (!bypassRateLimit && !rateAllows && !AlertCenter.shared.isOpen) {
            state.pending.append(contentsOf: items.filter { i in !state.pending.contains { $0.id == i.id } })
            pendingCount = state.pending.count
            save()
            return
        }
        present(items)
    }

    private func present(_ items: [NewsItem]) {
        if !AlertCenter.shared.isOpen { popupTimes.append(Date()) }
        AlertCenter.shared.present(items, digest: items.count >= cfg.digestThreshold, autoplay: true)
    }

    private func flushPending() {
        guard !state.pending.isEmpty, holdReason == nil, rateAllows || AlertCenter.shared.isOpen else { return }
        let items = state.pending
        state.pending = []
        pendingCount = 0
        present(items)
        save()
    }

    var holdReason: String? {
        if let p = pausedUntil, p > Date() { return "Paused" }
        if screenLocked { return "Screen locked" }
        if cfg.quietEnabled && Self.inQuietHours(cfg) { return "Quiet hours" }
        return nil
    }

    private var rateAllows: Bool {
        popupTimes.removeAll { $0 < Date().addingTimeInterval(-3600) }
        return popupTimes.count < cfg.maxPopupsPerHour
    }

    func pause(minutes: Int?) {
        if let minutes {
            pausedUntil = Date().addingTimeInterval(Double(minutes) * 60)
        } else {
            // until 8:00 tomorrow
            var c = Calendar.current.dateComponents([.year, .month, .day], from: Date().addingTimeInterval(86_400))
            c.hour = 8
            pausedUntil = Calendar.current.date(from: c)
        }
        state.pausedUntil = pausedUntil
        save()
        Task {
            while let p = pausedUntil, p > Date() { try? await Task.sleep(for: .seconds(30)) }
            if pausedUntil != nil { resume() }
        }
    }

    func resume() {
        pausedUntil = nil
        state.pausedUntil = nil
        flushPending()
        save()
    }

    // MARK: Helpers

    private func handle(_ error: Error) {
        lastError = error.localizedDescription
        Log.info("error: \(error.localizedDescription)")
        if case AIHotClient.Failure.rateLimited(let s) = error { backoffUntil = Date().addingTimeInterval(s) }
    }

    private func markSeen(_ id: String) {
        guard seenSet.insert(id).inserted else { return }
        state.seen.append(id)
        if state.seen.count > 4000 { state.seen.removeFirst(500); seenSet = Set(state.seen) }
    }

    private func markAlerted(_ id: String) {
        guard alertedSet.insert(id).inserted else { return }
        state.alerted.append(id)
        if state.alerted.count > 4000 { state.alerted.removeFirst(500); alertedSet = Set(state.alerted) }
    }

    private func setAlerted(_ ids: [String]) {
        for i in recent.indices where ids.contains(recent[i].id) { recent[i].alerted = true }
    }

    private func addRecent(_ items: [NewsItem]) {
        let ids = Set(items.map(\.id))
        recent = (items + recent.filter { !ids.contains($0.id) })
            .sorted { ($0.discoveredAt) > ($1.discoveredAt) }
        if recent.count > 300 { recent = Array(recent.prefix(300)) }
        unread += items.count
    }

    func save() {
        state.recent = recent
        if let data = try? JSONEncoder().encode(state) { try? data.write(to: stateURL, options: .atomic) }
    }

    static func source(_ a: APIItem) -> SourceText {
        SourceText(id: a.id, title: a.title, originalTitle: a.originalTitle, summary: a.summary, reason: a.reason)
    }

    static func make(_ a: APIItem, _ r: Rendered?) -> NewsItem {
        var n = NewsItem(id: a.id, title: r?.title ?? a.originalTitle ?? a.title, summary: r?.summary ?? a.summary ?? "")
        n.zhTitle = a.title
        n.zhSummary = a.summary ?? ""
        n.originalTitle = a.originalTitle
        n.why = r?.why
        n.spoken = r?.spoken
        n.relevance = r?.relevance
        n.score = a.score
        n.category = a.category
        n.sourceName = cleanSource(a.source?.name ?? "")
        n.aihotURL = a.links.aihot.flatMap(URL.init(string:))
        n.originalURL = a.links.original.flatMap(URL.init(string:))
        n.publishedAt = a.publishedAt
        n.discoveredAt = a.discoveredAt ?? Date()
        n.tags = a.tags
        n.firstParty = a.firstParty
        return n
    }

    /// "OpenAI：官网动态（RSS · 排除…）" -> "OpenAI"
    nonisolated static func cleanSource(_ s: String) -> String {
        // "X：Mistral AI (@MistralAI)" -> "Mistral AI (@MistralAI)": the platform prefix isn't the source.
        let base = s.hasPrefix("X：") ? String(s.dropFirst(2)) : s
        var name = base.components(separatedBy: "：").first ?? base
        if let r = name.range(of: "（") { name = String(name[..<r.lowerBound]) }
        return name.trimmingCharacters(in: .whitespaces)
    }

    static func inQuietHours(_ c: Config) -> Bool {
        let comps = Calendar.current.dateComponents([.hour, .minute], from: Date())
        let m = (comps.hour ?? 0) * 60 + (comps.minute ?? 0)
        return c.quietStart <= c.quietEnd ? (m >= c.quietStart && m < c.quietEnd) : (m >= c.quietStart || m < c.quietEnd)
    }

    static func dayKey(_ d: Date) -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyy-MM-dd"; return f.string(from: d)
    }

    static func weekKey(_ d: Date) -> String {
        let c = Calendar(identifier: .iso8601).dateComponents([.yearForWeekOfYear, .weekOfYear], from: d)
        return "\(c.yearForWeekOfYear ?? 0)-W\(c.weekOfYear ?? 0)"
    }

    static func firstSentences(_ s: String, _ n: Int) -> String {
        var out = "", count = 0
        s.enumerateSubstrings(in: s.startIndex..., options: .bySentences) { sub, _, _, stop in
            out += sub ?? ""; count += 1
            if count >= n { stop = true }
        }
        return out.trimmingCharacters(in: .whitespaces)
    }
}

extension Optional {
    func asyncMap<T>(_ f: (Wrapped) async throws -> T) async rethrows -> T? {
        guard let v = self else { return nil }
        return try await f(v)
    }
}
