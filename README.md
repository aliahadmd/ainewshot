# AI News Hot

An Android app that keeps you up to date on AI. It watches [aihot.news](https://aihot.news/agent) live, translates everything to English, and pops important news up in front of whatever you're doing, reading it aloud with a natural voice. It can also produce an AI podcast covering the last day, three days or week of AI news.

> The original macOS menu-bar app is **deprecated**. It lives in [`macos/`](macos/README.md), and its last release is v1.0.0.

## Install

Requires Android 10 or later. It's tested on a Redmi K80 Pro running HyperOS 4 / Android 17.

1. On the phone, open the latest release on the repo's [Releases page](https://github.com/aliahadmd/ainewshot/releases) and download `AINewsHot-<version>.apk`. The repo is private, so sign in to GitHub in the phone's browser first, or copy the APK over from a computer.
2. Open the APK and allow your browser or file manager to install unknown apps. HyperOS may run a security scan first; choose **Install anyway**.
3. Open **AI News Hot**, allow notifications, then go to **Me › Reliability setup**. Tap **Fix** on every row until it shows ✓, then use **Send test alert in 20 s** and lock the phone to confirm the alert wakes the screen and speaks.

The APK includes default DeepSeek and MiMo API keys, so it works right away. You can change them in **Me › Settings › API keys**.

To update, install a newer APK over the old one; your settings and episodes are kept.

## HyperOS reliability (verified on HyperOS 4 / Android 17)

HyperOS adds its own per-app switches on top of Android's, and turns several of them off for new apps. **Reliability setup** reads what it can and deep-links to the exact screen for the rest:

| Switch | How the app knows | Where it lives |
|---|---|---|
| Display over other apps | Android API | Settings › Apps › Special access |
| Full-screen alerts | Android API | Special access |
| Battery saver → **No restrictions** | Same as Android's battery-optimisation exemption on HyperOS 4 | App info › Power |
| Pop-up windows while in background | Xiaomi app-op 10021 `BACKGROUND_START_ACTIVITY`, read via `AppOpsManager` | App info › Other permissions |
| Show on lock screen | Xiaomi app-op 10020 `SHOW_WHEN_LOCKED` | Other permissions |
| Autostart | Xiaomi app-op 10008 `AUTO_START` | `miui.intent.action.OP_AUTO_START` |
| Exact wake-ups | `AlarmManager.canScheduleExactAlarms()` | Alarms & reminders |
| Floating notifications | Not readable by apps; you confirm it | `miui.settings.FLOAT_NOTIFICATION_SETTINGS` |
| Lock-screen notifications | You confirm it | `miui.settings.LOCK_SCREEN_NOTIFICATION_SETTINGS` |
| AI notification sorting (folding) | You confirm it | `miui.settings.AI_NOTIFICATION_SETTINGS` |
| Super Island (focus notifications) | `content://miui.statusbar.notification.public` → `canShowFocus` (whitelisted by Xiaomi) | `miui.settings.FOCUS_NOTIFICATION_SETTINGS` |

The app-op numbers were decoded from the phone's own `miui-framework.jar` (`com.miui.internal.os.MiuiHooks`), and the notification switches from MiuiSystemUI's `NotificationSettingsManager`.

**Staying alive:**
- A foreground service checks every 90 s while the CPU is awake.
- An allow-while-idle alarm wakes the phone every ~4 min with a short wakelock, because a sleeping CPU freezes ordinary timers. In deep Doze, Android spaces these to about 10–15 min.
- WorkManager runs a 15-min backup check, and live alerts restart after a reboot or update.
- A watchdog records any period of 30+ min in which no check ran at all (failed checks, for example with no network, don't count), since that usually means HyperOS froze or killed the app. If you swipe the app away in Recents, it restarts itself within a second.

## What it does

| Feature | How |
|---|---|
| **Same layout as aihot.news** | Five tabs that mirror the site's phone version: **Selected · Hot · Daily · Podcast · Me** (Podcast takes the place of the model ranking, which opens on the site). |
| **Selected** | Both of the site's feeds, item for item: **Selected** (精选: AIHOT's curated picks, ~10–25 a day, with the **Current hot topics** panel on top) and **All** (全部动态: everything AIHOT collected, ~360 a day, from the site's own /all data). Both have the site's single-choice chips: **All · First-hand · Models · Products · Industry · Papers · Tutorials · Opinion** (plus **★ Following** for your topics). "First-hand" differs by feed, as on the site: Selected › First-hand is the ~10 curated official posts a day (your alerts); All › First-hand is every official post. Stories are grouped by day with the site's counts ("Today · Oct 7 Wed · 11 items"). Cards follow the site: source and time, **● Selected** badge, **AI score** (70+ highlighted), bookmark, title and summary (X posts show the post itself), thumbnail, **quoted post** in its original language, the green **why it matters** line or, when AIHOT gave none, the category and tags, and an **"N reports"** expander listing every outlet that covered it. Stories you've opened are dimmed, and your last feed and chip are remembered. |
| **Full articles** | Every article page shows the **full text**, translated to English as you open it (in parallel pieces, so the first part appears in a few seconds), with an **English / 中文** switch, a **Contents** list, the AI summary, why it matters, topic tags and the story it belongs to. The bottom bar is the site's reading toolbar: **Save · Contents · Listen · Share · Original**. The full text appears only when AIHOT shows it on its own site (`readingMode: full`); otherwise you get the summary and a link to the original. Translations are cached for offline reading. |
| **Hot** | The 48-hour top 10 from aihot.news/hot with the **heat index**, **trend** (↑/↓ %), a sparkline, the AI summary, the latest development, and how many sources and people are discussing it. Each opens a native **story page**: heat over time, AI overview, latest, **developments**, and the full coverage timeline filterable by **All / First-hand / Selected**, newest or oldest first. Optional **Trending alerts** fire when a story enters the top N with at least M sources. |
| **Daily** | AIHOT's **daily, weekly and monthly** reports laid out as a newspaper: masthead with story, source and reading-time counts, headline, today's highlights, the issue's sections and quick news, with back issues. **Listen** reads it aloud. Your **morning briefing** alert opens it. |
| **Me** | Saved stories, **Topics** (all 38, with each one's latest story and 30-day count; a topic page has its timeline of milestones and its stories; **Follow** adds it to your alerts), Catch up, Search, Settings and Reliability setup. |
| **Live alerts** | By default every new story in aihot.news › Selected (all categories, about 10–25 a day); or only Selected › First-hand (official announcements, about 8–10 a day), or all AI news. A foreground service checks every 90 s (cheap 304s when nothing changed). WorkManager runs a 15-min backup check, and live alerts restart after a reboot. |
| **One alert per event** | AIHOT's event groups (`factId`) plus a headline-similarity check: the same launch reported by several outlets produces one alert. |
| **Corrections & retractions** | Official `selected/snapshot` + `selected/changes` sync: corrected stories are updated and re-translated, and stories AIHOT withdraws leave the feed, held alerts and the popup (as AIHOT's terms §7 require). |
| **Never miss, never repeat** | Unread dots and a "What did I miss" screen with a spoken rundown and Mark all read. **Search** the last 7 days of all news (English queries also search the Chinese). Story **follow-ups** alert you when a story you opened develops. The always-on notification shows the latest headline. A **home-screen widget** shows the newest headlines and your unread count. |
| **English everywhere** | DeepSeek (`deepseek-flash`, thinking off) translates and summarizes in batches, and writes a short spoken version of each story. If it's unavailable, the app shows the source's own English headline. |
| **Forced popup** | With *Display over other apps* allowed, a full-screen alert card opens over whatever app you're in. On the lock screen it wakes the display. Taps are ignored for the first 0.8 s so you can't hit a button by accident. Three or more stories arrive as one digest with a spoken rundown. |
| **Voice** | Xiaomi MiMo TTS clips are generated *before* the popup appears, so the voice starts instantly. Tapping **Listen** on any other story *streams* MiMo audio straight into an AudioTrack, so speech starts in about 3 s; recent stories are also pre-recorded on Wi-Fi. Speech plays on the media stream, so your volume buttons control it, and it pauses the podcast. Each take is checked against its expected length and re-recorded if MiMo runs on. Other audio ducks while it speaks. It stays silent during calls and while a podcast is playing. The phone's own voice is the fallback. |
| **Respectful** | Quiet hours, a popups-per-hour cap, "In 30 min" snooze, *always* and *never* keywords (they also match AIHOT tags such as Anthropic, Agent, Coding), and no alerts for items older than 12 h. |
| **AI podcast** | Choose **1 / 3 / 7 days**, then: collect *all* news (about 450 items a day) → editor merges duplicates and ranks stories (lenient) → plan deep dives, briefs and quick hits, with **every** story in the show notes → writer (DeepSeek V4 Pro) → MiMo records each line → loudness matching → AAC. One or two hosts, plus tone, emotion level, sung jingle, and custom voices (VoiceDesign once, then VoiceClone). Plays through Media3 with lock-screen and Bluetooth controls and 0.8–2× speed. Episodes are saved to **Music/AI News Hot**. It can also prepare a morning episode automatically. |
| **Explain** | A short developer-focused brief on any story: what happened, why it matters, what you could do, and open questions. |

### MiMo style control used by the podcast
- **Director mode** in the user message: `[Character]` is the host persona, `[Scene]` is the show tone and current segment, `[Guidance]` is that line's direction.
- A leading **`(style)` tag** and inline **`[audio tags]`** (`[laughs]`, `[sighs]`, `[pause]`…) in the assistant text. Each emotion level has a target tag density, and a safety net fills in missing tags from the director notes.
- **Singing mode** for the optional intro jingle.

## Build from source

```bash
cd mobile
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug          # build and install on a USB-connected phone
./gradlew assembleRelease       # app/build/outputs/apk/release/app-release.apk
```

- **Version:** taken from `VERSION` (versionCode = major·10000 + minor·100 + patch).
- **API keys:** default keys are read from `Resources/secrets.json` at build time.
- **Signing:** every build is signed with the project's own key in `mobile/signing/` (see *Signing* below).

**Debug builds only:** trigger alerts, a podcast, or playback from the computer:
```bash
adb shell am broadcast -a com.aliahad.ainewshot.DEBUG_ALERT --ei count 3 -p com.aliahad.ainewshot
adb shell am broadcast -a com.aliahad.ainewshot.DEBUG_PODCAST --ei days 1 -p com.aliahad.ainewshot
adb shell am broadcast -a com.aliahad.ainewshot.DEBUG_PLAY -p com.aliahad.ainewshot
```

## Releasing

```bash
echo 2.2.0 > VERSION
mobile/scripts/release.sh              # → build/AINewsHot-2.2.0.apk (add --install to put it on the phone)
gh release create v2.2.0 build/AINewsHot-2.2.0.apk --title "AI News Hot 2.2.0 (Android)" --notes "…"
```

## Signing

| File (`mobile/signing/`) | What it is |
|---|---|
| `release.jks` | The app's dedicated signing key (RSA 4096, valid 30 years) |
| `signing.properties` | Passwords and aliases, read by Gradle and `release.sh` |
| `legacy-debug.keystore` | The Android debug key that signed versions ≤ 2.1.0 |
| `lineage.bin` | APK Signature Scheme v3 rotation proof: the old key handed over to `release.jks` |

- Gradle signs **debug and release** builds with `release.jks`, so either installs over the other.
- `release.sh` additionally signs with the lineage, so phones that still have a debug-key build (≤ 2.1.0) **update in place and keep their data**. Always publish with `release.sh`.
- The repo is private, but anyone with access to it can sign APKs as this app. **Back up `mobile/signing/`**: if `release.jks` is lost, future updates can't install over existing copies, and people would have to uninstall first.

## Project layout

```
mobile/                 Android app (Kotlin, Jetpack Compose) — the maintained product
  app/src/main/java/com/aliahad/ainewshot/
    AIHot.kt            aihot.news client (first-hand timeline + official v1 API)
    AI.kt               DeepSeek translation/explain, MiMo TTS + run-on guard
    NewsRepo.kt         polling, filtering, holds, routing
    Alerts.kt           notifications, forced popup, speaker
    AlertActivity.kt    the full-screen alert card
    Background.kt       live foreground service, WorkManager, boot receiver
    Podcast.kt          editor → writer → recorder → mixer → AAC
    PodcastPlayback.kt  production service + Media3 playback
    ui/                 Compose screens (News, Podcast, Reports, Settings)
macos/                  deprecated macOS menu-bar app (Swift)
Resources/secrets.json  default API keys bundled into builds
VERSION                 app version
```

## Data source

The app uses aihot.news's anonymous, read-only API. Personal use is free. Commercial products or redistribution need the site's written permission ([terms](https://aihot.news/terms)). The first-hand feed uses the site's own timeline endpoint, which is undocumented. The app learns which sources are first-hand, so if that endpoint changes it falls back to the official API filtered to those sources.
