# WoW Audio

Premium Myanmar audiobook companion for WoW Reader, with standalone EPUB import and BYOK Gemini TTS.

## WoW Audio v1.0.0

- Android application id: `com.whisper.wowaudio`
- minSdk 23, target/compile SDK 36
- version `1.0.0` / versionCode `3`
- Premium listening-first private EPUB library
- Continue Listening, Downloaded / Offline, Recent Books and All Imported Books sections
- Standalone import from Android Files / Downloads / Telegram open/share flows
- Stable WoW Reader → WoW Audio handoff preserved
- EPUB metadata, cover, spine, readable text, EPUB3 NAV and EPUB2 NCX chapter titles
- Persistent local book metadata so WoW Reader supplied Burmese title/author survives reloads
- BYOK Gemini TTS using `gemini-3.1-flash-tts-preview`
- Gemini API key encrypted with Android Keystore and excluded from backup / device transfer
- API-key test + voice preview flow
- 30 Gemini TTS voices and narration style presets/custom direction
- Myanmar speech preparation for Unicode cleanup, spacing, punctuation, Myanmar/ASCII digits, dates, times and percentages
- Per-chapter and whole-book sequential pre-generation with deterministic private offline audio caching
- Retry/backoff for transient Gemini 408/429/5xx failures and clearer key/quota/network errors
- Continuous cached chapter playback
- Device-local listening progress and resume position
- Android foreground media playback + MediaSession + lock-screen/notification controls
- Play/pause, ±15 second seek, previous/next chapter, playback speed and sleep timer
- Follow Text / Now Playing screen with cover, progress and sentence-level highlighting
- Follow Text timing uses measured generated-audio duration plus proportional sentence timing; it is not model-provided word timestamps
- Offline cache state and clearing controls

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

## Validation boundary

Repository CI verifies compilation, release lint, APK identity, and the stable Reader handoff contract. A live Gemini narration request still requires the user's own API key entered only in the app and a real-device/network test. CI success alone does not prove that a particular user key/quota/network combination works.

## Intentionally future

- Custom/My Voice or voice cloning
- Production-grade PDF narration
- Native/offline Myanmar TTS model
- Model-provided exact word/sentence timestamps if Gemini TTS exposes them in a future API
- Process-kill-resumable background generation queue (completed chapter cache already survives interruption)
