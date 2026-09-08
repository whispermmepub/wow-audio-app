# WoW Audio — New Chat Handoff

Use this repository as the source of truth:

`whispermmepub/wow-audio-app`

## Current verified state

- Android application id: `com.whisper.wowaudio`
- minSdk 23, target/compile SDK 36
- Current starter version: `0.1.0` / versionCode `1`
- CI builds debug + unsigned release and runs release lint successfully.
- The app already receives a book from WoW Reader using:
  - action `com.whisper.wowaudio.action.OPEN_BOOK`
  - EPUB MIME `application/epub+zip`
  - PDF MIME `application/pdf`
  - temporary read-only `content://` grant
- Incoming books are immediately copied into WoW Audio private `files/library` storage, preserving the display filename when possible.
- The current screen is only a starter/receiver UI. AI narration is NOT implemented yet.

### End-to-end handoff has been tested on a real device

The user installed WoW Reader v61 and the WoW Audio starter APK and verified this exact flow:

1. In WoW Reader, long-press/open the per-book action popup.
2. `🎧 Open in WoW Audio` appears alongside the existing book actions.
3. Tapping it opens WoW Audio.
4. WoW Audio displays `✓ Book received`.
5. The Burmese book title/author and EPUB filename arrive correctly.

This cross-app receiver/handoff is working and should be preserved while building the real audiobook app.

## WoW Reader side

WoW Reader integration is prepared separately on branch:

`whispermmepub/wow-reader-lab` → `feature/v61-wow-audio-handoff`

Reader v61 / 2.19.1 adds `🎧 Open in WoW Audio` to the existing per-book action popup for EPUB/PDF. It does not add a persistent reader bottom bar or disturb the existing reading UI. It sends title + author metadata and a temporary read-only FileProvider URI specifically to package `com.whisper.wowaudio`.

The v61 branch CI/build/lint/identity verification passed. Keep this intent contract stable unless both apps are updated together.

## Standalone import requirement

WoW Audio must also work independently of WoW Reader.

Add normal Android import/open paths so a user can:

- tap `Add Book` / `Import EPUB` inside WoW Audio and choose an `.epub` from the system file picker;
- use Android `Open with` / share/open flows from Files, Downloads, Telegram, etc.;
- import the file into WoW Audio private storage using the same safe import pipeline as the WoW Reader handoff.

WoW Reader is an optional companion, not a requirement for using WoW Audio.

EPUB is the priority for v1. PDF support may follow after EPUB narration is excellent, although the current handoff receiver already accepts PDF.

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

1. Import/open EPUB from WoW Reader and normal Android file picker/open/share flows.
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
- PDF narration can follow after EPUB is excellent.

## UI/UX principles

- Premium audiobook-first design, not a copy of WoW Reader.
- The current starter screen is NOT the target final design; it is only proof that handoff works.
- UI/UX must look intentionally designed, premium and modern, like the approved mockups from the prior conversation.
- Excellent Myanmar typography is mandatory.
- Home should feel like a real audiobook library, with strong cover presentation and clear hierarchy.
- Add Book/import should be obvious and quick.
- Book Detail should show cover, title, author, chapters, narration/download state, and a strong primary action.
- Now Playing should have large cover art, clear controls, voice/style/speed access, chapter navigation, sleep timer, and download/offline status.
- Smooth sheets, cards, transitions, progress states, download states, skeleton/loading states.
- Light/dark themes should be intentional, not default system-looking screens.
- No permanent clutter over reading text if a text-follow screen is used.
- The WoW Reader handoff should feel instant: select `Open in WoW Audio` → WoW Audio opens → imported book appears ready for narration setup/playback.

## Development safety

- Do feature work on branches; do not casually overwrite stable `main` once the app becomes usable.
- Do not modify WoW Reader unless explicitly required for the cross-app contract.
- Preserve the working receiver while refactoring the app architecture.
- Keep signing keys, API keys, OAuth credentials, and recovery material out of GitHub.
- Keep CI artifact retention short to avoid Actions storage buildup.
- Do not claim a feature works until it is built/tested.

## First task in a new chat

Read this handoff and audit the current repository before changing anything.

Then create the first real WoW Audio feature branch and build, in this order:

1. A polished premium Library/Home screen.
2. Standalone `Add Book / Import EPUB` using Android's file picker, while preserving the already-working WoW Reader receiver.
3. A proper EPUB import/data model: metadata, cover, spine, chapters, readable text, duplicate handling.
4. A polished Book Detail screen with chapter list and clear narration setup entry point.
5. Only after import/data/library are stable, implement BYOK Gemini TTS and playback.

Do not spend the first turn re-explaining the product. Inspect the repo and start doing the work.
