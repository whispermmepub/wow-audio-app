# WoW Audio — New Chat Handoff

Use this repository as the source of truth:

`whispermmepub/wow-audio-app`

## Current release line

- Android application id: `com.whisper.wowaudio`
- minSdk 23, target/compile SDK 36
- Current feature identity: `1.3.0` / versionCode `7`
- Stable Reader action: `com.whisper.wowaudio.action.OPEN_BOOK`
- Reader EPUB/PDF MIME contract: `application/epub+zip` / `application/pdf`
- Reader title/author extras: `wow_book_title` / `wow_book_author`
- Incoming content URIs are copied into private `files/library` storage.
- Permanent update signer SHA-256 remains `5F:58:AB:46:3C:3A:43:27:5D:D7:68:FB:3B:DB:2A:AE:F3:88:B5:C1:8A:4D:60:76:B0:C5:33:9B:4A:EE:0F:E1`.

## Product goal

WoW Audio is built first for simple, screen-reader-friendly audiobook use for Myanmar blind and low-vision readers.

Primary experience:

`Set up a voice once → Add EPUB → automatic whole-book preparation → Home → Play / Resume`

Users should not have to generate chapters manually.

## Real-device Reader contract already verified

The original WoW Reader → WoW Audio handoff was verified on a real device. Preserve this contract while refactoring. Do not modify WoW Reader unless both apps genuinely require a coordinated protocol change.

## v1.3 narration engines

### Automatic — default

`NarrationSettings.ENGINE_AUTO`

- If official eSpeak NG Android TTS package `com.reecedunn.espeak` is installed, WoW Audio uses offline Burmese synthesis first.
- Otherwise, if a user-owned Gemini API key exists, WoW Audio uses Gemini natural voice.
- If neither is available, the imported book is still queued in setup-required state and the user is sent to accessible one-time voice setup.

### Offline Burmese — eSpeak NG

`NarrationSettings.ENGINE_OFFLINE`

- Uses the separately installed official eSpeak NG Android TextToSpeechService package `com.reecedunn.espeak`.
- Burmese language is requested as `my-MM` / upstream language identifier `my`.
- Uses Android `TextToSpeech.synthesizeToFile()`.
- Long chapters are punctuation-chunked under Android TTS input limits.
- Temporary WAV chunks are parsed as PCM mono 16-bit and streamed into the durable WoW Audio WAV cache.
- No Gemini API key, provider quota or internet is required for synthesis once the engine is installed.
- WorkManager recovery does not require network while the offline engine is active.
- Offline audio has a distinct cache engine key so it never mixes with Gemini narration.
- eSpeak is fast, compact and useful at high reading speeds, but it is less natural than Gemini neural speech.

### Gemini Natural

`NarrationSettings.ENGINE_GEMINI`

- Uses the user-owned encrypted Gemini API key.
- Current model remains `gemini-3.1-flash-tts-preview` through the Interactions API.
- Existing v1.2 Gemini cache keys remain backward compatible.
- 30 Gemini voices and style presets remain available.
- 408/429/5xx/network retry and durable quota backoff remain intact.

## Licensing boundary — important

WoW Audio does **not** copy or link eSpeak NG core code/assets into its application package in v1.3.

It talks to eSpeak NG as a separately installed Android TTS engine through the standard Android TextToSpeech API. The setup screen points to the official upstream eSpeak NG Android APK release.

Do not bundle eSpeak NG core into WoW Audio later without explicitly reviewing and complying with eSpeak NG's GPL licensing obligations. A future system-wide `WoW Burmese Voice` companion may be built as a clearly licensed separate project if desired.

## One-time offline voice setup

`OfflineVoiceSetupActivity` is TalkBack-friendly and uses large controls.

- Detects whether `com.reecedunn.espeak` is installed.
- Install button opens the official eSpeak NG Android release APK URL.
- Returning after install auto-detects the engine, selects Auto mode, reconciles the library and resumes pending books.
- Users may choose Gemini instead.
- Setup-required generation notifications route to this activity.

## Accessible Home contract

- Add Book is a primary action.
- Home announces the active voice engine truthfully.
- If no engine is available, Home exposes one clear `Set up voice once` action.
- Continue Listening / Resume remains primary when available.
- Each book reports ready/preparing/setup state based on the active engine, not merely whether a Gemini key exists.
- Home Play appears only when all chapters for the active engine are ready for uninterrupted listening.
- More Options contains advanced controls.
- Decorative content should not clutter TalkBack focus.

## Durable automatic generation — must not regress

v1.2 introduced the core resilient architecture and v1.3 reuses it for both engines.

- `GenerationQueueStore` persists per-book state, chapter checkpoint, retry timing, failures and revision.
- Imports are always queued, including before voice setup.
- `NarrationGenerationEngine` is process-serialized with an engine lock.
- Every completed/cached chapter is checkpointed.
- Existing partial books resume from the first missing cached chapter.
- FGS provides the user-visible fast path after import/app foreground.
- WorkManager is the durable fallback for background restrictions, reboot/update recovery and delayed retries.
- BOOT_COMPLETED/MY_PACKAGE_REPLACED do not illegally start a dataSync foreground service.
- Android 15+ dataSync timeout hands work to WorkManager.
- Low storage/memory conditions pause instead of corrupting cache files.
- Voice/engine changes create fresh queue revisions so narration from different settings is not mixed.

## Cache and playback

- `AudioCache` resolves the active engine cache key consistently across Home, advanced UI, resume and playback.
- v1.2 Gemini cached audio remains reusable when Gemini is active.
- Offline eSpeak audio uses `offline-espeak-ng-my` in the cache material.
- Continuous cached chapter playback, MediaSession, lock-screen controls, ±15 seconds, previous/next chapter, speed and sleep timer remain.
- Follow Text sidecars remain approximate sentence timing based on generated audio duration; do not claim exact upstream word timestamps.

## EPUB/import behavior preserved

- Private EPUB library copy.
- ZIP/container validation.
- SHA-256 duplicate detection.
- EPUB metadata, cover, spine chapters, EPUB3 NAV and EPUB2 NCX.
- Standalone Files/Downloads/Telegram open/share flows.
- Reader handoff contract remains unchanged.

## Permanent signing

Starting with stable v1.1.0, all installable update releases must use the same private update certificate.

Expected signer SHA-256:

`5F:58:AB:46:3C:3A:43:27:5D:D7:68:FB:3B:DB:2A:AE:F3:88:B5:C1:8A:4D:60:76:B0:C5:33:9B:4A:EE:0F:E1`

GitHub secret names if CI signing is enabled:

- `WOW_AUDIO_KEYSTORE_B64`
- `WOW_AUDIO_STORE_PASSWORD`
- `WOW_AUDIO_KEY_ALIAS`
- `WOW_AUDIO_KEY_PASSWORD`

Never commit the private keystore or passwords. If GitHub secrets are unavailable, sign the main-branch unsigned release offline with the preserved permanent key and verify v1/v2/v3 signatures plus certificate fingerprint.

## Validation boundary

CI can verify Android compilation, release lint, APK identity, Reader/static accessibility contracts, resilient queue architecture and offline-TTS integration code.

CI **cannot** prove that eSpeak NG's Burmese voice sounds correct on the user's actual phone. The official engine must be installed and tested on a real Android device. Likewise, live Gemini behavior and OEM background policies need device testing. Do not claim either live engine is verified from CI alone.

## Next real-device tests

1. Update stable v1.2.0 to v1.3.0 without uninstalling.
2. Install official eSpeak NG Android APK once.
3. Open WoW Audio and verify Home says `Voice: Offline Burmese • eSpeak NG`.
4. Import a Burmese EPUB with no Gemini API key and preferably with internet disabled after installation.
5. Verify chapters prepare automatically, screen lock does not lose the durable job, and Home Play appears after whole-book preparation.
6. Check Burmese pronunciation and high-speed intelligibility with a blind/TalkBack user.
7. Switch explicitly to Gemini and confirm existing natural-voice behavior/cache still works.
8. Reboot during preparation and confirm WorkManager recovery.

## Future possibility

If eSpeak Burmese quality is acceptable, consider a separate properly licensed `WoW Burmese Voice` Android TTS companion so TalkBack, browsers, ebook readers and other apps can use Burmese system-wide—not only WoW Audio.
