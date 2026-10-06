import SwiftUI
import AppKit
import AVFoundation
import MediaPlayer

// MARK: - Player (AVAudioPlayer + Now Playing / media keys / AirPods)

@MainActor
final class PodcastPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    static let shared = PodcastPlayer()

    @Published private(set) var episode: Episode?
    @Published private(set) var isPlaying = false
    @Published var time: Double = 0
    @Published private(set) var rate: Float = Float(ConfigStore.shared.c.podSpeed)

    private var player: AVAudioPlayer?
    private var timer: Timer?
    private var remoteReady = false

    var duration: Double { player?.duration ?? episode?.duration ?? 0 }
    var currentLine: Int? { episode?.lines.lastIndex { $0.start <= time + 0.05 } }
    var currentChapter: Int? { episode?.chapters.lastIndex { $0.start <= time + 0.05 } }

    func load(_ ep: Episode) {
        guard episode?.id != ep.id else { return }
        unload()
        guard let p = try? AVAudioPlayer(contentsOf: ep.audioURL) else { return }
        p.enableRate = true
        p.rate = rate
        p.delegate = self
        p.prepareToPlay()
        player = p
        episode = ep
        time = 0
        setupRemote()
        updateNowPlaying()
    }

    func play(_ ep: Episode? = nil) {
        if let ep { load(ep) }
        guard let player else { return }
        Speaker.shared.stop()
        player.play()
        isPlaying = true
        startTimer()
        updateNowPlaying()
    }

    func pause() {
        player?.pause()
        isPlaying = false
        updateNowPlaying()
    }

    func toggle() { isPlaying ? pause() : play() }

    func seek(_ t: Double) {
        guard let player else { return }
        player.currentTime = max(0, min(t, player.duration - 0.1))
        time = player.currentTime
        updateNowPlaying()
    }

    func skip(_ d: Double) { seek((player?.currentTime ?? 0) + d) }

    func setRate(_ r: Float) {
        rate = r
        player?.rate = r
        ConfigStore.shared.c.podSpeed = Double(r)
        updateNowPlaying()
    }

    func unload() {
        player?.stop()
        player = nil
        episode = nil
        isPlaying = false
        timer?.invalidate()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        MPNowPlayingInfoCenter.default().playbackState = .stopped
    }

    private func startTimer() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let p = self.player else { return }
                self.time = p.currentTime
                if !p.isPlaying && self.isPlaying { self.isPlaying = false; self.updateNowPlaying() }
            }
        }
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.isPlaying = false; self.time = 0; self.updateNowPlaying() }
    }

    private func updateNowPlaying() {
        guard let ep = episode else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: ep.title,
            MPMediaItemPropertyArtist: PodcastStyle.show,
            MPMediaItemPropertyAlbumTitle: ep.periodLabel,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: player?.currentTime ?? 0,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(rate) : 0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let c = currentChapter { info[MPNowPlayingInfoPropertyChapterNumber] = c; info[MPNowPlayingInfoPropertyChapterCount] = ep.chapters.count }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
    }

    private func setupRemote() {
        guard !remoteReady else { return }
        remoteReady = true
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { _ in MainActor.assumeIsolated { PodcastPlayer.shared.play() }; return .success }
        c.pauseCommand.addTarget { _ in MainActor.assumeIsolated { PodcastPlayer.shared.pause() }; return .success }
        c.togglePlayPauseCommand.addTarget { _ in MainActor.assumeIsolated { PodcastPlayer.shared.toggle() }; return .success }
        c.skipForwardCommand.preferredIntervals = [30]
        c.skipForwardCommand.addTarget { _ in MainActor.assumeIsolated { PodcastPlayer.shared.skip(30) }; return .success }
        c.skipBackwardCommand.preferredIntervals = [15]
        c.skipBackwardCommand.addTarget { _ in MainActor.assumeIsolated { PodcastPlayer.shared.skip(-15) }; return .success }
        c.nextTrackCommand.addTarget { _ in MainActor.assumeIsolated { PodcastPlayer.shared.chapter(+1) }; return .success }
        c.previousTrackCommand.addTarget { _ in MainActor.assumeIsolated { PodcastPlayer.shared.chapter(-1) }; return .success }
        c.changePlaybackPositionCommand.addTarget { e in
            guard let e = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            MainActor.assumeIsolated { PodcastPlayer.shared.seek(e.positionTime) }
            return .success
        }
    }

    func chapter(_ delta: Int) {
        guard let ep = episode else { return }
        let cur = currentChapter ?? 0
        let target = min(max(0, (delta < 0 && time - (ep.chapters[safe: cur]?.start ?? 0) > 3) ? cur : cur + delta), ep.chapters.count - 1)
        if let c = ep.chapters[safe: target] { seek(c.start) }
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}

// MARK: - Window

@MainActor
enum PodcastWindow {
    private static var window: NSWindow?

    static func show(generate days: Int? = nil, select id: String? = nil, autoplay: Bool = false) {
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1040, height: 720),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                             backing: .buffered, defer: false)
            w.title = "AI News Hot Podcast"
            w.titlebarAppearsTransparent = true
            w.isReleasedWhenClosed = false
            w.contentViewController = NSHostingController(rootView: PodcastView())
            w.setContentSize(NSSize(width: 1040, height: 720))
            w.center()
            w.setFrameAutosaveName("PodcastWindow")
            window = w
        }
        NSApp.activate()
        window?.makeKeyAndOrderFront(nil)
        let studio = PodcastStudio.shared
        if let id { studio.selectedID = id }
        if let days { studio.generate(days: days) }
        if autoplay, let ep = studio.selected { PodcastPlayer.shared.play(ep) }
    }
}

// MARK: - Views

struct PodcastView: View {
    @ObservedObject var studio = PodcastStudio.shared
    @ObservedObject var store = ConfigStore.shared

    var body: some View {
        HStack(spacing: 0) {
            sidebar.frame(width: 270)
            Divider()
            Group {
                if studio.isBusy || studio.stage == .failed {
                    GenerationView()
                } else if let ep = studio.selected {
                    EpisodeView(episode: ep)
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: "mic.and.signal.meter").font(.system(size: 44)).foregroundStyle(.secondary)
                        Text("Pick a period on the left to produce your first episode.").foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(minWidth: 880, minHeight: 600)
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("New episode").font(.headline).padding(.top, 36)
            VStack(spacing: 8) {
                periodButton(1, "Yesterday", "Last 24 hours", "sun.horizon")
                periodButton(3, "Last 3 days", "Catch up after a break", "calendar")
                periodButton(7, "Last 7 days", "Weekly deep recap", "calendar.badge.clock")
            }
            .disabled(studio.isBusy)

            VStack(alignment: .leading, spacing: 6) {
                Picker("Hosts", selection: $store.c.podFormat) {
                    Text("Two hosts").tag("duo"); Text("One host").tag("solo")
                }
                Picker("Length", selection: $store.c.podLengthMin) {
                    Text("Auto").tag(0); ForEach([5, 10, 15, 20, 30], id: \.self) { Text("\($0) min").tag($0) }
                }
                Picker("Coverage", selection: $store.c.podCoverage) {
                    Text("All news (AI-curated)").tag("all"); Text("Curated").tag("selected"); Text("First-hand").tag("firstParty")
                }
                Picker("Tone", selection: $store.c.podTone) {
                    ForEach(PodcastStyle.tones, id: \.id) { Text($0.label).tag($0.id) }
                }
                Picker("Emotion", selection: $store.c.podExpressiveness) {
                    Text("Subtle").tag(1); Text("Natural").tag(2); Text("Expressive").tag(3)
                }
                Button("Voices & more…") { SettingsWindow.show() }.buttonStyle(.link)
            }
            .controlSize(.small)
            .disabled(studio.isBusy)

            Divider()
            Text("Library").font(.headline)
            List(selection: $studio.selectedID) {
                ForEach(studio.episodes) { ep in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(ep.title).font(.system(size: 12.5, weight: .medium)).lineLimit(2)
                        Text("\(ep.createdAt.formatted(date: .abbreviated, time: .shortened)) · \(ep.periodLabel) · \(PodcastStudio.mmss(ep.duration))")
                            .font(.system(size: 10.5)).foregroundStyle(.secondary)
                    }
                    .tag(ep.id)
                    .contextMenu {
                        Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([ep.audioURL]) }
                        Button("Delete", role: .destructive) { studio.delete(ep) }
                    }
                }
            }
            .listStyle(.sidebar)
            .scrollContentBackground(.hidden)
        }
        .padding(.horizontal, 14)
        .padding(.bottom, 12)
        .background(.background.secondary)
    }

    private func periodButton(_ days: Int, _ title: String, _ subtitle: String, _ symbol: String) -> some View {
        Button { studio.generate(days: days) } label: {
            HStack(spacing: 10) {
                Image(systemName: symbol).font(.title3).frame(width: 26)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.system(size: 13, weight: .semibold))
                    Text(subtitle).font(.system(size: 11)).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "waveform").foregroundStyle(.secondary)
            }
            .padding(10)
            .frame(maxWidth: .infinity)
            .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct GenerationView: View {
    @ObservedObject var studio = PodcastStudio.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(studio.stage == .failed ? "Something went wrong" : "Producing your \(studio.generatingDays == 1 ? "daily" : "\(studio.generatingDays)-day") episode")
                .font(.title2.weight(.semibold))
            VStack(alignment: .leading, spacing: 10) {
                ForEach([PodcastStudio.Stage.fetching, .editing, .writing, .recording, .mixing], id: \.rawValue) { s in
                    HStack(spacing: 10) {
                        Group {
                            if studio.stage.rawValue > s.rawValue && studio.stage != .failed { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                            else if studio.stage == s { ProgressView().controlSize(.small) }
                            else { Image(systemName: "circle").foregroundStyle(.tertiary) }
                        }
                        .frame(width: 20)
                        Text(s.label).foregroundStyle(studio.stage == s ? .primary : .secondary)
                    }
                }
            }
            if studio.stage == .recording || studio.stage == .editing {
                ProgressView(value: studio.progress).frame(maxWidth: 420)
            }
            Text(studio.detail).font(.callout).foregroundStyle(.secondary)
            if let e = studio.error { Text(e).foregroundStyle(.red).textSelection(.enabled) }
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(studio.log.suffix(8).enumerated()), id: \.offset) { _, l in
                    Text(l).font(.system(size: 11, design: .monospaced)).foregroundStyle(.secondary)
                }
            }
            HStack {
                if studio.isBusy { Button("Cancel") { studio.cancel() } }
                if studio.stage == .failed, !studio.episodes.isEmpty {
                    Button("Back to library") { studio.selectedID = studio.episodes.first?.id; PodcastStudio.shared.objectWillChange.send() }
                }
            }
            Text("Typical time: 2–4 min for a day, 4–7 min for a week. You can close this window; generation continues.")
                .font(.caption).foregroundStyle(.tertiary)
            Spacer()
        }
        .padding(32)
        .padding(.top, 20)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct EpisodeView: View {
    let episode: Episode
    @ObservedObject var player = PodcastPlayer.shared
    @State private var tab = 0

    private var isLoaded: Bool { player.episode?.id == episode.id }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 6) {
                Text(episode.title).font(.system(size: 24, weight: .bold))
                Text(episode.summary).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                Text("\(episode.periodLabel) · \(PodcastStudio.mmss(episode.duration)) · \(episode.notes.count) stories from \(episode.itemsScanned) news items · " +
                     Set(episode.lines.map(\.speaker)).subtracting(["J"]).sorted().map { episode.name($0) }.joined(separator: " & "))
                    .font(.caption).foregroundStyle(.secondary)
            }
            .padding(.top, 30)

            playerBar

            Picker("", selection: $tab) {
                Text("Transcript").tag(0); Text("Chapters").tag(1); Text("Show notes (\(episode.notes.count))").tag(2)
            }
            .pickerStyle(.segmented).labelsHidden().frame(maxWidth: 420)

            switch tab {
            case 1: chapters
            case 2: notes
            default: transcript
            }
        }
        .padding(.horizontal, 26)
        .padding(.bottom, 16)
        .onAppear { if player.episode == nil { player.load(episode) } }
    }

    private var playerBar: some View {
        let t = isLoaded ? player.time : 0
        return HStack(spacing: 14) {
            Button { player.skip(-15) } label: { Image(systemName: "gobackward.15").font(.title2) }
                .buttonStyle(.borderless).disabled(!isLoaded)
            Button { isLoaded ? player.toggle() : player.play(episode) } label: {
                Image(systemName: isLoaded && player.isPlaying ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 42))
            }
            .buttonStyle(.borderless)
            .keyboardShortcut(.space, modifiers: [])
            Button { player.skip(30) } label: { Image(systemName: "goforward.30").font(.title2) }
                .buttonStyle(.borderless).disabled(!isLoaded)
            Text(PodcastStudio.mmss(t)).font(.caption.monospacedDigit())
            Slider(value: Binding(get: { t }, set: { v in if !isLoaded { player.load(episode) }; player.seek(v) }),
                   in: 0...max(1, episode.duration))
            Text(PodcastStudio.mmss(episode.duration)).font(.caption.monospacedDigit())
            Menu("\(String(format: "%g", player.rate))×") {
                ForEach([0.8, 1.0, 1.15, 1.25, 1.5, 1.75, 2.0], id: \.self) { r in
                    Button("\(String(format: "%g", r))×") { player.setRate(Float(r)) }
                }
            }
            .fixedSize()
            Menu {
                Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([episode.audioURL]) }
                Button("Copy transcript") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(episode.lines.map { "\(episode.name($0.speaker)): \($0.text)" }.joined(separator: "\n\n"), forType: .string)
                }
                Button("Open in Music / default player") { NSWorkspace.shared.open(episode.audioURL) }
            } label: { Image(systemName: "ellipsis.circle") }
            .fixedSize()
        }
        .padding(12)
        .background(.background.secondary, in: RoundedRectangle(cornerRadius: 12))
    }

    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    ForEach(episode.lines) { l in
                        let active = isLoaded && player.currentLine == l.id
                        if l.id == 0 || episode.lines[safe: l.id - 1]?.chapter != l.chapter,
                           let c = episode.chapters[safe: l.chapter] {
                            Text(c.title.uppercased()).font(.caption.weight(.bold)).foregroundStyle(.secondary).padding(.top, 8)
                        }
                        HStack(alignment: .top, spacing: 10) {
                            Text(episode.name(l.speaker))
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(l.speaker == "B" ? Color.orange : Color.accentColor)
                                .frame(width: 64, alignment: .trailing)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(l.text).font(.system(size: 14)).fixedSize(horizontal: false, vertical: true)
                                if !l.direction.isEmpty {
                                    Text(l.direction).font(.system(size: 10.5)).italic().foregroundStyle(.tertiary)
                                }
                            }
                        }
                        .padding(8)
                        .background(active ? Color.accentColor.opacity(0.14) : .clear, in: RoundedRectangle(cornerRadius: 8))
                        .contentShape(Rectangle())
                        .onTapGesture { if !isLoaded { player.load(episode) }; player.seek(l.start); player.play() }
                        .id(l.id)
                    }
                }
                .padding(.vertical, 6)
            }
            .onChange(of: player.currentLine) { _, line in
                guard isLoaded, player.isPlaying, let line else { return }
                withAnimation(.easeInOut(duration: 0.3)) { proxy.scrollTo(line, anchor: .center) }
            }
        }
    }

    private var chapters: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(episode.chapters) { c in
                    VStack(alignment: .leading, spacing: 4) {
                        Button {
                            if !isLoaded { player.load(episode) }
                            player.seek(c.start); player.play()
                        } label: {
                            HStack {
                                Text(PodcastStudio.mmss(c.start)).font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(width: 48, alignment: .leading)
                                Text(c.title).font(.system(size: 14, weight: isLoaded && player.currentChapter == c.id ? .bold : .medium))
                                Spacer()
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        ForEach(c.stories, id: \.self) { n in
                            if let note = episode.notes[safe: n] { noteLinks(note).padding(.leading, 48) }
                        }
                    }
                    .padding(8)
                    .background(.background.secondary, in: RoundedRectangle(cornerRadius: 8))
                }
            }
        }
    }

    private var notes: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(["deep", "brief", "quick", "notes"], id: \.self) { tier in
                    let group = episode.notes.filter { $0.tier == tier }
                    if !group.isEmpty {
                        Text(["deep": "Deep dives", "brief": "Briefs", "quick": "Quick hits", "notes": "Also happened (not read on air)"][tier]!)
                            .font(.headline).padding(.top, 6)
                        ForEach(group) { n in
                            VStack(alignment: .leading, spacing: 3) {
                                Text(n.title).font(.system(size: 13, weight: .medium))
                                noteLinks(n)
                            }
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .textSelection(.enabled)
        }
    }

    private func noteLinks(_ n: ShowNote) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            ForEach(n.links.prefix(3)) { l in
                if let u = URL(string: l.url) {
                    Link(destination: u) {
                        Text("\(l.source): \(l.title)").font(.system(size: 11)).lineLimit(1)
                    }
                }
            }
        }
    }
}
