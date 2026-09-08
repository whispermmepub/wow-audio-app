# WoW Audio — New Chat Handoff

Use this repository as the source of truth:

`whispermmepub/wow-audio-app`

## Current repository state

- Android application id: `com.whisper.wowaudio`
- minSdk 23, target/compile SDK 36
- Current release identity: `1.2.0` / versionCode `6`
- Stable WoW Reader action: `com.whisper.wowaudio.action.OPEN_BOOK`
- Reader EPUB/PDF MIME contract: `application/epub+zip` / `application/pdf`
- Incoming Reader `content://` URIs are copied immediately into private `files/library` storage.
- Reader title/author extras remain `wow_book_title` / `wow_book_author`.
- CI builds debug + release, runs release lint, verifies Reader handoff/accessibility/resilient-generation contracts, verifies APK identity, and can verify the permanent update certificate when signing secrets are configured.

## Product goal

WoW Audio is designed first for simple, accessible audiobook use, especially for Myanmar blind and low-vision readers.

Primary flow:

`One-time API/voice setup → Add EPUB → automatic background preparation → Home → Play / Resume`

A normal user should not need to generate chapter-by-chapter. Per-chapter controls and advanced narration controls are secondary tools only.

## Real-device Reader integration already verified

The original WoW Reader v61 → WoW Audio handoff was tested on a real device:

1. Open the Reader per-book actions.
2. Tap `🎧 Open in WoW Audio`.
3. WoW Audio opens.
4. The EPUB is received and copied.
5. Burmese title, author and filename arrive correctly.

Preserve this cross-app contract. Do not modify WoW Reader unless both apps truly need a coordinated contract change.

Historical Reader integration branch:

`whispermmepub/wow-reader-lab` → `feature/v61-wow-audio-handoff`

## Accessible library and import

- Screen-reader-first Home with a simple focus order.
- Add Book is a primary action.
- Continue Listening / Resume is a primary action when available.
- Home Play appears when the whole book is ready for uninterrupted offline playback.
- More options contains advanced/chapter controls.
- TalkBack-friendly status text and accessibility headings.
- Standalone ACTION_VIEW import from Files / Downloads / Telegram.
- Share receiver supports ACTION_SEND, ACTION_SEND_MULTIPLE and ClipData fallbacks.
- Private app-library copy pipeline.
- EPUB ZIP validation before accepting imports.
- SHA-256 duplicate detection.
- Original filename preservation with collision-safe naming.

## EPUB data model

- OPF metadata parsing.
- Cover extraction.
- Spine-order readable chapter extraction.
- Robust relative EPUB path handling including `../`, fragments and query components.
- EPUB3 NAV chapter titles.
- EPUB2 NCX chapter titles.
- Heading and numbered fallback titles.
- Persistent `library-index.json` metadata.
- Reader-supplied Burmese title/author persists across reloads.

## BYOK Gemini narration

- Model: `gemini-3.1-flash-tts-preview`.
- Interactions endpoint: `https://generativelanguage.googleapis.com/v1beta/interactions`.
- Request schema uses audio response format and speech configuration.
- `Api-Revision: 2026-05-20` is sent.
- User API key is encrypted with Android Keystore AES-GCM and remains device-local.
- Key storage is excluded from Android backup/device transfer.
- No shared WoW owner-paid API key or quota.
- API key test + voice preview flow.
- 30 Gemini voices.
- Natural/Warm/Calm/Storyteller/Dramatic/Soft/Bedtime presets plus Custom direction.
- Myanmar/ASCII number, date, time, percentage and punctuation preparation is applied only to the speech copy; original EPUB text remains unchanged.

## v1.2 resilient automatic audiobook generation

This is a core product contract. Do not regress it to a UI-thread or one-shot whole-book loop.

### Durable queue

- `GenerationQueueStore` persists a job per imported book.
- Each job stores state, next chapter, total chapters, retry time, failure count and revision.
- Chapter completion is checkpointed after every generated/cached chapter.
- v1.1/pre-v1.2 partial books without queue records are automatically reconciled on upgrade/app open.
- Already-cached chapters are skipped, so a book with chapter 1 complete resumes at the next missing chapter.
- API key save queues existing library books.
- New indexed books auto-queue when an API key exists.
- Voice/style changes create a new revision and re-prepare the library without mixing old and new narration settings.

### Memory/storage safety

- Generated PCM is streamed to a temporary WAV on disk instead of accumulating a whole long chapter in RAM.
- WAV headers are finalized and committed atomically.
- Follow-text sidecar metadata is written only after successful audio generation.
- Low-memory failures are checkpointed and retried.
- Low-storage conditions pause generation instead of corrupting cache files.

### Retry and quota behavior

- Gemini requests are globally paced inside the process to reduce burst rate-limit failures.
- Retry-After/server hints are honored when available.
- Transient network/408/429/5xx failures retry in-request first.
- Persistent 429/quota failures checkpoint the current chapter and use longer durable backoff.
- Durable rate-limit backoff grows through approximately 5/15/30/60/120/240 minute windows.
- Network loss pauses and resumes automatically.
- Invalid/unauthorized API keys stop automatic retries and surface setup attention instead of burning requests.
- A real provider daily/project quota cannot be bypassed; the queue must remain intact and continue later.

### Foreground + WorkManager hybrid

- User-visible/user-initiated imports use `NarrationGenerationService` for fast foreground generation.
- A partial wake lock is held only while active chapter work is running.
- Generation engine access is process-serialized so foreground service and WorkManager cannot write the same chapter concurrently.
- Modern Android can reject background foreground-service starts; `NarrationWorkScheduler` / `NarrationRecoveryWorker` are the durable fallback.
- WorkManager requires connected network and non-low storage before recovery work runs.
- Worker continuation is appended instead of replacing/cancelling the currently running worker.
- BOOT_COMPLETED and MY_PACKAGE_REPLACED never directly start a dataSync foreground service; they reconcile/schedule WorkManager recovery.
- Android 15+ dataSync foreground-service timeout checkpoints state, stops cleanly, and hands recovery to WorkManager.
- App foreground/open still prefers the fast foreground drain when work is due.

## Offline playback

- Deterministic cache key includes cache version, book/chapter text, voice and style.
- Continuous playback across consecutive cached chapters.
- Device-local chapter/position resume state.
- Foreground media playback service.
- MediaSession / notification / lock-screen controls.
- Play/pause, ±15-second seek, previous/next chapter.
- 0.8×–2.0× speed.
- 15/30/45/60/90-minute sleep timer.

## Follow Text

- Now Playing / Follow Text screen with book context, progress and controls.
- Previous/current/next text context.
- Approximate sentence-level highlighting.
- Timing metadata is derived from measured generated audio duration and proportional sentence weighting.
- Gemini does not provide exact word timestamps through this flow; do not claim exact word synchronization.

## Permanent update signing

A permanent WoW Audio update certificate was established starting with the stable v1.1.0 line.

Expected signer SHA-256:

`5F:58:AB:46:3C:3A:43:27:5D:D7:68:FB:3B:DB:2A:AE:F3:88:B5:C1:8A:4D:60:76:B0:C5:33:9B:4A:EE:0F:E1`

GitHub signing secret names expected by CI:

- `WOW_AUDIO_KEYSTORE_B64`
- `WOW_AUDIO_STORE_PASSWORD`
- `WOW_AUDIO_KEY_ALIAS`
- `WOW_AUDIO_KEY_PASSWORD`

Never commit the keystore or credentials. If CI secrets are unavailable, CI uploads an unsigned release package and final signing must use the preserved private update key offline. Future APKs must use this same certificate for update-install compatibility.

## Validation boundary

CI can verify:

- Android compilation
- debug/release builds
- release lint
- v1.2 APK identity
- static Reader handoff contract
- resilient generation architecture contracts
- permanent signer certificate when secrets are configured

The Reader → Audio import contract has prior real-device verification. Live Gemini narration, provider quota behavior, OEM background behavior and TalkBack usability still need real-device testing with a user-owned API key. Never put the user's API key in GitHub.

## Security and development safety

- Never commit API keys, signing keys, OAuth credentials or recovery secrets.
- Keep user Gemini keys device-local.
- Keep CI artifact retention short.
- Do feature work on branches and merge only after green CI.
- Preserve the Reader receiver contract while refactoring.
- Do not remove durable generation checkpoints or WorkManager recovery for a simpler one-shot loop.
- Do not claim live provider behavior is verified only because CI is green.

## Next task in a future chat

1. Read this file first and inspect `main` before changing anything.
2. Preserve the v1.2 durable queue + FGS/WorkManager hybrid architecture.
3. Validate automatic whole-book generation on a real device with a user-owned Gemini key.
4. Test: import while foregrounded, screen locked, temporary network loss, 429 quota wait, app process kill, device reboot and app update.
5. Fix any real-device/OEM-specific issues before expanding into PDF narration, custom voice or exact upstream timestamps.
