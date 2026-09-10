# WoW Audio v1.1 — Fast Natural Myanmar Audio

## Product contract

The user experience is intentionally simple:

**Import EPUB/TXT -> choose Nilar or Thiha if desired -> press Play -> hear Burmese quickly.**

No chapter-generation screen, no owner server, no Railway, no user account, and no API-key setup in the reading flow.

## Natural voice path

The primary path uses the Microsoft Edge Read Aloud consumer WebSocket protocol directly from the Android app:

- `my-MM-NilarNeural` — default
- `my-MM-ThihaNeural` — alternate

This is an unofficial consumer endpoint, not the supported Azure Speech API. It requires Internet for uncached speech and can change without notice. The protocol is isolated in `EdgeMyanmarTtsClient` so it can be repaired/replaced without rewriting the player.

WoW Audio does **not** run or require a Railway/backend service and does not ship an owner Azure API key.

## Offline fallback

The bundled Meta MMS Burmese VITS model through Sherpa-ONNX remains only as an emergency fallback when natural online speech is unavailable. WoW Audio is free and non-commercial and includes attribution for the offline model/runtime.

## Latency strategy

- First segment is intentionally short so the first sound can start quickly.
- Later segments are larger and use natural Burmese sentence boundaries.
- While one segment plays, the next three natural segments are prefetched.
- Natural speech uses compressed 24 kHz / 48 kbps mono MP3.
- Playback never waits for the whole book to be synthesized.
- A temporary online failure is backed off instead of repeatedly blocking every segment.

## Persistent audiobook cache

Generated speech is cached under the private directory for each imported book. Cache identity includes normalized segment text, voice and speed.

Cached audio is **not deleted after playback**. This is required for rewind, forward seeking and instant replay of already-heard material.

Deleting a book deletes:

- WoW Audio's private EPUB/TXT copy
- extracted text/metadata
- generated speech cache
- saved reading position

The user's original external file is never deleted.

## Player contract

- Play / Pause / Resume
- exact resume: segment + milliseconds
- Back 15 seconds
- Forward 15 seconds
- seeking across cached segment boundaries
- playback position saved about once per second
- background foreground-service playback
- notification controls: Back 15s / Pause-Resume / Forward 15s / Stop
- Telegram/File Manager Open with
- Share and SEND_MULTIPLE import

## Accessibility

WoW Audio is designed TalkBack-first:

- large touch targets
- concise content descriptions
- predictable control order
- no repeated status toast for every segment
- primary daily flow remains Add Book -> Play/Resume

## Reliability boundary

The Edge Read Aloud path has no public SLA. CI should exercise the current Nilar and Thiha voices when possible, but a passing CI does not guarantee Microsoft will keep the consumer protocol unchanged. The bundled offline voice prevents complete loss of reading when the online path is unavailable.
