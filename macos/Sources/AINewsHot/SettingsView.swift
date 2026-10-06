import SwiftUI
import AppKit
import ServiceManagement
import AVFoundation
import UniformTypeIdentifiers

@MainActor
enum SettingsWindow {
    private static var window: NSWindow?

    static func show() {
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 640, height: 620),
                             styleMask: [.titled, .closable, .miniaturizable], backing: .buffered, defer: false)
            w.title = "AI News Hot Settings"
            w.isReleasedWhenClosed = false
            w.contentViewController = NSHostingController(rootView: SettingsView())
            w.center()
            window = w
        }
        NSApp.activate()
        window?.makeKeyAndOrderFront(nil)
    }
}

struct SettingsView: View {
    var body: some View {
        TabView {
            AlertsTab().tabItem { Label("Alerts", systemImage: "bell.badge") }
            TranslationTab().tabItem { Label("Translation & AI", systemImage: "character.book.closed") }
            AudioTab().tabItem { Label("Audio", systemImage: "speaker.wave.2") }
            PodcastTab().tabItem { Label("Podcast", systemImage: "mic") }
            GeneralTab().tabItem { Label("General", systemImage: "gearshape") }
        }
        .frame(width: 680, height: 680)
    }
}

private func minutesBinding(_ b: Binding<Int>) -> Binding<Date> {
    Binding(
        get: { Calendar.current.date(bySettingHour: b.wrappedValue / 60, minute: b.wrappedValue % 60, second: 0, of: Date()) ?? Date() },
        set: { let c = Calendar.current.dateComponents([.hour, .minute], from: $0); b.wrappedValue = (c.hour ?? 0) * 60 + (c.minute ?? 0) }
    )
}

struct AlertsTab: View {
    @ObservedObject var store = ConfigStore.shared

    var body: some View {
        Form {
            Section("What pops up") {
                Picker("Feed", selection: $store.c.feedMode) {
                    Text("First-hand only — official sources (recommended)").tag("firstParty")
                    Text("All curated (adds media, X posts, newsletters)").tag("selected")
                    Text("Everything (very noisy)").tag("all")
                }
                Text(store.c.feedMode == "firstParty"
                     ? "Same list as aihot.news › 精选 › 只看一手: announcements straight from OpenAI, Anthropic, Google, Hugging Face… ≈8 a day."
                     : store.c.feedMode == "selected" ? "≈15–25 a day." : "Hundreds a day. Use keywords or a high score.")
                    .font(.caption).foregroundStyle(.secondary)
                ForEach(NewsCategory.all) { cat in
                    Toggle(isOn: Binding(
                        get: { store.c.categories.contains(cat.id) },
                        set: { on in if on { store.c.categories.insert(cat.id) } else { store.c.categories.remove(cat.id) } }
                    )) { Label(cat.label, systemImage: cat.symbol) }
                }
                Stepper("Minimum AIHOT score: \(store.c.minScore)", value: $store.c.minScore, in: 0...95, step: 1)
                Text("Curated items score 60–85. 0 = alert on everything in the feed; 70 ≈ the top half.").font(.caption).foregroundStyle(.secondary)
                Stepper("Ignore items older than \(store.c.maxAlertAgeHours) h", value: $store.c.maxAlertAgeHours, in: 1...72)
                TextField("Always alert on keywords", text: $store.c.includeKeywords, prompt: Text("claude, anthropic, deepseek, gpt-6"))
                Text("Keywords also match AIHOT tags: Anthropic, OpenAI, Agent, Coding, Model release, Safety, MCP/Tools…").font(.caption).foregroundStyle(.secondary)
                TextField("Never alert on keywords", text: $store.c.excludeKeywords, prompt: Text("crypto, funding round"))
            }
            Section("AI relevance filter (DeepSeek)") {
                Toggle("Only alert when relevant to me", isOn: $store.c.useAIRelevance)
                Stepper("Minimum relevance: \(store.c.minRelevance)", value: $store.c.minRelevance, in: 0...100, step: 5)
                    .disabled(!store.c.useAIRelevance)
                VStack(alignment: .leading) {
                    Text("About me (used to score relevance)").font(.caption).foregroundStyle(.secondary)
                    TextEditor(text: $store.c.interestProfile).frame(height: 64).font(.callout)
                }
            }
            Section("Trending stories") {
                Toggle("Alert when a story is trending across many sources", isOn: $store.c.hotEnabled)
                Stepper("In the top \(store.c.hotMaxRank) of the hot list", value: $store.c.hotMaxRank, in: 1...10)
                Stepper("Covered by at least \(store.c.hotMinSources) sources", value: $store.c.hotMinSources, in: 2...30)
            }
            Section("Interruptions") {
                Toggle("Dim the screen behind the popup", isOn: $store.c.dimBackground)
                Stepper("At most \(store.c.maxPopupsPerHour) popups per hour", value: $store.c.maxPopupsPerHour, in: 1...30)
                Stepper("Group \(store.c.digestThreshold)+ stories into one digest", value: $store.c.digestThreshold, in: 2...10)
                Stepper(store.c.autoDismissSeconds == 0 ? "Stay until I act" : "Auto-close after \(store.c.autoDismissSeconds)s",
                        value: $store.c.autoDismissSeconds, in: 0...600, step: 15)
                Toggle("Quiet hours (alerts wait, then arrive as a digest)", isOn: $store.c.quietEnabled)
                if store.c.quietEnabled {
                    DatePicker("From", selection: minutesBinding($store.c.quietStart), displayedComponents: .hourAndMinute)
                    DatePicker("To", selection: minutesBinding($store.c.quietEnd), displayedComponents: .hourAndMinute)
                }
            }
        }
        .formStyle(.grouped)
    }
}

struct TranslationTab: View {
    @ObservedObject var store = ConfigStore.shared
    @ObservedObject var secrets = SecretsStore.shared
    @State private var appleStatus = "Checking…"
    @State private var testResult = ""

    var body: some View {
        Form {
            Section("Translate Chinese → English with") {
                Picker("Provider", selection: $store.c.translation) {
                    ForEach(TranslationProvider.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.radioGroup)
                Text("If the chosen provider fails, the app falls back to Apple Translate, then to the source's own English headline.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("DeepSeek") {
                SecureField("API key", text: $secrets.s.deepseekKey)
                Picker("Model", selection: $store.c.deepseekModel) {
                    Text("deepseek-flash (fast, recommended)").tag("deepseek-flash")
                    Text("deepseek-v4-pro (higher quality, slower)").tag("deepseek-v4-pro")
                }
                Button("Test translation") {
                    testResult = "Translating…"
                    Task {
                        let r = await Translator.items([SourceText(id: "t", title: "Anthropic 发布 Claude 新模型，编码能力大幅提升", originalTitle: nil, summary: "新模型在多项编码基准上领先。", reason: nil)],
                                                       cfg: store.c, keys: secrets.s)
                        testResult = r["t"].map { "\($0.title) — \($0.summary)" } ?? "Failed"
                    }
                }
                if !testResult.isEmpty { Text(testResult).font(.callout).textSelection(.enabled) }
            }
            Section("Apple Translate (on-device)") {
                LabeledContent("Status", value: appleStatus)
                Button("Open Translation Languages settings…") {
                    NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.Localization-Settings.extension")!)
                }
                Text("Download “Chinese (Simplified)” under General › Language & Region › Translation Languages to translate offline, free and private.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .task { appleStatus = await Translator.appleAvailability() }
    }
}

struct AudioTab: View {
    @ObservedObject var store = ConfigStore.shared
    @ObservedObject var secrets = SecretsStore.shared

    var body: some View {
        Form {
            Section {
                Toggle("Read alerts aloud", isOn: $store.c.audioEnabled)
                Toggle("Play a chime first", isOn: $store.c.chime)
                Toggle("Stay silent while the microphone is in use (calls, meetings)", isOn: $store.c.holdAudioWhenMicBusy)
                Slider(value: $store.c.volume, in: 0.1...1) { Text("Volume") }
            }
            Section("Voice") {
                Picker("Engine", selection: $store.c.voiceProvider) {
                    ForEach(VoiceProvider.allCases) { Text($0.label).tag($0) }
                }
                SecureField("MiMo API key", text: $secrets.s.mimoKey)
                Picker("MiMo voice", selection: $store.c.voice) {
                    Text("Mia (English, female)").tag("Mia")
                    Text("Chloe (English, female)").tag("Chloe")
                    Text("Milo (English, male)").tag("Milo")
                    Text("Dean (English, male)").tag("Dean")
                }
                VStack(alignment: .leading) {
                    Text("Speaking style (natural-language direction for MiMo)").font(.caption).foregroundStyle(.secondary)
                    TextEditor(text: $store.c.voiceStyle).frame(height: 54).font(.callout)
                }
                Button("Test voice") {
                    var n = NewsItem(id: "test-\(Date().timeIntervalSince1970)", title: "Test", summary: "")
                    n.spoken = "This is AI News Hot. Mistral just released Large 4 in preview, with open weights coming at the end of the month."
                    Speaker.shared.play(n, chime: true)
                }
                Text("MiMo audio is generated in the background before each popup appears, so the voice starts instantly. If MiMo fails, the macOS voice is used.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }
}

struct GeneralTab: View {
    @ObservedObject var store = ConfigStore.shared
    @State private var launchAtLogin = SMAppService.mainApp.status == .enabled
    @State private var loginError = ""

    var body: some View {
        Form {
            Section {
                Toggle("Launch at login", isOn: $launchAtLogin)
                    .onChange(of: launchAtLogin) { _, on in
                        do {
                            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
                            loginError = ""
                        } catch { loginError = error.localizedDescription }
                    }
                if !loginError.isEmpty { Text(loginError).font(.caption).foregroundStyle(.red) }
                Stepper("Check for news every \(Int(store.c.pollSeconds)) s", value: $store.c.pollSeconds, in: 60...900, step: 30)
                Text("AIHOT asks clients to poll at most once a minute; unchanged checks cost a tiny 304.").font(.caption).foregroundStyle(.secondary)
            }
            Section("Briefings") {
                Toggle("Morning briefing (AIHOT daily report, translated and read aloud)", isOn: $store.c.briefingEnabled)
                DatePicker("Deliver at", selection: minutesBinding($store.c.briefingMinutes), displayedComponents: .hourAndMinute)
                Text("AIHOT publishes the daily at 08:00 Beijing time.").font(.caption).foregroundStyle(.secondary)
                Toggle("Weekly briefing on Monday (after 11:00)", isOn: $store.c.briefingWeekly)
            }
            Section("Shortcuts") {
                LabeledContent("Reopen recent alerts", value: "⌃⌥⌘N")
                LabeledContent("Stop speaking", value: "⌃⌥⌘.")
                LabeledContent("In a popup", value: "⏎ read · esc dismiss · space listen · E explain · ←→")
            }
            Section {
                Button("Send a test alert") {
                    let sample = NewsEngine.shared.recent.prefix(1)
                    if sample.isEmpty { return }
                    AlertCenter.shared.present(Array(sample), digest: false, autoplay: true)
                }
                Button("Show data folder") { NSWorkspace.shared.activateFileViewerSelecting([Paths.support]) }
            }
        }
        .formStyle(.grouped)
    }
}

struct PodcastTab: View {
    @ObservedObject var store = ConfigStore.shared
    @ObservedObject var secrets = SecretsStore.shared
    @State private var customVoices = VoiceLibrary.list()
    @State private var designName = "Nova"
    @State private var designPrompt = "A woman in her late twenties with a smooth, clear, slightly husky voice; confident late-night radio host; relaxed, warm pace."
    @State private var voiceStatus = ""
    @State private var previewing = false

    var body: some View {
        Form {
            Section("Show") {
                Picker("Format", selection: $store.c.podFormat) {
                    Text("Two hosts (conversation)").tag("duo"); Text("One host (narrated)").tag("solo")
                }
                Picker("Length", selection: $store.c.podLengthMin) {
                    Text("Auto (1 day ≈ 8 min · 3 days ≈ 12 · 7 days ≈ 18)").tag(0)
                    ForEach([5, 10, 15, 20, 30], id: \.self) { Text("\($0) minutes").tag($0) }
                }
                Picker("Coverage", selection: $store.c.podCoverage) {
                    Text("All news — AI merges duplicates, drops only noise").tag("all")
                    Text("Curated (AIHOT selected)").tag("selected")
                    Text("First-hand only (official sources)").tag("firstParty")
                }
                Picker("Tone (scene)", selection: $store.c.podTone) {
                    ForEach(PodcastStyle.tones, id: \.id) { Text($0.label).tag($0.id) }
                }
                Picker("Emotion & audio tags", selection: $store.c.podExpressiveness) {
                    Text("Subtle — rare tags, understated").tag(1)
                    Text("Natural — reactions where a real host would").tag(2)
                    Text("Expressive — vivid emotions, laughs, sighs, breaths").tag(3)
                }
                Toggle("Sung intro jingle (MiMo singing mode)", isOn: $store.c.podJingle)
                Picker("Script writer", selection: $store.c.podWriterModel) {
                    Text("DeepSeek V4 Pro (best writing)").tag("deepseek-v4-pro")
                    Text("DeepSeek Flash (faster)").tag("deepseek-flash")
                }
            }
            hostSection("Host A (lead)", name: $store.c.podHostAName, voice: $store.c.podHostAVoice, persona: $store.c.podHostAPersona)
            if store.c.podFormat == "duo" {
                hostSection("Host B (co-host)", name: $store.c.podHostBName, voice: $store.c.podHostBVoice, persona: $store.c.podHostBPersona)
            }
            Section("Custom voices") {
                Text("Design: MiMo VoiceDesign invents a voice from your description. It's rendered once and then reused via VoiceClone, so the voice stays identical for every line.")
                    .font(.caption).foregroundStyle(.secondary)
                TextField("Voice name", text: $designName)
                TextEditor(text: $designPrompt).frame(height: 48).font(.callout)
                HStack {
                    Button("Design voice") {
                        voiceStatus = "Designing…"
                        Task {
                            do {
                                let f = try await VoiceLibrary.design(name: designName, description: designPrompt, key: secrets.s.mimoKey)
                                customVoices = VoiceLibrary.list()
                                voiceStatus = "Created \(f). Pick it as a host voice above."
                            } catch { voiceStatus = error.localizedDescription }
                        }
                    }
                    Button("Clone from audio file…") {
                        let panel = NSOpenPanel()
                        panel.allowedContentTypes = [.wav, .mp3]
                        if panel.runModal() == .OK, let url = panel.url {
                            do { let f = try VoiceLibrary.importFile(url); customVoices = VoiceLibrary.list(); voiceStatus = "Imported \(f) (10–30 s of clean speech works best)." }
                            catch { voiceStatus = error.localizedDescription }
                        }
                    }
                    Button("Show folder") { NSWorkspace.shared.open(VoiceLibrary.dir) }
                }
                if !voiceStatus.isEmpty { Text(voiceStatus).font(.caption).textSelection(.enabled) }
            }
            Section("Schedule & files") {
                Toggle("Prepare a morning episode automatically", isOn: $store.c.podAuto)
                DatePicker("Ready by", selection: minutesBinding($store.c.podAutoMinutes), displayedComponents: .hourAndMinute)
                    .disabled(!store.c.podAuto)
                Toggle("On Mondays, make it a 7-day recap", isOn: $store.c.podAutoWeeklyOnMonday).disabled(!store.c.podAuto)
                Text("When it's ready you get a popup (it waits until you unlock your Mac).").font(.caption).foregroundStyle(.secondary)
                Toggle("Also save episodes to Music › AI News Hot", isOn: $store.c.podSaveToMusic)
            }
        }
        .formStyle(.grouped)
    }

    private func hostSection(_ title: String, name: Binding<String>, voice: Binding<String>, persona: Binding<String>) -> some View {
        Section(title) {
            TextField("Name", text: name)
            Picker("Voice", selection: voice) {
                ForEach(PodcastStyle.builtInVoices, id: \.id) { Text($0.label).tag($0.id) }
                if !customVoices.isEmpty {
                    Divider()
                    ForEach(customVoices, id: \.self) { Text(VoiceLibrary.label("file:" + $0)).tag("file:" + $0) }
                }
            }
            VStack(alignment: .leading) {
                Text("Persona — sent to MiMo as the [Character] in director mode, and to the script writer").font(.caption).foregroundStyle(.secondary)
                TextEditor(text: persona).frame(height: 54).font(.callout)
            }
            Button(previewing ? "Previewing…" : "Preview voice") {
                previewing = true
                let cfg = store.c
                let st = "[Character] \(name.wrappedValue). \(persona.wrappedValue)\n[Scene] Recording \"\(PodcastStyle.show)\". \(PodcastStyle.scene(cfg.podTone))\n[Guidance] Warm welcome, smiling. \(PodcastStyle.ttsRule(cfg.podExpressiveness))"
                let text = "(cheerful) Good morning! I'm \(name.wrappedValue), and this is \(PodcastStyle.show). [chuckles] Big day in A I, so let's get into it."
                Task {
                    defer { previewing = false }
                    do {
                        let pcm = try await MiMoTTS.pcm(text: text, style: st, voice: voice.wrappedValue, key: secrets.s.mimoKey)
                        let url = Paths.audio.appendingPathComponent("preview.wav")
                        try MiMoTTS.wav(pcm: pcm, sampleRate: MiMoTTS.sampleRate).write(to: url)
                        PreviewPlayer.play(url)
                    } catch { voiceStatus = error.localizedDescription }
                }
            }
            .disabled(previewing)
        }
    }
}

@MainActor
enum PreviewPlayer {
    private static var player: AVAudioPlayer?
    static func play(_ url: URL) {
        player = try? AVAudioPlayer(contentsOf: url)
        player?.play()
    }
}
