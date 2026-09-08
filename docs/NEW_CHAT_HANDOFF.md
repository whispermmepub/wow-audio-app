# WoW Audio — New Chat Handoff

Use this repository as the source of truth:

`whispermmepub/wow-audio-app`

## Current starter state

- Android application id: `com.whisper.wowaudio`
- minSdk 23, target/compile SDK 36
- Current starter version: `0.1.0` / versionCode `1`
- CI builds debug + unsigned release and runs release lint.
- The app already receives a book from WoW Reader using:
  - action `com.whisper.wowaudio.action.OPEN_BOOK`
  - EPUB MIME `application/epub+zip`
  - PDF MIME `application/pdf`
  - temporary read-only `content://` grant
- Incoming books are immediately copied into WoW Audio private `files/library` storage, preserving the display filename when possible.
- The current screen is only a starter/receiver UI. AI narration is NOT implemented yet.

## WoW Reader side

WoW Reader integration is prepared separately on branch:

`whispermmepub/wow-reader-lab` → `feature/v61-wow-audio-handoff`

Reader v61 / 2.19.1 adds `🎧 Open in WoW Audio` to the existing per-book action popup for EPUB/PDF. It does not add a persistent reader bottom bar or disturb the existing reading UI. It sends title + author metadata and a temporary read-only FileProvider URI specifically to package `com.whisper.wowaudio`.

Keep this intent contract stable unless both apps are updated together.

## Product direction

Build WoW Audio as a separate premium audiobook app. UI/UX quality is a core requirement: modern, polished, clean, smooth, visually strong, and comparable to the approved mockups from the prior conversation. Avoid generic Android demo UI.

Primary use case:

**Myanmar EPUB → natural Myanmar AI audiobook**

### TTS model strategy

- BYOK only: user provides their own Gemini API key.
- No shared WoW API key, shared quota, or owner-paid WoW TTS service.
- Never commit or upload user API keys.
- Store API keys device-locally and exclude them from app/library backup flows where appropriate.
- Before implementation, verify the current Google/Gemini TTS model names, API method, Burmese support, voice list, quotas, limits, and pricing from official Google docs; these can change.
- Android/Google on-device system TTS should NOT be assumed to provide Burmese.

### Required v1 audiobook features

1. Import/open EPUB from WoW Reader and normal Android file picker/share flows.
2. Parse EPUB metadata, cover, spine/chapters, and readable text.
3. Myanmar speech text normalization before TTS:
   - Myanmar punctuation and pauses
   - Myanmar digits/numbers/dates/times/percentages
   - common abbreviations
   - mixed Myanmar/English names and acronyms where practical
   - remove hidden/navigation/HTML noise
4. Gemini TTS BYOK setup:
   - API key entry
   - test connection
   - voice picker using voices actually supported by the live API
   - preview/sample voice
5. Narration styles such as Natural, Warm, Calm, Storyteller, Dramatic, Soft, Bedtime, plus Custom style prompt if supported.
6. Playback speed controls.
7. Read current chapter or whole book continuously across chapters.
8. Pre-generate/queue upcoming chunks so playback does not pause between normal chunks/chapters.
9. Sentence-follow highlighting synchronized to the currently playing chunk/sentence.
10. Background playback + Android MediaSession/notification/lock-screen controls.
11. 15-second rewind/forward, previous/next chapter, sleep timer, and playback speed.
12. Offline listening by caching/downloading generated audio. Generated audio should be keyed by book/text/voice/style/settings so unchanged audio is reused instead of repeatedly consuming API quota.
13. Library/Home UX: Continue Listening, Imported Books, Downloaded/Offline, recent books.
14. Book detail: cover, title, author, chapter list, narration/download state.
15. Proper error/retry UX for invalid key, quota exhausted, network loss, unsupported content, and interrupted whole-book generation.

## Future — do not block v1 on this

- Custom/My Voice for Burmese narration if a suitable licensed/allowed voice-cloning path becomes available.
- Offline native Myanmar TTS model only when licensing, quality, app size, and device performance are acceptable.
- PDF narration can follow after EPUB is excellent; the current handoff contract already permits PDF import.

## UI/UX principles

- Premium audiobook-first design, not a copy of WoW Reader.
- No permanent clutter over reading text.
- Large, attractive Now Playing screen with cover art and clear primary controls.
- Smooth sheets, cards, transitions, progress states, download states, skeleton/loading states.
- Excellent Myanmar typography.
- Light/dark themes should be intentional, not default system-looking screens.
- The WoW Reader handoff should feel instant: select `Open in WoW Audio` → WoW Audio opens → imported book appears ready for narration setup/playback.

## Development safety

- Do feature work on branches; do not casually overwrite stable `main` once the app becomes usable.
- Do not modify WoW Reader unless explicitly required for the cross-app contract.
- Keep signing keys, API keys, OAuth credentials, and recovery material out of GitHub.
- Keep CI artifact retention short to avoid Actions storage buildup.

## First task in a new chat

Read this handoff and the current repository, audit the starter, then build the first real WoW Audio feature branch beginning with a polished Library + Book Detail + EPUB parsing/import flow while preserving the working WoW Reader handoff receiver. Continue through BYOK Gemini TTS and playback only after the import/data model is stable.
