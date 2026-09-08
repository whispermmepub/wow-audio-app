# WoW Audio — New Chat Handoff

Use this repository as the source of truth:

`whispermmepub/wow-audio-app`

## Current repository state

- Android application id: `com.whisper.wowaudio`
- minSdk 23, target/compile SDK 36
- Current test version: `0.2.0` / versionCode `2`
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

## Features implemented after the starter

### Library and standalone EPUB import

- Premium audiobook-first Library/Home shell.
- `Add Book / Import EPUB` system file picker.
- Android ACTION_VIEW / ACTION_SEND import support for Files, Downloads and Telegram flows.
- ACTION_SEND also accepts `ClipData` fallback.
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
- Narration/offline-audio entry point.

### BYOK Gemini TTS

- Current official model used by this branch: `gemini-3.1-flash-tts-preview`.
- Current Interactions REST endpoint: `https://generativelanguage.googleapis.com/v1beta/interactions`.
- Current schema uses `response_format: {"type":"audio"}` and `generation_config.speech_config`.
- `Api-Revision: 2026-05-20` is sent.
- Burmese is supported by current official Gemini TTS docs (`my`).
- User API key is encrypted with Android Keystore AES-GCM and remains device-local.
- No shared WoW owner-paid API key or quota.
- Saved API key can be explicitly removed in the UI.
- Voice picker and custom narration direction.
- Long chapter text is split into conservative punctuation-aware chunks.
- Transient 408/429/5xx Gemini failures use bounded retry/backoff.
- Audio is written as private WAV files.

### Offline audio and playback

- Deterministic cache key includes book/chapter text/voice/style, preventing unnecessary repeated generation for unchanged settings.
- Generate one chapter or generate the whole book sequentially.
- Cached chapter status and total cache size shown in UI.
- Clear offline audio cache control.
- Continuous playback across consecutive cached chapters.
- Android foreground media playback service.
- MediaSession / notification / lock-screen controls.
- Play/pause, ±15 second seek, previous/next chapter.
- Playback speed preferences from 0.8× through 2.0×.
- Sleep timer choices including 15/30/45/60/90 minutes.

## Current development branch

Active TTS/playback branch:

`feature/gemini-byok-tts-playback`

The branch is intended to be merged only after its final v0.2.0 build/lint/APK identity CI is green.

## Validation boundary

Do not claim live Gemini narration is verified merely because CI is green.

CI can verify:
- Android compilation
- unsigned release build
- release lint
- APK identity/version
- static Reader handoff contract

A real Gemini narration request requires the user's own API key and a real-device/network test. The assistant does not have the user's key and must not put any key into GitHub.

## Still intentionally future

Do not block v0.2.0 on these:

- Accurate sentence-follow highlighting synchronized to real audio timing. Do not fake timing.
- Custom/My Voice or voice cloning.
- Production-quality PDF narration.
- Native/offline Myanmar TTS model.

## Important implementation caveat

Current whole-book generation runs sequentially from the narration UI process and writes each completed chapter to the durable private cache. Completed chapters survive interruption, but an active generation job is not yet a resumable Android WorkManager/foreground-generation job. If process-kill resilience becomes a priority, add a persistent/resumable generation queue without changing the Reader handoff contract.

## Security and development safety

- Never commit API keys, signing keys, OAuth credentials or recovery secrets.
- Keep user Gemini keys device-local.
- Keep CI artifact retention short.
- Do feature work on branches and merge only after green CI.
- Preserve the already-working Reader receiver while refactoring.
- Do not claim a feature works until the relevant build/test exists.

## Next task in a future chat

1. Read this file and inspect `main` before changing anything.
2. If v0.2.0 has not yet been merged, verify the active TTS branch CI and finish that merge first.
3. If it is merged, begin with real-device validation using a user-provided BYOK key entered only in the app UI.
4. Fix live API/playback issues found in that test before adding new scope.
5. Only after live narration is stable, consider resumable whole-book generation, premium Now Playing polish, and accurate sentence-follow timing.
