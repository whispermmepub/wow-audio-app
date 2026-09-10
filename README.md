# WoW Audio

A free, non-commercial, Myanmar-first accessible audiobook reader.

## Product rule

**Add an EPUB/TXT → tap Play → hear natural Burmese.**

WoW Audio is designed for a simple TalkBack-first daily flow. Users do not need to generate a whole book, configure an API key, run a server, or maintain a Railway account.

## WoW Audio v1.1

- Natural Myanmar voices: **Nilar** and **Thiha**
- Nilar is the default; voice can be changed with one tap
- Direct online natural speech for uncached text; no user account/API key setup
- Persistent per-book audio cache — already-heard speech is not deleted
- Exact resume using segment + millisecond position
- Back 15 seconds / Forward 15 seconds
- Pause / Resume / Stop
- Prefetch the next three speech segments while listening
- Bundled offline Burmese MMS/VITS voice as emergency fallback
- EPUB and UTF-8 TXT private library
- Telegram / File Manager **Open with**
- Share and SEND_MULTIPLE import
- Delete Book removes WoW Audio's private copy, cached speech and saved position, but never the original external file
- Background/notification playback controls
- Navy premium visual family shared with WoW Reader

## Natural voice reliability

The Nilar/Thiha path currently uses the Microsoft Edge Read Aloud consumer WebSocket protocol directly from the app. It is an unofficial consumer endpoint rather than the supported Azure Speech API, so it can change without notice. The provider is isolated in `EdgeMyanmarTtsClient` and the bundled offline voice remains available as fallback.

No WoW Audio owner backend or Railway service is required.

## Licensing

WoW Audio is free/non-commercial. The bundled offline fallback uses Sherpa-ONNX and the Meta MMS Burmese model; see `THIRD_PARTY_NOTICES.md` and the in-app notice for attribution and applicable licenses.
