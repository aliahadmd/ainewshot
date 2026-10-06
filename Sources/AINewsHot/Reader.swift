import AppKit
import SwiftUI

/// A plain window that shows a translated Markdown document (briefings, story timelines, explainers).
@MainActor
final class ReaderController: NSObject, NSWindowDelegate {
    private static var open: [ReaderController] = []
    private let window: NSWindow
    private let model: ReaderModel

    private init(model: ReaderModel) {
        self.model = model
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 720, height: 780),
                          styleMask: [.titled, .closable, .resizable, .miniaturizable, .fullSizeContentView],
                          backing: .buffered, defer: false)
        super.init()
        window.title = model.title
        window.titlebarAppearsTransparent = true
        window.isReleasedWhenClosed = false
        window.contentViewController = NSHostingController(rootView: ReaderView(model: model))
        window.setContentSize(NSSize(width: 720, height: 780))
        window.center()
        window.delegate = self
        window.level = .floating
    }

    static func open(title: String, link: URL?, doc: TranslatedDoc) {
        show(ReaderModel(title: title, link: link, doc: doc))
    }

    static func open(title: String, link: URL?, load: @escaping @MainActor () async throws -> TranslatedDoc) {
        let m = ReaderModel(title: title, link: link, doc: nil)
        show(m)
        Task {
            do { m.doc = try await load() } catch { m.error = error.localizedDescription }
        }
    }

    private static func show(_ m: ReaderModel) {
        let c = ReaderController(model: m)
        open.append(c)
        NSApp.activate()
        c.window.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) {
        if Speaker.shared.playingID == model.speechItem.id { Speaker.shared.stop() }
        Self.open.removeAll { $0 === self }
    }
}

@MainActor
final class ReaderModel: ObservableObject {
    let title: String
    let link: URL?
    @Published var doc: TranslatedDoc?
    @Published var error: String?
    init(title: String, link: URL?, doc: TranslatedDoc?) { self.title = title; self.link = link; self.doc = doc }

    var speechItem: NewsItem {
        var n = NewsItem(id: "doc-\(title.hashValue)", kind: .briefing, title: title, summary: "")
        n.spoken = doc.map { $0.spoken.isEmpty ? Translator.spokenFallback($0.markdown) : $0.spoken }
        return n
    }
}

struct ReaderView: View {
    @ObservedObject var model: ReaderModel
    @ObservedObject var speaker = Speaker.shared

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(model.title).font(.headline).lineLimit(1)
                Spacer()
                if model.doc != nil {
                    Button {
                        speaker.toggle(model.speechItem)
                    } label: {
                        let on = speaker.playingID == model.speechItem.id
                        Label(speaker.preparingID == model.speechItem.id ? "Preparing…" : on ? "Stop" : "Listen",
                              systemImage: on ? "stop.fill" : "speaker.wave.2.fill")
                    }
                    Button {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(model.doc?.markdown ?? "", forType: .string)
                    } label: { Label("Copy", systemImage: "doc.on.doc") }
                }
                if let link = model.link {
                    Button { NSWorkspace.shared.open(link) } label: { Label("Open source", systemImage: "safari") }
                }
            }
            .padding(.horizontal, 20).padding(.top, 34).padding(.bottom, 12)
            Divider()
            ScrollView {
                Group {
                    if let doc = model.doc {
                        MarkdownBlocks(markdown: doc.markdown)
                    } else if let e = model.error {
                        Label(e, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    } else {
                        HStack(spacing: 10) { ProgressView().controlSize(.small); Text("Fetching and translating…") }
                            .foregroundStyle(.secondary).padding(.top, 40)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(24)
                .textSelection(.enabled)
            }
        }
        .frame(minWidth: 480, minHeight: 400)
    }
}

/// Minimal block-level Markdown renderer (SwiftUI Text only handles inline Markdown).
struct MarkdownBlocks: View {
    let markdown: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(markdown.components(separatedBy: "\n").enumerated()), id: \.offset) { _, raw in
                line(raw)
            }
        }
    }

    @ViewBuilder
    private func line(_ raw: String) -> some View {
        let t = raw.trimmingCharacters(in: .whitespaces)
        if t.isEmpty {
            Spacer().frame(height: 2)
        } else if t.hasPrefix("### ") {
            inline(String(t.dropFirst(4))).font(.headline).padding(.top, 4)
        } else if t.hasPrefix("## ") {
            inline(String(t.dropFirst(3))).font(.title3.weight(.semibold)).padding(.top, 8)
        } else if t.hasPrefix("# ") {
            inline(String(t.dropFirst(2))).font(.title.weight(.bold)).padding(.bottom, 4)
        } else if t.hasPrefix("- ") || t.hasPrefix("* ") {
            HStack(alignment: .firstTextBaseline, spacing: 8) { Text("•"); inline(String(t.dropFirst(2))) }
                .padding(.leading, raw.hasPrefix("  ") ? 18 : 0)
        } else if t.hasPrefix("> ") {
            inline(String(t.dropFirst(2))).foregroundStyle(.secondary).padding(.leading, 12)
                .overlay(alignment: .leading) { Rectangle().fill(.tertiary).frame(width: 3) }
        } else if t.hasPrefix("---") {
            Divider()
        } else if t.hasPrefix("【") || (t.hasPrefix("[") && t.hasSuffix("]") && !t.contains("](")) {
            Text(t.trimmingCharacters(in: CharacterSet(charactersIn: "【】[]"))).font(.title3.weight(.semibold)).padding(.top, 10)
        } else {
            inline(t).padding(.leading, raw.hasPrefix("   ") ? 22 : 0)
                .foregroundStyle(raw.hasPrefix("   ") ? .secondary : .primary)
        }
    }

    private func inline(_ s: String) -> Text {
        if let a = try? AttributedString(markdown: s, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)) {
            return Text(a)
        }
        return Text(s)
    }
}
