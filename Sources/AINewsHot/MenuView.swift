import SwiftUI
import AppKit

struct MenuView: View {
    @ObservedObject var engine = NewsEngine.shared
    @ObservedObject var store = ConfigStore.shared
    @State private var filter = 0   // 0 all, 1 alerted

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "bolt.fill").foregroundStyle(.yellow)
                Text("AI News Hot").font(.headline)
                Spacer()
                if engine.isChecking { ProgressView().controlSize(.mini) }
                Button { engine.checkNow() } label: { Image(systemName: "arrow.clockwise") }
                    .buttonStyle(.borderless).help("Check now")
            }
            statusLine
            if let note = engine.feedNote {
                Label(note, systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.orange)
            }

            if let p = engine.pausedUntil {
                HStack {
                    Image(systemName: "pause.circle.fill").foregroundStyle(.orange)
                    Text("Alerts paused until \(p.formatted(date: .omitted, time: .shortened))")
                    Spacer()
                    Button("Resume") { engine.resume() }
                }
                .font(.callout).padding(8).background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
            }

            Picker("", selection: $filter) {
                Text("Latest").tag(0)
                Text("Alerted").tag(1)
            }
            .pickerStyle(.segmented).labelsHidden()

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    let rows = engine.recent.filter { filter == 0 || $0.alerted }.prefix(40)
                    if rows.isEmpty {
                        Text(engine.lastCheck == nil ? "Loading the latest AI news…" : "Nothing here yet.")
                            .foregroundStyle(.secondary).padding()
                    }
                    ForEach(Array(rows)) { item in
                        RecentRow(item: item)
                    }
                }
            }
            .frame(height: 380)

            Divider()
            podcastRow
            HStack(spacing: 6) {
                docButton("Daily", "sun.horizon", path: "api/v1/agent/daily", link: "https://aihot.news/daily")
                docButton("Trending", "flame", path: "api/v1/agent/hot", link: "https://aihot.news/hot")
                docButton("Weekly", "calendar", path: "api/v1/agent/weekly", link: "https://aihot.news/weekly")
                docButton("Monthly", "calendar.badge.clock", path: "api/v1/agent/monthly", link: "https://aihot.news/monthly")
            }
            HStack {
                Menu {
                    Button("1 hour") { engine.pause(minutes: 60) }
                    Button("3 hours") { engine.pause(minutes: 180) }
                    Button("Until tomorrow 8:00") { engine.pause(minutes: nil) }
                    if engine.pausedUntil != nil { Divider(); Button("Resume now") { engine.resume() } }
                } label: { Label("Pause", systemImage: "pause.circle") }
                .fixedSize()
                Spacer()
                Button { SettingsWindow.show() } label: { Label("Settings", systemImage: "gearshape") }
                Button { NSApp.terminate(nil) } label: { Label("Quit", systemImage: "power") }
            }
            .buttonStyle(.borderless)
        }
        .padding(14)
        .frame(width: 420)
        .onAppear { engine.unread = 0 }
    }

    @ObservedObject private var studio = PodcastStudio.shared

    private var podcastRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label("Podcast briefing", systemImage: "mic.fill").font(.subheadline.weight(.semibold))
                Spacer()
                Button("Library") { PodcastWindow.show() }.buttonStyle(.link).font(.caption)
            }
            if studio.isBusy {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("\(studio.stage.label) — \(studio.detail)").font(.caption).lineLimit(1)
                    Spacer()
                    Button("Show") { PodcastWindow.show() }.font(.caption)
                }
            } else {
                HStack(spacing: 6) {
                    ForEach([(1, "1 day"), (3, "3 days"), (7, "7 days")], id: \.0) { days, label in
                        Button {
                            PodcastWindow.show(generate: days)
                        } label: {
                            VStack(spacing: 2) { Image(systemName: "waveform"); Text(label).font(.caption) }.frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(.purple)
                    }
                }
                Text("\(store.c.podFormat == "duo" ? "\(store.c.podHostAName) & \(store.c.podHostBName)" : store.c.podHostAName) · \(store.c.podLengthMin == 0 ? "auto length" : "\(store.c.podLengthMin) min") · \(["all": "all news", "selected": "curated", "firstParty": "first-hand"][store.c.podCoverage] ?? "")")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    private var statusLine: some View {
        HStack(spacing: 4) {
            Circle().fill(engine.lastError == nil ? .green : .orange).frame(width: 7, height: 7)
            if let e = engine.lastError {
                Text(e).lineLimit(1)
            } else if let d = engine.lastCheck {
                Text("Checked \(d.formatted(.relative(presentation: .named)))")
            } else {
                Text("Connecting to aihot.news…")
            }
            if engine.pendingCount > 0 {
                Text("· \(engine.pendingCount) held (\(engine.holdReason ?? "rate limit"))")
            }
        }
        .font(.caption).foregroundStyle(.secondary)
    }

    private func docButton(_ title: String, _ symbol: String, path: String, link: String) -> some View {
        Button {
            let cfg = store.c, keys = SecretsStore.shared.s
            ReaderController.open(title: "AIHOT \(title)", link: URL(string: link)) {
                let md = try await AIHotClient.shared.markdown(path)
                return await Translator.document(md, purpose: "\(title.lowercased()) report", cfg: cfg, keys: keys)
            }
        } label: {
            VStack(spacing: 3) { Image(systemName: symbol); Text(title).font(.caption) }
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
    }
}

struct RecentRow: View {
    let item: NewsItem
    @State private var hover = false

    var body: some View {
        Button { AlertCenter.shared.focus(item) } label: {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: item.kind == .hot ? "flame.fill" : NewsCategory.find(item.category)?.symbol ?? "bolt")
                    .foregroundStyle(item.kind == .hot ? .orange : item.alerted ? .yellow : .secondary)
                    .frame(width: 16)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.title).font(.system(size: 12.5, weight: item.alerted ? .semibold : .regular))
                        .lineLimit(2).multilineTextAlignment(.leading)
                    HStack(spacing: 4) {
                        if item.firstParty == true { Image(systemName: "checkmark.seal.fill").foregroundStyle(.green) }
                        Text(item.sourceName)
                        Text("·")
                        Text(item.discoveredAt, format: .relative(presentation: .named))
                        if let s = item.score { Text("· \(s)") }
                    }
                    .font(.system(size: 10.5)).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(6)
            .contentShape(Rectangle())
            .background(hover ? Color.primary.opacity(0.07) : .clear, in: RoundedRectangle(cornerRadius: 6))
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}
