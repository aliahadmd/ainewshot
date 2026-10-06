import SwiftUI
import AppKit
import Carbon.HIToolbox

@main
struct AINewsHotApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @ObservedObject private var engine = NewsEngine.shared

    var body: some Scene {
        MenuBarExtra {
            MenuView()
        } label: {
            if engine.pausedUntil != nil {
                Image(systemName: "bolt.slash")
            } else if engine.unread > 0 {
                Image(systemName: "bolt.badge.clock.fill")
            } else {
                Image(systemName: "bolt.fill")
            }
        }
        .menuBarExtraStyle(.window)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        MainActor.assumeIsolated {
            NewsEngine.shared.start()
            HotKey.register(id: 1, keyCode: UInt32(kVK_ANSI_N), modifiers: UInt32(controlKey | optionKey | cmdKey)) {
                let alerted = NewsEngine.shared.recent.filter(\.alerted)
                let items = Array((alerted.isEmpty ? NewsEngine.shared.recent : alerted).prefix(6))
                guard !items.isEmpty else { return }
                AlertCenter.shared.present(items, digest: false, autoplay: false)
            }
            HotKey.register(id: 2, keyCode: UInt32(kVK_ANSI_Period), modifiers: UInt32(controlKey | optionKey | cmdKey)) {
                Speaker.shared.stop()
            }
            // `open -a AINewsHot --args --podcast 1` produces an episode on launch (handy for Shortcuts/cron).
            let args = CommandLine.arguments
            if let i = args.firstIndex(of: "--podcast"), let days = args[safe: i + 1].flatMap(Int.init) {
                PodcastWindow.show(generate: days)
            }
            if SecretsStore.shared.s.deepseekKey.isEmpty && SecretsStore.shared.s.mimoKey.isEmpty {
                SettingsWindow.show()
            }
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        MainActor.assumeIsolated { NewsEngine.shared.save() }
    }
}

/// System-wide hotkeys via Carbon (no Accessibility permission needed).
@MainActor
enum HotKey {
    private static var handlers: [UInt32: () -> Void] = [:]
    private static var refs: [EventHotKeyRef?] = []
    private static var installed = false

    static func register(id: UInt32, keyCode: UInt32, modifiers: UInt32, action: @escaping () -> Void) {
        if !installed {
            installed = true
            var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
            InstallEventHandler(GetApplicationEventTarget(), { _, event, _ in
                var hk = EventHotKeyID()
                GetEventParameter(event, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID),
                                  nil, MemoryLayout<EventHotKeyID>.size, nil, &hk)
                let id = hk.id
                DispatchQueue.main.async { MainActor.assumeIsolated { HotKey.handlers[id]?() } }
                return noErr
            }, 1, &spec, nil, nil)
        }
        var ref: EventHotKeyRef?
        RegisterEventHotKey(keyCode, modifiers, EventHotKeyID(signature: OSType(0x41494E48), id: id),
                            GetApplicationEventTarget(), 0, &ref)
        refs.append(ref)
        handlers[id] = action
    }
}
