import Foundation

// MARK: - AIHOT v1 wire types (https://aihot.news/openapi-v1.json)

struct APIItem: Codable, Hashable {
    struct Source: Codable, Hashable { let name: String }
    struct Links: Codable, Hashable { let aihot: String?; let original: String?; let story: String? }
    let id: String
    let title: String
    let originalTitle: String?
    let summary: String?
    let source: Source?
    let links: Links
    let publishedAt: Date?
    var discoveredAt: Date?
    let category: String?
    let score: Int?
    let reason: String?
    var selected: Bool? = nil
    var tags: [String]? = nil          // only the site timeline carries tags
    var firstParty: Bool? = nil
}

struct ItemsResponse: Codable { let items: [APIItem] }

/// The feed behind https://aihot.news/?channel=firstParty ("只看一手": official sources only).
/// Undocumented site endpoint, so callers must tolerate it changing shape.
struct TimelineResponse: Codable {
    struct Item: Codable {
        let id: String
        let title: String
        let summary: String?
        let reason: String?
        let source: APIItem.Source?
        let publishedAt: Date?
        let timelineAt: Date?
        let category: String?
        let tags: [String]?
        let score: Int?
    }
    struct Card: Codable { let item: Item }
    let cards: [Card]
    let nextCursor: String?
}

struct HotTopic: Codable, Hashable {
    let rank: Int
    let id: String
    let title: String
    let source: APIItem.Source?
    let links: APIItem.Links
    let sourceCount: Int?
    let sourceNames: [String]?
    let latestAt: Date?

    var storyID: String? { links.story.flatMap { URL(string: $0)?.lastPathComponent } }
}

struct HotResponse: Codable { let items: [HotTopic] }

struct StoryResponse: Codable {
    struct Story: Codable { let publicId: String; let title: String; let digest: String?; let sourceCount: Int? }
    let story: Story
}

// MARK: - Our model

struct NewsItem: Codable, Identifiable, Hashable {
    enum Kind: String, Codable { case news, hot, briefing, digest, podcast }

    var id: String
    var kind: Kind = .news
    var zhTitle = ""
    var zhSummary = ""
    var originalTitle: String?
    var title: String
    var summary: String
    var why: String?
    var spoken: String?
    var relevance: Int?
    var score: Int?
    var category: String?
    var sourceName = ""
    var aihotURL: URL?
    var originalURL: URL?
    var storyID: String?
    var publishedAt: Date?
    var discoveredAt = Date()
    var hotSources: Int?
    var hotRank: Int?
    var tags: [String]?
    var firstParty: Bool?
    var episodeID: String?
    var alerted = false
    var markdown: String?          // briefings / digests carry a full document

    var speechText: String {
        if let spoken, !spoken.isEmpty { return spoken }
        switch kind {
        case .hot: return "Trending across \(hotSources ?? 0) sources. \(title). \(summary)"
        default: return "\(title). \(summary)"
        }
    }
}

// MARK: - Client

/// Anonymous, read-only AIHOT client. Uses ETag/If-None-Match so unchanged polls cost a tiny 304.
actor AIHotClient {
    static let shared = AIHotClient()

    enum Failure: Error, LocalizedError {
        case http(Int), rateLimited(TimeInterval)
        var errorDescription: String? {
            switch self {
            case .http(let c): "AIHOT returned HTTP \(c)"
            case .rateLimited(let s): "AIHOT rate limit; retry in \(Int(s))s"
            }
        }
    }

    private let base = URL(string: "https://aihot.news")!
    private var etags: [String: String] = [:]
    private let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 30
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = ["User-Agent": "AINewsHot/1.0 (macOS; personal reader)"]
        return URLSession(configuration: c)
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        let frac = ISO8601DateFormatter(); frac.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let plain = ISO8601DateFormatter()
        d.dateDecodingStrategy = .custom { dec in
            let s = try dec.singleValueContainer().decode(String.self)
            if let v = frac.date(from: s) ?? plain.date(from: s) { return v }
            throw DecodingError.dataCorrupted(.init(codingPath: dec.codingPath, debugDescription: "bad date \(s)"))
        }
        return d
    }()

    /// Returns nil when the server says nothing changed (304).
    func fetch(_ path: String, query: [String: String] = [:], conditional: Bool = true) async throws -> Data? {
        var comps = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty {
            comps.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        let url = comps.url!
        var req = URLRequest(url: url)
        if conditional, let tag = etags[url.absoluteString] { req.setValue(tag, forHTTPHeaderField: "If-None-Match") }
        let (data, resp) = try await session.data(for: req)
        let http = resp as! HTTPURLResponse
        switch http.statusCode {
        case 200:
            if conditional, let tag = http.value(forHTTPHeaderField: "ETag") { etags[url.absoluteString] = tag }
            return data
        case 304:
            return nil
        case 429:
            throw Failure.rateLimited(Double(http.value(forHTTPHeaderField: "Retry-After") ?? "") ?? 60)
        default:
            throw Failure.http(http.statusCode)
        }
    }

    func items(mode: String, window: String = "24h", limit: Int = 50) async throws -> [APIItem]? {
        guard let data = try await fetch("api/v1/items", query: ["mode": mode, "window": window, "limit": String(limit)]) else { return nil }
        return try Self.decoder.decode(ItemsResponse.self, from: data).items
    }

    /// Everything discovered since `since`, paging back with the cursor until we pass it.
    /// coverage: "all" | "selected" | "firstParty". Pages are spaced out to stay well under ~60 req/min.
    func itemsSince(_ since: Date, coverage: String, progress: @escaping @Sendable (Int) -> Void) async throws -> [APIItem] {
        var out: [APIItem] = []
        var cursor: String?
        let days = Date().timeIntervalSince(since) / 86_400
        for page in 0..<80 {
            try Task.checkCancellation()
            if page > 0 { try await Task.sleep(for: .milliseconds(700)) }
            var batch: [APIItem]
            var next: String?
            if coverage == "firstParty" {
                var q = ["channel": "firstParty"]
                if let cursor { q["cursor"] = cursor }
                let data = try await fetch("api/site/timeline", query: q, conditional: false) ?? Data()
                let r = try Self.decoder.decode(TimelineResponse.self, from: data)
                batch = r.cards.map { c in
                    let t = c.item
                    var a = APIItem(id: t.id, title: t.title, originalTitle: nil, summary: t.summary, source: t.source,
                                    links: .init(aihot: "https://aihot.news/items/\(t.id)", original: nil, story: nil),
                                    publishedAt: t.publishedAt, discoveredAt: t.timelineAt, category: t.category,
                                    score: t.score, reason: t.reason)
                    a.tags = t.tags
                    a.firstParty = true
                    return a
                }
                next = r.nextCursor
            } else {
                var q = ["mode": coverage == "selected" ? "selected" : "all",
                         "window": days <= 1.01 ? "24h" : "7d", "limit": "100"]
                if let cursor { q["cursor"] = cursor }
                let data = try await fetch("api/v1/items", query: q, conditional: false) ?? Data()
                struct Paged: Decodable { struct P: Decodable { let hasMore: Bool?; let nextCursor: String? }; let items: [APIItem]; let page: P }
                let r = try Self.decoder.decode(Paged.self, from: data)
                batch = r.items
                next = r.page.hasMore == true ? r.page.nextCursor : nil
            }
            out += batch.filter { ($0.discoveredAt ?? $0.publishedAt ?? .distantPast) >= since }
            progress(out.count)
            let oldest = batch.compactMap { $0.discoveredAt ?? $0.publishedAt }.min() ?? .distantPast
            guard let n = next, oldest >= since else { break }
            cursor = n
        }
        return out
    }

    func firstPartyTimeline() async throws -> [TimelineResponse.Item]? {
        guard let data = try await fetch("api/site/timeline", query: ["channel": "firstParty"]) else { return nil }
        return try Self.decoder.decode(TimelineResponse.self, from: data).cards.map(\.item)
    }

    func hotTopics() async throws -> [HotTopic]? {
        guard let data = try await fetch("api/v1/hot-topics") else { return nil }
        return try Self.decoder.decode(HotResponse.self, from: data).items
    }

    func story(_ id: String) async throws -> StoryResponse.Story {
        let data = try await fetch("api/v1/stories/\(id)", conditional: false) ?? Data()
        return try Self.decoder.decode(StoryResponse.self, from: data).story
    }

    /// Agent endpoints return ready-made Chinese Markdown (daily, weekly, hot, stories).
    func markdown(_ path: String) async throws -> String {
        let data = try await fetch(path, conditional: false) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }
}

/// AIHOT's topic tags are mostly Chinese; names of companies are already English.
enum Tags {
    static let english: [String: String] = [
        "产品更新": "Product update", "编码": "Coding", "教程/实践": "Tutorial", "部署/工程": "Engineering",
        "安全/对齐": "Safety", "模型发布": "Model release", "论文/研究": "Research", "推理": "Reasoning",
        "行业动态": "Industry", "多模态": "Multimodal", "MCP/工具调用": "MCP/Tools", "评测/基准": "Benchmarks",
        "开源生态": "Open source", "数据/训练": "Training data", "端侧": "On-device", "语音": "Voice",
        "大佬观点": "Opinion", "开源/仓库": "Open-source repo", "具身智能": "Robotics", "图像生成": "Image gen",
        "视频生成": "Video gen", "融资/商业": "Funding", "政策/监管": "Policy", "芯片/算力": "Chips/Compute",
    ]
    static func en(_ t: String) -> String { english[t] ?? t }
}
