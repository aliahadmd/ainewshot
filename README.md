# AI News Hot

An Android app that keeps you up to date on AI. It watches [aihot.news](https://aihot.news/agent) live, translates everything to English, and pops important news up in front of whatever you're doing, reading it aloud with a natural voice. It can also produce an AI podcast covering the last day, three days or week of AI news.

> The original macOS menu-bar app is **deprecated**. It lives in [`macos/`](macos/README.md), and its last release is v1.0.0.

## Install

Requires Android 10 or later. It's tested on a Redmi K80 Pro running HyperOS 4 / Android 17.

1. On the phone, open the latest release on the repo's [Releases page](https://github.com/aliahadmd/ainewshot/releases) and download `AINewsHot-<version>.apk`. The repo is private, so sign in to GitHub in the phone's browser first, or copy the APK over from a computer.
2. Open the APK and allow your browser or file manager to install unknown apps. HyperOS may run a security scan first; choose **Install anyway**.
3. Open **AI News Hot**. Allow notifications, then go to **Settings › Permissions & reliability** and tap **Allow** on every row.
4. **Xiaomi / HyperOS only:** in AI News Hot's app info, turn on **Autostart**, set **Battery saver** to *No restrictions*, and under *Other permissions* enable **Display pop-up windows while running in the background**. The *Open HyperOS app permissions* button in Settings takes you there.

The APK includes default DeepSeek and MiMo API keys, so it works right away. You can change them in **Settings › API keys**.

To update, install a newer APK over the old one; your settings and episodes are kept.

## What it does

| Feature | How |
|---|---|
| **Live first-hand news** | The same list as aihot.news › 精选 › 只看一手: announcements straight from OpenAI, Anthropic, Google, Hugging Face and others, about 8 a day. A foreground service checks every 90 s (cheap 304s when nothing changed). WorkManager runs a 15-min backup check, and live alerts restart after a reboot. |
| **English everywhere** | DeepSeek (`deepseek-flash`, thinking off) translates and summarizes in batches, and writes a short spoken version of each story. If it's unavailable, the app shows the source's own English headline. |
| **Forced popup** | With *Display over other apps* allowed, a full-screen alert card opens over whatever app you're in. On the lock screen it wakes the display. Taps are ignored for the first 0.8 s so you can't hit a button by accident. Three or more stories arrive as one digest with a spoken rundown. |
| **Voice** | Xiaomi MiMo TTS clips are generated *before* the popup appears, so the voice starts instantly. Each take is checked against its expected length and re-recorded if MiMo runs on. Other audio ducks while it speaks. It stays silent during calls and while a podcast is playing. The phone's own voice is the fallback. |
| **Respectful** | Quiet hours, a popups-per-hour cap, "In 30 min" snooze, *always* and *never* keywords (they also match AIHOT tags such as Anthropic, Agent, Coding), and no alerts for items older than 12 h. |
| **AI podcast** | Choose **1 / 3 / 7 days**, then: collect *all* news (about 450 items a day) → editor merges duplicates and ranks stories (lenient) → plan deep dives, briefs and quick hits, with **every** story in the show notes → writer (DeepSeek V4 Pro) → MiMo records each line → loudness matching → AAC. One or two hosts, plus tone, emotion level, sung jingle, and custom voices (VoiceDesign once, then VoiceClone). Plays through Media3 with lock-screen and Bluetooth controls and 0.8–2× speed. Episodes are saved to **Music/AI News Hot**. It can also prepare a morning episode automatically. |
| **Reports** | AIHOT's daily, trending, weekly and monthly reports, translated and ready to listen to. |
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
- **Signing:** release APKs are signed with this Mac's Android debug key (no Play Store). Updates must be signed with the same key, or the old app has to be uninstalled first.

**Debug builds only:** trigger alerts, a podcast, or playback from the computer:
```bash
adb shell am broadcast -a com.aliahad.ainewshot.DEBUG_ALERT --ei count 3 -p com.aliahad.ainewshot
adb shell am broadcast -a com.aliahad.ainewshot.DEBUG_PODCAST --ei days 1 -p com.aliahad.ainewshot
adb shell am broadcast -a com.aliahad.ainewshot.DEBUG_PLAY -p com.aliahad.ainewshot
```

## Releasing

```bash
echo 2.1.0 > VERSION
(cd mobile && ./gradlew assembleRelease)
cp mobile/app/build/outputs/apk/release/app-release.apk build/AINewsHot-2.1.0.apk
gh release create v2.1.0 build/AINewsHot-2.1.0.apk --title "AI News Hot 2.1.0 (Android)" --notes "…"
```

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
