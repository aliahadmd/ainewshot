import AppKit
import SwiftUI

/// Owns the alert queue shown in the floating panel.
@MainActor
final class AlertCenter: ObservableObject {
    static let shared = AlertCenter()

    @Published private(set) var queue: [NewsItem] = []
    @Published var index = 0
    @Published var digestMode = false
    @Published private(set) var armed = false      // keyboard shortcuts are ignored for a moment after popping up

    private lazy var controller = AlertPanelController(center: self)
    private var spoken = Set<String>()
    private var dismissTask: Task<Void, Never>?

    var isOpen: Bool { controller.isVisible }
    var current: NewsItem? { queue.indices.contains(index) ? queue[index] : nil }

    func present(_ items: [NewsItem], digest: Bool, autoplay: Bool) {
        let new = items.filter { i in !queue.contains { $0.id == i.id } }
        guard !new.isEmpty else { return }
        if isOpen {
            queue.append(contentsOf: new)
            if digest && !digestMode && queue.count > 1 { Speaker.shared.stop() }
            return
        }
        queue = new
        index = 0
        digestMode = digest && new.count > 1
        show(autoplay: autoplay)
    }

    /// Open one specific item (menu click / hotkey), keeping anything already queued.
    func focus(_ item: NewsItem) {
        if let i = queue.firstIndex(where: { $0.id == item.id }) {
            index = i
        } else {
            queue.insert(item, at: isOpen ? index : 0)
            if !isOpen { index = 0 }
        }
        digestMode = false
        Speaker.shared.stop()
        if !isOpen { show(autoplay: false) } else { controller.bringToFront() }
    }

    private func show(autoplay: Bool) {
        armed = false
        controller.show(dim: ConfigStore.shared.c.dimBackground)
        let guardDelay = ConfigStore.shared.c.keyGuardSeconds
        Task { try? await Task.sleep(for: .seconds(guardDelay)); armed = true }
        if autoplay { autoSpeak() }
        scheduleAutoDismiss()
    }

    private func autoSpeak() {
        let cfg = ConfigStore.shared.c
        guard cfg.audioEnabled else { return }
        if cfg.holdAudioWhenMicBusy && Speaker.microphoneInUse() { return }   // you're on a call
        if PodcastPlayer.shared.isPlaying { return }                          // don't talk over the podcast
        if current?.kind == .podcast && !digestMode {
            if cfg.chime { NSSound(named: "Glass")?.play() }
            return
        }
        if digestMode {
            Speaker.shared.play(digestItem, chime: true)
        } else if let c = current, !spoken.contains(c.id) {
            spoken.insert(c.id)
            Speaker.shared.play(c, chime: true)
        }
    }

    var digestItem: NewsItem {
        let titles = queue.prefix(8).map(\.title)
        var script = "You have \(queue.count) new AI stories. "
        for (i, t) in titles.enumerated() {
            script += (i == 0 ? "First: " : i == titles.count - 1 ? "And finally: " : "Next: ") + t + ". "
        }
        var n = NewsItem(id: "digest-\(queue.map(\.id).joined().hashValue)", kind: .digest,
                         title: "\(queue.count) new AI stories", summary: "")
        n.spoken = script
        return n
    }

    private func scheduleAutoDismiss() {
        dismissTask?.cancel()
        let s = ConfigStore.shared.c.autoDismissSeconds
        guard s > 0 else { return }
        dismissTask = Task {
            try? await Task.sleep(for: .seconds(Double(s)))
            if !Task.isCancelled && Speaker.shared.playingID == nil { close() }
        }
    }

    private func touched() { dismissTask?.cancel() }

    // MARK: Actions

    func next() { touched(); guard index < queue.count - 1 else { return }; Speaker.shared.stop(); index += 1 }
    func prev() { touched(); guard index > 0 else { return }; Speaker.shared.stop(); index -= 1 }

    func open(at i: Int) {
        touched()
        Speaker.shared.stop()
        index = i
        digestMode = false
    }

    func stepThrough() {
        touched()
        Speaker.shared.stop()
        digestMode = false
        index = 0
        autoSpeak()
    }

    func dismiss() {
        touched()
        Speaker.shared.stop()
        guard queue.indices.contains(index) else { close(); return }
        queue.remove(at: index)
        if queue.isEmpty { close(); return }
        index = min(index, queue.count - 1)
        autoSpeak()
    }

    func later(minutes: Int) {
        guard let item = current else { return }
        dismiss()
        Task {
            try? await Task.sleep(for: .seconds(Double(minutes) * 60))
            spoken.remove(item.id)
            NewsEngine.shared.route([item], bypassRateLimit: true)
        }
    }

    func read() {
        guard let item = current else { return }
        switch item.kind {
        case .briefing:
            ReaderController.open(title: item.title, link: item.aihotURL, doc: TranslatedDoc(markdown: item.markdown ?? "", spoken: item.spoken ?? ""))
        case .hot:
            if let story = item.storyID { openStory(story, title: item.title) } else { openLink(item) }
        case .podcast:
            PodcastWindow.show(select: item.episodeID, autoplay: true)
        default:
            openLink(item)
        }
        dismiss()
    }

    func openLink(_ item: NewsItem) {
        if let u = item.originalURL ?? item.aihotURL { NSWorkspace.shared.open(u) }
    }

    func openStory(_ id: String, title: String) {
        let cfg = ConfigStore.shared.c, keys = SecretsStore.shared.s
        ReaderController.open(title: title, link: URL(string: "https://aihot.news/story/\(id)")) {
            let md = try await AIHotClient.shared.markdown("api/v1/agent/stories/\(id)")
            return await Translator.document(md, purpose: "story timeline", cfg: cfg, keys: keys)
        }
    }

    func explain() {
        guard let item = current else { return }
        touched()
        let cfg = ConfigStore.shared.c, keys = SecretsStore.shared.s
        ReaderController.open(title: "Explain: " + item.title, link: item.originalURL ?? item.aihotURL) {
            let md = try await Translator.explain(item, cfg: cfg, keys: keys)
            return TranslatedDoc(markdown: md, spoken: Translator.spokenFallback(md))
        }
    }

    func listen() {
        touched()
        if !digestMode, let c = current, c.kind == .podcast {
            PodcastWindow.show(select: c.episodeID, autoplay: true)
            dismiss()
            return
        }
        if digestMode { Speaker.shared.toggle(digestItem) } else if let c = current { Speaker.shared.toggle(c) }
    }

    func close() {
        dismissTask?.cancel()
        Speaker.shared.stop()
        queue = []
        digestMode = false
        controller.hide()
    }
}

// MARK: - Window plumbing

final class KeyPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}

@MainActor
final class AlertPanelController {
    private let panel: KeyPanel
    private var dims: [NSWindow] = []
    private let hosting: NSHostingController<AnyView>

    init(center: AlertCenter) {
        hosting = NSHostingController(rootView: AnyView(AlertRootView().environmentObject(center)))
        hosting.sizingOptions = [.preferredContentSize]
        panel = KeyPanel(contentRect: NSRect(x: 0, y: 0, width: 640, height: 320),
                         styleMask: [.borderless, .nonactivatingPanel, .fullSizeContentView],
                         backing: .buffered, defer: false)
        panel.contentViewController = hosting
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.level = .modalPanel
        panel.isMovableByWindowBackground = true
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        panel.animationBehavior = .alertPanel
    }

    var isVisible: Bool { panel.isVisible }

    func show(dim: Bool) {
        let screen = NSScreen.screens.first { NSMouseInRect(NSEvent.mouseLocation, $0.frame, false) } ?? NSScreen.main ?? NSScreen.screens[0]
        if dim { showDim(on: screen) }
        hosting.view.layoutSubtreeIfNeeded()
        let size = hosting.view.fittingSize
        let vf = screen.visibleFrame
        panel.setFrame(NSRect(x: vf.midX - size.width / 2, y: vf.midY - size.height / 2 + vf.height * 0.08,
                              width: size.width, height: size.height), display: true)
        panel.alphaValue = 0
        panel.makeKeyAndOrderFront(nil)
        panel.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.18
            panel.animator().alphaValue = 1
        }
        panel.invalidateShadow()
    }

    func bringToFront() {
        panel.makeKeyAndOrderFront(nil)
        panel.orderFrontRegardless()
    }

    func hide() {
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.15
            panel.animator().alphaValue = 0
            dims.forEach { $0.animator().alphaValue = 0 }
        }, completionHandler: { [weak self] in
            MainActor.assumeIsolated {
                self?.panel.orderOut(nil)
                self?.dims.forEach { $0.orderOut(nil) }
                self?.dims = []
            }
        })
    }

    private func showDim(on screen: NSScreen) {
        guard dims.isEmpty else { return }
        let w = NSWindow(contentRect: screen.frame, styleMask: .borderless, backing: .buffered, defer: false)
        w.isOpaque = false
        w.backgroundColor = NSColor.black.withAlphaComponent(0.32)
        w.level = .floating
        w.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        w.isReleasedWhenClosed = false
        w.alphaValue = 0
        w.setFrame(screen.frame, display: false)
        w.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { $0.duration = 0.25; w.animator().alphaValue = 1 }
        dims = [w]
    }
}

// MARK: - Views

struct VisualEffect: NSViewRepresentable {
    func makeNSView(context: Context) -> NSVisualEffectView {
        let v = NSVisualEffectView()
        v.material = .hudWindow
        v.blendingMode = .behindWindow
        v.state = .active
        return v
    }
    func updateNSView(_ nsView: NSVisualEffectView, context: Context) {}
}

struct AlertRootView: View {
    @EnvironmentObject var center: AlertCenter

    var body: some View {
        Group {
            if center.digestMode {
                DigestView()
            } else if let item = center.current {
                CardView(item: item)
            } else {
                Color.clear.frame(height: 10)
            }
        }
        .frame(width: 640)
        .background(VisualEffect())
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(.white.opacity(0.14)))
        .environment(\.colorScheme, .dark)
        .animation(.snappy(duration: 0.22), value: center.index)
        .animation(.snappy(duration: 0.22), value: center.digestMode)
    }
}

struct KindBadge: View {
    let item: NewsItem
    var body: some View {
        let (label, symbol, color): (String, String, Color) = {
            switch item.kind {
            case .hot: return ("Trending · \(item.hotSources ?? 0) sources", "flame.fill", .orange)
            case .briefing: return ("Briefing", "sun.horizon.fill", .yellow)
            case .digest: return ("Digest", "list.bullet", .blue)
            case .podcast: return ("Podcast ready", "mic.fill", .purple)
            case .news:
                let c = NewsCategory.find(item.category)
                return (c?.label ?? "News", c?.symbol ?? "bolt.fill", .accentColor)
            }
        }()
        Label(label, systemImage: symbol)
            .font(.system(size: 11.5, weight: .semibold))
            .padding(.horizontal, 9).padding(.vertical, 4)
            .background(color.opacity(0.22), in: Capsule())
            .foregroundStyle(color == .accentColor ? .white : color)
    }
}

struct CardView: View {
    let item: NewsItem
    @EnvironmentObject var center: AlertCenter
    @ObservedObject var speaker = Speaker.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                KindBadge(item: item)
                if let r = item.relevance, ConfigStore.shared.c.translation == .deepseek {
                    Text("For you \(r)").font(.system(size: 11, weight: .medium)).foregroundStyle(.secondary)
                }
                Spacer()
                if center.queue.count > 1 {
                    Button { center.prev() } label: { Image(systemName: "chevron.left") }
                        .buttonStyle(.borderless).keyboardShortcut(center.armed ? KeyboardShortcut(.leftArrow, modifiers: []) : nil)
                    Text("\(center.index + 1) of \(center.queue.count)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    Button { center.next() } label: { Image(systemName: "chevron.right") }
                        .buttonStyle(.borderless).keyboardShortcut(center.armed ? KeyboardShortcut(.rightArrow, modifiers: []) : nil)
                }
                Button { center.close() } label: { Image(systemName: "xmark.circle.fill").font(.system(size: 15)) }
                    .buttonStyle(.borderless).foregroundStyle(.secondary).help("Close all")
            }

            VStack(alignment: .leading, spacing: 6) {
                Text(item.title)
                    .font(.system(size: 23, weight: .semibold))
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
                if let o = item.originalTitle, !o.isEmpty, o != item.title, Translator.isLatin(o) {
                    Text("“\(o)”").font(.system(size: 12.5)).italic().foregroundStyle(.secondary).lineLimit(1)
                }
            }

            if !item.summary.isEmpty {
                Text(item.summary)
                    .font(.system(size: 14.5))
                    .foregroundStyle(.primary.opacity(0.88))
                    .fixedSize(horizontal: false, vertical: true)
                    .lineSpacing(2)
                    .textSelection(.enabled)
            }

            if let why = item.why, !why.isEmpty {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "sparkles").foregroundStyle(.yellow)
                    Text(why).fixedSize(horizontal: false, vertical: true)
                }
                .font(.system(size: 12.5))
                .foregroundStyle(.secondary)
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
            }

            HStack(spacing: 6) {
                if item.firstParty == true {
                    Label("Official", systemImage: "checkmark.seal.fill").foregroundStyle(.green)
                }
                Text(item.sourceName)
                if let d = item.publishedAt ?? Optional(item.discoveredAt) {
                    Text("·"); Text(d, format: .relative(presentation: .named))
                }
                if let s = item.score { Text("·"); Text("AIHOT score \(s)") }
                Spacer(minLength: 8)
                ForEach((item.tags ?? []).prefix(3), id: \.self) { t in
                    Text(Tags.en(t))
                        .padding(.horizontal, 7).padding(.vertical, 2)
                        .background(.white.opacity(0.08), in: Capsule())
                }
            }
            .font(.system(size: 11.5)).foregroundStyle(.secondary).lineLimit(1)

            Divider().opacity(0.5)

            HStack(spacing: 8) {
                Button { center.listen() } label: {
                    if speaker.preparingID == item.id {
                        Label("Preparing…", systemImage: "waveform")
                    } else {
                        Label(speaker.playingID == item.id ? "Stop" : "Listen",
                              systemImage: speaker.playingID == item.id ? "stop.fill" : "speaker.wave.2.fill")
                    }
                }
                .keyboardShortcut(center.armed ? KeyboardShortcut(.space, modifiers: []) : nil)

                if item.kind != .briefing && item.kind != .podcast {
                    Button { center.explain() } label: { Label("Explain", systemImage: "brain") }
                        .keyboardShortcut(center.armed ? KeyboardShortcut("e", modifiers: []) : nil)
                }

                Menu {
                    Button("In 15 minutes") { center.later(minutes: 15) }
                    Button("In 1 hour") { center.later(minutes: 60) }
                    Button("In 3 hours") { center.later(minutes: 180) }
                } label: { Label("Later", systemImage: "clock") } primaryAction: { center.later(minutes: 30) }
                .fixedSize()

                Spacer()

                if item.aihotURL != nil && item.kind == .news {
                    Button("AIHOT") { if let u = item.aihotURL { NSWorkspace.shared.open(u) } }
                        .buttonStyle(.borderless).foregroundStyle(.secondary)
                }
                Button("Dismiss") { center.dismiss() }
                    .keyboardShortcut(center.armed ? .cancelAction : nil)
                Button { center.read() } label: {
                    Label(item.kind == .hot ? "Read story" : item.kind == .briefing ? "Read briefing" : item.kind == .podcast ? "Play podcast" : "Read", systemImage: "arrow.up.right")
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(center.armed ? .defaultAction : nil)
            }
            .controlSize(.large)

            Text("⏎ read   esc dismiss   space listen   E explain" + (center.queue.count > 1 ? "   ← → browse" : ""))
                .font(.system(size: 10.5)).foregroundStyle(.tertiary)
                .frame(maxWidth: .infinity, alignment: .center)
        }
        .padding(22)
    }
}

struct DigestView: View {
    @EnvironmentObject var center: AlertCenter
    @ObservedObject var speaker = Speaker.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("\(center.queue.count) new AI stories", systemImage: "bolt.fill")
                    .font(.system(size: 20, weight: .semibold))
                Spacer()
                Button { center.close() } label: { Image(systemName: "xmark.circle.fill").font(.system(size: 15)) }
                    .buttonStyle(.borderless).foregroundStyle(.secondary)
            }
            ScrollView {
                VStack(spacing: 4) {
                    ForEach(Array(center.queue.enumerated()), id: \.element.id) { i, item in
                        Button { center.open(at: i) } label: {
                            HStack(alignment: .top, spacing: 10) {
                                Image(systemName: item.kind == .hot ? "flame.fill" : NewsCategory.find(item.category)?.symbol ?? "bolt.fill")
                                    .frame(width: 18).foregroundStyle(item.kind == .hot ? .orange : .secondary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.title).font(.system(size: 14, weight: .medium)).multilineTextAlignment(.leading)
                                    Text(item.sourceName).font(.system(size: 11)).foregroundStyle(.secondary)
                                }
                                Spacer(minLength: 0)
                                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                            }
                            .padding(8)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 8))
                    }
                }
            }
            .frame(maxHeight: 360)
            .fixedSize(horizontal: false, vertical: true)

            HStack {
                Button { center.listen() } label: {
                    Label(speaker.playingID?.hasPrefix("digest") == true ? "Stop" : "Listen to rundown",
                          systemImage: speaker.playingID?.hasPrefix("digest") == true ? "stop.fill" : "speaker.wave.2.fill")
                }
                .keyboardShortcut(center.armed ? KeyboardShortcut(.space, modifiers: []) : nil)
                Spacer()
                Button("Dismiss all") { center.close() }.keyboardShortcut(center.armed ? .cancelAction : nil)
                Button("Step through") { center.stepThrough() }
                    .buttonStyle(.borderedProminent).keyboardShortcut(center.armed ? .defaultAction : nil)
            }
            .controlSize(.large)
        }
        .padding(22)
    }
}
