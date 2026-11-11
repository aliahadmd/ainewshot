# Direct DeepSeek API restoration

Researched 2026-11-09 against DeepSeek and Android Developers documentation. The recommendations below apply to the maintained Android app. They do not require changing the deprecated macOS app or deleting any deployed Cloudflare resources.

## Provider contract

Use `POST https://api.deepseek.com/chat/completions`, JSON requests, and an `Authorization: Bearer <user-supplied key>` header. The currently documented model IDs are `deepseek-flash` and `deepseek-v4-pro`. Flash now serves DeepSeek-V4.1-Flash-1017; Pro serves DeepSeek-V4-Pro-0813. The older `deepseek-v4-flash` alias remains accepted but routes to the newer Flash model. The repository's former `deepseek-flash` and `deepseek-v4-pro` configuration is therefore still valid. [DeepSeek quickstart](https://api-docs.deepseek.com/), [model details](https://api-docs.deepseek.com/quick_start/pricing/)

For this application, restore Flash for translation, summaries, Explain, and the podcast editor; restore the podcast writer's Pro default. This is an application design recommendation matching the previous division of work, not a provider requirement.

Set `"thinking": {"type": "disabled"}` explicitly for these tasks. Thinking is enabled with high effort by default. In thinking mode, reasoning is returned separately in `reasoning_content`; the user-facing answer remains `content`. Sampling temperature has no effect while thinking is enabled. Disabling thinking keeps routine translation and script generation from unexpectedly spending their token budget on reasoning. [Thinking mode](https://api-docs.deepseek.com/guides/thinking_mode/)

With thinking disabled, using `temperature: 0.2` is supported for both models. Do not confuse temperature with `top_p`: Flash's non-thinking `top_p` is fixed at 1, while Pro's non-thinking `top_p` accepts the requested value. Flash thinking clamps `top_p` below 0.95; Pro thinking ignores it. Leave `top_p` unspecified when setting temperature. [Chat Completions reference](https://api-docs.deepseek.com/api/create-chat-completion/), [sampling details](https://api-docs.deepseek.com/guides/thinking_mode/)

## JSON, streaming, and completion checks

For structured responses, send `"response_format": {"type": "json_object"}`, explicitly request JSON in the prompt, and include the required output shape. Allocate enough output tokens. DeepSeek documents that unrecognizable JSON can be replaced with whitespace, producing blank content even when the finish reason is `stop`. Detect blank content and allow a bounded retry; validate JSON and the application's expected fields before accepting it. [JSON output guide](https://api-docs.deepseek.com/guides/json_mode/)

`max_tokens` accepts 1–393216; its non-thinking default is 8192. Explicit existing application budgets, including the writer's 24000-token ceiling, fit the documented contract. Streaming uses SSE `data:` payloads; concatenate `choices[0].delta.content` and ignore separate reasoning. The terminal marker is `data: [DONE]`. Final finish reasons include `stop`, `length`, `content_filter`, `tool_calls`, `insufficient_system_resource`, and `aborted`. [Chat Completions reference](https://api-docs.deepseek.com/api/create-chat-completion/)

Recommended acceptance rule: require a normal `stop` completion and nonblank content, with the terminal SSE marker for streaming. Treat truncated, filtered, aborted, or unexpectedly closed streams as failures; never persist partial article translations as completed content. Keep streaming article and Explain UX from the Cloudflare migration. A retry after partial streaming must reset the accumulated output or otherwise avoid duplicating visible text. These checks are application recommendations derived from the documented response contract.

DeepSeek can send blank lines while a non-streaming request waits, or `: keep-alive` comments during streaming. Ignore them. The server closes requests that have not begun inference after ten minutes; keep an application-level timeout rather than waiting indefinitely. [Rate limits and keep-alives](https://api-docs.deepseek.com/quick_start/rate_limit/)

## Failures and retries

The provider documents 400 for bad request format, 401 for invalid credentials, 402 for insufficient balance, 422 for invalid parameters, 429 for rate/concurrency limits, and 500/503 for server errors/overload. Its guidance is to correct credentials/input/balance errors, pace rate-limited requests, and wait briefly before retrying server errors. [DeepSeek error codes](https://api-docs.deepseek.com/quick_start/error_codes/)

Recommended implementation: bound retries, apply exponential delay with jitter, respect a supplied `Retry-After`, and preserve coroutine cancellation. Retry transient server/rate-limit failures and blank output conservatively. Do not repeatedly retry authentication, balance, or parameter errors. Keep useful provider error messages without logging headers, keys, or full request payloads. Do not blindly replay partially delivered streams. A shared small concurrency bound is appropriate for this personal reader even though the provider's published account limits are much higher.

## User-supplied credentials on Android

Android's security checklist recommends Keystore-backed key management, encryption of stored keys, and excluding API keys from source control. For a direct-provider personal app, accept the user's DeepSeek and MiMo keys in Settings instead of embedding developer credentials in BuildConfig, resources, APK assets, or committed configuration. [Android API key guidance](https://developer.android.com/privacy-and-security/security-tips)

Android Keystore keeps cryptographic key material non-exportable and lets an app restrict its use. Keystore operations should run off the main thread. Practical design: generate an app-owned AES-GCM wrapping key in `AndroidKeyStore`, encrypt credential values using a fresh IV per write, and store the ciphertext in app-private storage. Do not require per-use biometric authentication for the wrapping key because this app must poll and generate voice in the background. This construction is an application recommendation based on the platform's cryptographic facilities. It protects credentials at rest; a direct API client necessarily obtains their plaintext in memory when sending requests. [Android Keystore](https://developer.android.com/privacy-and-security/keystore)

Place encrypted credential storage under `Context.noBackupFilesDir`. Android documents that this directory is always excluded from Auto Backup, avoiding restoration of ciphertext whose device-specific wrapping key is absent. Ask for keys again after reinstall/restore or unreadable credential storage, and retain ordinary settings/news data independently. [Android Auto Backup](https://developer.android.com/identity/data/autobackup)

## Direct MiMo voice calls

Xiaomi's API reference confirms `POST https://api.xiaomimimo.com/v1/chat/completions` and both `api-key` and Bearer authentication. Existing `Authorization: Bearer <user-supplied key>` is valid. The existing model IDs remain `mimo-v2.5-tts`, `mimo-v2.5-tts-voicedesign`, and `mimo-v2.5-tts-voiceclone`. Streaming uses SSE; audio bytes are Base64 in `choices[0].delta.audio.data`. Completion reasons include `stop`, `length`, and `content_filter`, so reject unsuccessful completion and empty audio. [MiMo TTS API reference](https://mimo.mi.com/docs/en-US/api/audio/tts-post)

Keep synthesis text in the `assistant` message and style instructions in `user`. Streaming audio should request `audio.format: "pcm16"`; the provider's example decodes 24 kHz, little-endian, signed PCM16 mono. Preset TTS supports low-latency output; voice design and cloning currently return their completed audio in one compatibility-stream response. The existing 12-second first-audio fallback may therefore trigger for custom voices without indicating a credential failure. [MiMo speech synthesis guide](https://mimo.mi.com/docs/en-US/quick-start/usage-guide/audio/speech-synthesis-v2.5)

Xiaomi's own scripts independently confirm the OpenAI-compatible base URL and message layout, and its skill lists all three TTS models and the Mia/Chloe/Milo/Dean English voices. [Official MiMo script](https://github.com/XiaomiMiMo/MiMo-Skills/blob/main/skills/mimo-v2-5-tts/scripts/mimo_tts.py), [official MiMo skill](https://github.com/XiaomiMiMo/MiMo-Skills/blob/main/skills/mimo-v2-5-tts/SKILL.md)

## Scope for this migration

Remove the Android Cloudflare Access sign-in requirement and call DeepSeek and MiMo directly. Preserve public AIHOT access, local settings/read/saved/episode data, streaming articles and Explain, translation recovery, voice fallback, and release-signing lineage. Existing historical credentials may already have been exposed in older builds or Git history; do not restore them into tracked source or distribute them in new APKs. External Worker/Access deletion is a separate infrastructure action and is unnecessary to restore the app's direct API path.

## HTTP transport validation

A local HTTP integration test reproduced blocked cancellation with the original `HttpURLConnection` POST transport. Provider POST streaming now uses a shared OkHttp client, with redirects and automatic connection retries disabled, finite call/read/write/connect timeouts, and coroutine cancellation wired to `Call.cancel()`. DeepSeek retains the explicit bounded retry policy. Public AIHOT GET behavior is unchanged. [OkHttp cancellation and timeouts](https://lysine.dev/okhttp/recipes/), [release history](https://github.com/lysine-dev/okhttp/blob/main/CHANGELOG.md)

## Verification of the implementation

- `./gradlew :app:testDebugUnitTest :app:assembleDebug`: passed; 26 local tests cover request construction, real localhost HTTP/SSE, credential encryption, response completion checks, retry/cancellation, and translated-item persistence.
- The stalled-connection cancellation test failed against the original transport and passes with OkHttp (about 0.01 seconds in this run).
- `./gradlew :app:lintDebug`: reports four pre-existing errors, confirmed against `HEAD`: notification permission handling in `Background.kt` and `PodcastPlayback.kt`, and two Android 31 API guards missing from `ui/Theme.kt`. These are outside the provider restoration; no lint baseline or suppression was added.
- No Android device was connected. Android Keystore persistence/migration and the key-entry UI still need an on-device or emulator check; no live provider calls were made with historical credentials.
