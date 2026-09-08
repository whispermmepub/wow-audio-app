# WoW Audio — New Chat Handoff

Use this repository as the source of truth:

`whispermmepub/wow-audio-app`

## Current repository state

- Android application id: `com.whisper.wowaudio`
- minSdk 23, target/compile SDK 36
- Final v1 test identity: `1.0.0` / versionCode `3`
- CI builds debug + unsigned release, runs release lint, verifies the Reader handoff contract, and verifies APK identity.
- Stable WoW Reader action remains `com.whisper.wowaudio.action.OPEN_BOOK`.
- Reader EPUB/PDF MIME contract remains `application/epub+zip` / `application/pdf`.
- Incoming Reader `content://` URIs are copied immediately into private `files/library` storage.
- Reader title/author extras remain `wow_book_title` / `wow_book_author`.

## Real-device integration already verified

The original WoW Reader v61 → WoW Audio handoff was tested on a real device:

1. Open the Reader per-book actions.
2. Tap `🎧 Open in WoW Audio`.
3. WoW Audio opens.
4. The EPUB is received and copied.
5. Burmese title, author and filename arrive correctly.

Preserve this cross-app contract. Do not modify WoW Reader unless both apps truly need a coordinated contract change.

Reader integration branch historically used:

`whispermmepub/wow-reader-lab` → `feature/v61-wow-audio-handoff`

## v1 feature set

### Library and standalone EPUB import

- Premium listening-first Library/Home.
- Continue Listening section backed by device-local progress.
- Downloaded / Offline section with offline chapter counts.
- Recent Books and All Imported Books sections.
- `Add Book / Import EPUB` system file picker.
- Android ACTION_VIEW / ACTION_SEND import support for Files, Downloads and Telegram flows.
- ACTION_SEND accepts `ClipData` fallback.
- Private app-library copy pipeline.
- SHA-256 duplicate detection.
- Original filename preservation with collision-safe naming.

### EPUB data model

- OPF metadata parsing.
- Cover extraction.
- Spine-order readable chapter extraction.
- Robust relative EPUB path handling including `../`, fragments and query components.
- EPUB3 NAV chapter titles.
- EPUB2 NCX chapter titles.
- Heading and numbered fallback chapter titles.
- Persistent `library-index.json` book metadata.
- Reader-supplied Burmese title/author persists across app reloads.

### Book Detail

- Cover, title, author and chapter list.
- Chapter text previews.
- Offline narration state/count.
- Narration/listening entry point.

### BYOK Gemini TTS

- Model: `gemini-3.1-flash-tts-preview`.
- Interactions REST endpoint: `https://generativelanguage.googleapis.com/v1beta/interactions`.
- Request schema uses `response_format: {"type":"audio"}` and `generation_config.speech_config`.
- `Api-Revision: 2026-05-20` is sent.
- User API key is encrypted with Android Keystore AES-GCM and remains device-local.
- Key storage is excluded from Android backup and device transfer.
- No shared WoW owner-paid API key or quota.
- Saved API key can be explicitly removed in the UI.
- API-key test + voice preview flow.
- 30 Gemini TTS voices.
- Natural/Warm/Calm/Storyteller/Dramatic/Soft/Bedtime style presets plus Custom direction.
- Long chapter text is split into conservative punctuation-aware chunks.
- Transient 408/429/5xx Gemini failures use bounded retry/backoff.
- Invalid-key, quota/rate-limit and network failures get clearer user-facing errors.
- Audio is written as private WAV files.

### Myanmar narration preparation

- Original EPUB text remains unchanged.
- Only the copy sent for speech is normalized.
- NFC/zero-width/spacing cleanup.
- Myanmar and ASCII digits are converted to Myanmar number words when Myanmar script is present.
- Common numeric dates, times and percentages are prepared for more natural speech.
- Burmese punctuation and pauses are normalized conservatively.

### Offline audio and playback

- Deterministic cache key includes cache version, book/chapter text/voice/style.
- Generate one chapter or generate the whole book sequentially.
- Completed chapters remain in durable private storage even if a later chapter fails or the app is interrupted.
- Cached chapter status and total cache size shown in UI.
- Clear offline audio cache control.
- Continuous playback across consecutive cached chapters.
- Device-local chapter/position resume progress.
- Android foreground media playback service.
- MediaSession / notification / lock-screen controls.
- Play/pause, ±15 second seek, previous/next chapter.
- Playback speed from 0.8× through 2.0×.
- Sleep timer choices including 15/30/45/60/90 minutes.

### Follow Text / Now Playing

- Large book cover, title/author, chapter and playback progress.
- Previous/current/next text context.
- Current sentence-level segment highlighted more prominently.
- Chapter and ±15-second controls.
- Timing metadata is stored next to generated audio.
- Important: Gemini currently does not provide exact word timestamps through this flow. WoW Audio measures actual generated chunk audio duration and proportionally divides each chunk across its sentence units. Treat this as useful approximate sentence-follow timing, not exact model timestamps.

## Validation boundary

Do not claim live Gemini narration is verified merely because CI is green.

CI verifies:
- Android compilation
- unsigned release build
- release lint
- APK identity/version
- static Reader handoff contract

The Reader → Audio import contract itself has prior real-device verification. Live Gemini TTS still requires the user's own API key entered only in the app UI plus a real-device/network test. Never put a user API key into GitHub.

## Intentionally future

These are not blockers for the v1 EPUB audiobook build:

- Custom/My Voice or voice cloning.
- Production-quality PDF narration.
- Native/offline Myanmar TTS model.
- Exact model-provided word/sentence timestamps if a future Gemini TTS API exposes them.
- Process-kill-resumable WorkManager/foreground generation queue. Whole-book generation is sequential and completed chapter files already persist.

## Security and development safety

- Never commit API keys, signing keys, OAuth credentials or recovery secrets.
- Keep user Gemini keys device-local.
- Keep CI artifact retention short.
- Do feature work on branches and merge only after green CI.
- Preserve the already-working Reader receiver while refactoring.
- Do not claim a feature works until the relevant build/test exists.

## Next task in a future chat

1. Read this file and inspect `main` before changing anything.
2. Confirm v1.0.0 is merged and main CI is green before starting new scope.
3. Run real-device validation with a user-owned Gemini API key entered only in the app UI.
4. Fix any live API/voice/playback issues found in that test before broadening scope.
5. Future scope can then focus on generation-job resilience, exact sync if upstream supports timestamps, PDF narration, or Custom Voice.
