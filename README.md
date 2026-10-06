# AI News Hot

A native macOS menu-bar app that watches [aihot.news](https://aihot.news/agent) and pushes the AI news that matters into a popup you can't miss. Everything is translated to English and can be read aloud.

## Build & run

```bash
scripts/build-app.sh            # builds build/AINewsHot.app
scripts/build-app.sh --install  # copies it to ~/Applications and launches it (needed for "Launch at login")
```

You need Xcode installed (the script points `DEVELOPER_DIR` at `/Applications/Xcode.app`, because SwiftUI macros aren't in Command Line Tools).

On the Mac, API keys live in `~/Library/Application Support/AINewsHot/secrets.json` (mode 0600). The defaults are seeded from `Resources/secrets.json`. You can edit them in Settings › Translation & AI and Settings › Audio.

## Install on another Mac

Requires macOS 26 or later. Works on Apple Silicon and Intel.

**Easiest (GitHub CLI):**
```bash
brew install gh && gh auth login
gh repo clone aliahadmd/ainewshot && cd ainewshot && bash install.sh
```

**Manual:**
1. Download `AINewsHot-<version>.zip` from the repo's **Releases** page.
2. Unzip it and move `AINewsHot.app` to `~/Applications` (or `/Applications`).
3. The app isn't notarized (there's no paid Apple Developer account), so macOS blocks it on first open. Fix it either way:
   - run `xattr -dr com.apple.quarantine ~/Applications/AINewsHot.app`, **or**
   - try to open it once, then go to System Settings › Privacy & Security › **Open Anyway**.

The app ships with API keys in `Resources/secrets.json`. They are copied to `~/Library/Application Support/AINewsHot/secrets.json` (mode 0600) on first launch. Change them any time in Settings.

## Releasing a new version

```bash
echo 1.1.0 > VERSION
scripts/build-app.sh --zip
gh release create v1.1.0 build/AINewsHot-1.1.0.zip --title "v1.1.0" --notes "…"
```

## How it works

| Stage | What happens |
|---|---|
| **Fetch** | **First-hand feed (default)** = the web's [精选 › 只看一手](https://aihot.news/?channel=firstParty): `GET /api/site/timeline?channel=firstParty`, the same endpoint the website uses. This list decides *which* items to show, and the official `GET /api/v1/items` adds each one's English original title and article link (matched by ID). Every request uses `If-None-Match`, so a check where nothing changed is a 304. Polls every 90 s and backs off on 429. |
| **Self-healing** | The site endpoint is undocumented. Each response teaches the app which sources are first-hand (OpenAI, Anthropic, Google, Hugging Face…). If the endpoint breaks, the app keeps working off the official API filtered to those learned sources, and the menu shows a warning. |
| **Translate** | DeepSeek (`deepseek-flash`, thinking disabled, about 6 s for 8 items) translates the title, summary and "why it matters" in one batched JSON call. The same call also writes a ≤45-word script to read aloud and gives a 0–100 relevance score against your profile. If DeepSeek fails, the app falls back to **Apple's on-device Translation framework** (free, offline, no AI), then to the source's own English headline (AIHOT includes `originalTitle`). |
| **Filter** | Feed: First-hand (~8 a day, recommended), All curated (~15–25 a day) or Everything. Also categories, minimum score, *always* and *never* keywords (these also match AIHOT tags such as Anthropic, Agent, Coding), an optional AI relevance threshold, and no alerts for items older than 12 h. Trending alerts are optional and off by default. |
| **Prepare audio** | The MiMo TTS clip (voice `Mia`, streaming PCM16 → WAV) is generated **before** the popup opens, so the voice starts the moment it appears. MiMo takes 5–20 s per clip. The fallback is the macOS system voice. |
| **Interrupt** | A centred floating panel on every Space, including full-screen apps, with an optional dimmed backdrop. Three or more stories at once become one **digest** with a spoken rundown. |

## AI podcast

In the menu bar, under **Podcast briefing**, choose **1 day**, **3 days** or **7 days**. A 1-day episode takes about 3 minutes to produce.

| Stage | What happens |
|---|---|
| Collect | Fetches *all* news for the period (≈450 items/day, ≈2,400/week), paging the API politely. Coverage can also be set to Curated or First-hand. |
| Edit | DeepSeek Flash processes batches of 250 in parallel. It merges coverage of the same event and rates importance 1–5. It is lenient: it drops only ads, chatter and duplicates. For long periods a second pass merges stories that were split across batches. |
| Plan | Assigns deep dives, briefs and quick hits based on the episode length. **Every** kept story goes into the show notes, with links. |
| Write | DeepSeek V4 Pro writes the script: cold open → themed deep dives → briefs → quick hits → wrap. It sticks to the provided facts with attribution, is written for the ear, and gives a line-count target. |
| Record | One MiMo call per line, using the full style-control set from the docs:<br>• **Director mode** in the user message: `[Character]` = host persona, `[Scene]` = show tone and segment, `[Guidance]` = that line's direction<br>• a **leading `(style)` tag** and **inline `[audio tags]`** in the assistant text<br>• **singing mode** for the optional jingle<br>• **VoiceClone** for custom voices<br>Five lines record in parallel. |
| Guard | Generative TTS sometimes keeps talking past the script for minutes. Every take is checked against its word count: bad takes are retried, and as a last resort capped with a fade. Silence is trimmed. |
| Mix | Matches loudness across voices with a soft limiter. Gaps are 0.22 s for the same speaker, 0.3 s for a speaker change and 0.85 s between chapters. Exports AAC 64 kbps `.m4a` and copies it to `~/Music/AI News Hot/`. |

**Player:** chapters, a transcript that highlights the current line (click any line to jump there), show notes, speed 0.8–2×, ±15/30 s. Media keys, AirPods and Control Center work through Now Playing; next/previous track jumps between chapters.

**Settings › Podcast:**
- solo or duo; for each host: name, voice and persona
- length: auto, or 5–30 min
- coverage
- tone: morning, newsroom, casual, energetic
- emotion: subtle (≈15% of lines tagged), natural (≈40%), expressive (≈75%, with breaths and laughs)
- sung jingle
- writer model
- auto-prepare a morning episode at a set time (7-day recap on Mondays), announced by a popup
- **Custom voices:**
  - *Design* a voice from a description. VoiceDesign renders it once and the sample is reused through VoiceClone, so the voice stays the same for every line.
  - *Clone* a voice from a 10–30 s wav/mp3 recording.

**Automation:** run `open -a ~/Applications/AINewsHot.app --args --podcast 1` (works from Shortcuts or cron).

## Popup controls

`⏎` Read (opens the original article, or the story timeline for trending items) · `esc` Dismiss · `space` Listen/Stop · `E` Explain · `←/→` browse · **Later** (re-pops in 15 min, 30 min, 1 h or 3 h).
Keyboard shortcuts only work after a 0.8 s guard, so a key you were already pressing in another app can't trigger the popup by accident.

Global hotkeys: **⌃⌥⌘N** reopens recent alerts · **⌃⌥⌘.** stops speech.

## Beyond the basics

- **Trending detection.** When one story is picked up by 8+ sources and enters the top 5, you get an alert, even if no single article scored high.
- **Explain button.** DeepSeek writes a short developer brief: what happened, why it matters, what you could try, and open questions. It sticks to the item's text and labels any background it adds.
- **Morning briefing.** At 08:15 the app turns AIHOT's daily report into an English briefing with a 60–90 s spoken version. A weekly one comes on Mondays. Daily, Trending, Weekly and Monthly reports can also be opened on demand from the menu.
- **Respectful interruption:**
  - Alerts are held while the screen is locked and during quiet hours or a pause. Held alerts arrive later as one digest.
  - A popups-per-hour cap keeps a busy news day from flooding you.
  - The voice stays **silent while your microphone is in use** (calls and meetings).
  - After the Mac wakes, the app catches up right away.
- **No first-run flood.** On the first launch the app fills the menu with recent news but doesn't pop any of it up.

## Other ways to use the same data

- **Claude Code / Claude Desktop:** `claude mcp add --transport http aihot https://aihot.news/api/mcp` adds 8 tools (latest, search, hot topics, story, daily/weekly/monthly).
- **RSS:** `https://aihot.news/feed.xml` (curated), `/feed/category/ai-models.xml`, etc.

## Files

`Sources/AINewsHot/`
- `AIHot.swift`: API client and models
- `Translator.swift`: DeepSeek and Apple Translate
- `Audio.swift`: MiMo TTS, playback and microphone detection
- `Engine.swift`: polling, filters and routing
- `Alert.swift`: popup panel
- `Reader.swift`: document window
- `MenuView.swift`
- `SettingsView.swift`
- `App.swift`: app entry point and hotkeys

Data lives in `~/Library/Application Support/AINewsHot/`: `state.json`, `secrets.json`, and `audio/` (a 3-day cache).

AIHOT terms: personal use is free. Commercial products or redistribution need written permission ([terms](https://aihot.news/terms)).
