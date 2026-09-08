# WoW Audio

Premium Myanmar audiobook companion for WoW Reader, with standalone EPUB import and BYOK Gemini TTS.

## Current v0.2.0 test build

- Android application id: `com.whisper.wowaudio`
- minSdk 23, target/compile SDK 36
- version `0.2.0` / versionCode `2`
- Premium private EPUB library and Book Detail
- Standalone import from Android Files / Downloads / Telegram open/share flows
- EPUB metadata, cover, spine, readable text, EPUB3 NAV and EPUB2 NCX chapter titles
- Persistent local book metadata so WoW Reader supplied Burmese title/author survives reloads
- BYOK Gemini TTS using `gemini-3.1-flash-tts-preview`
- Gemini API key encrypted with Android Keystore and stored only on-device
- Voice and narration direction controls
- Per-chapter and whole-book offline generation with deterministic audio caching
- Retry/backoff for transient Gemini 408/429/5xx failures
- Continuous cached chapter playback
- Android MediaSession + foreground media playback + lock-screen/notification controls
- ±15 second seek, next/previous chapter, playback speed, sleep timer
- Offline cache status and cache clearing

CI builds debug + unsigned release, runs release lint, verifies the Reader handoff contract, and verifies APK identity.

## Integration contract with WoW Reader

- WoW Reader sends a book as a one-time read-only `content://` URI with `FLAG_GRANT_READ_URI_PERMISSION`.
- Primary action: `com.whisper.wowaudio.action.OPEN_BOOK`
- Data/MIME: `application/epub+zip` and `application/pdf`.
- Receiver copies the incoming URI into WoW Audio private app storage immediately; it never depends on the temporary grant after import.
- Original display name is preserved when possible.
- Title/author extras remain `wow_book_title` and `wow_book_author`.
- Do not copy API keys or secrets between apps.

The Reader → Audio receiver contract was previously verified on a real device and must remain stable.

## Security

Never commit Gemini/API keys or signing secrets. User Gemini API keys are encrypted with Android Keystore and remain device-local. Generated audio stays under app-private storage unless a future explicit export feature is added.

## Validation note

Repository CI verifies compilation, release lint, APK identity, and the stable Reader handoff contract. A real Gemini narration request still requires the user's own API key and device/network test; do not claim live API narration is verified until that test is performed.

## Still future / not part of v0.2.0

- Accurate sentence-follow highlighting with real audio timing
- Custom/My Voice
- Production-grade PDF narration
- Native/offline Myanmar TTS model
