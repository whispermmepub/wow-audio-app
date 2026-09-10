# WoW Audio v1.1 — Fast Natural Myanmar Audio

## Product contract

The user experience must be: import EPUB/TXT -> press Play -> hear Burmese quickly.
No per-chapter generate buttons, no waiting for the whole book, and no deleting already-generated audio immediately after playback.

## Primary voice

Online neural Burmese is the primary path. The first implementation target is Azure Speech standard Burmese neural voices:
- my-MM-NilarNeural (default)
- my-MM-ThihaNeural (alternate)

Cloud credentials must never be shipped in the APK. The Android app talks to a small WoW Audio backend; the backend holds provider credentials in environment secrets.

## Latency strategy

- First request: very small sentence/phrase so first sound starts quickly.
- Once playback starts, prefetch the next 2-3 larger text segments in parallel.
- Reuse HTTP connections.
- Use compressed speech audio over the network.
- Never block playback on whole-book synthesis.

## Player contract

Generated online speech is persistent app-private cache, keyed by normalized text hash + voice + rate.
The player must provide:
- Play / Pause
- exact resume (segment + milliseconds)
- 15-second back / forward
- previous / next segment
- seek into already cached speech
- background and lock-screen controls
- seamless progression through cached/prefetched segments

A segment is not deleted after playback. Cache eviction is LRU/storage-aware and never removes the currently playing segment.

## Offline fallback

The existing MMS Burmese model is fallback only. It is not allowed to block the natural online path. Production can make the offline model an optional download to reduce APK size.

## Privacy

Online mode sends only the short text segment needed for speech synthesis. The backend must not log book text and must not persist book text or generated audio. Audio is cached only on the user's device.

## Accessibility

TalkBack-first controls, large touch targets, concise labels, predictable focus order. Day-to-day UI remains Add Book and Play/Resume; provider setup belongs outside the primary reading flow.
