# WoW Audio v1.1 — Fast Natural Myanmar Audio

## Product contract

The user experience must be: import EPUB/TXT -> press Play -> hear Burmese quickly.
No per-chapter Generate button. No waiting for the whole book. Previously heard audio must remain seekable.

## Non-negotiable deployment rule

WoW Audio does not operate an owner-managed Railway/server/cloud TTS proxy.
There must be no owner API account that needs routine switching, quota maintenance, secret rotation, or manual intervention for ordinary users.

The production design is device-first: each phone downloads a Myanmar voice pack once and runs speech locally. Voice-pack files may be hosted as static versioned downloads; static hosting contains no credentials and executes no user requests.

## Natural voice candidate

The first HQ candidate is F5-Myanmar-TTS v2, because it is Burmese-specific and trained for substantially more natural cadence and pronunciation than the MMS fallback. Its published FP16 checkpoint is large, so WoW Audio must not ship it inside the base APK.

The engineering path is:
1. Convert/pin the Burmese checkpoint to a mobile ONNX/ORT-compatible graph.
2. Evaluate FP16 and quantized variants.
3. Use ONNX Runtime execution providers (NNAPI/QNN where usable; CPU fallback).
4. Download the selected voice pack once into app-private storage with SHA-256 verification.
5. Keep a versioned voice manifest so upgrades are automatic and rollback-safe.

The HQ voice cannot become the default until it passes real Android benchmarks. Target gates:
- first audible speech <= 3 seconds on a representative mid-range Android phone after model warmup,
- sustained generation at or faster than playback (RTF <= 1) so look-ahead can prevent gaps,
- no process crash under long-book use,
- acceptable Burmese pronunciation and naturalness in real listening tests.

If F5 cannot meet those gates on ordinary phones, it remains an optional HQ pack rather than forcing a slow experience. Other on-device candidates can be evaluated behind the same VoiceEngine interface without rewriting the player.

## Fast fallback

The existing MMS Burmese model becomes a Lite/emergency fallback only. It is not the quality target. Production should make it a downloadable fallback rather than inflating the base APK.

## Latency strategy

- On import, immediately prepare the first short segment in the background.
- Play uses the prepared segment as soon as possible.
- While segment N is playing, synthesize N+1, N+2 and N+3 ahead.
- Never synthesize only after playback has already stopped.
- Persist generated segments so replay never requires regeneration.
- Warm the model once per reading session instead of recreating it for every segment.

## Audiobook cache and timeline

Speech cache is app-private and keyed by book/text hash + voice-pack version + voice/style + rate.
The player must provide:
- Play / Pause,
- exact resume: segment plus playback milliseconds,
- 15-second back / forward,
- previous / next segment,
- replay of already heard audio without regeneration,
- background and lock-screen controls,
- seamless continuation through cached/look-ahead segments.

A played segment is never immediately deleted. Cache eviction is storage-aware LRU and never evicts the current segment or the nearby rewind window.

## Voice-pack downloads

The APK stays small. Voice packs are static downloadable artifacts, not a backend service.
Every pack must have:
- immutable version,
- SHA-256 digest,
- license/attribution metadata,
- minimum app/runtime version,
- atomic download/install,
- rollback to last-known-good pack.

## Accessibility

TalkBack-first controls, large touch targets and predictable focus order. The everyday UI remains Add Book, Play/Resume, Pause, 15s Back, 15s Forward and Delete. Model/runtime details stay out of the normal reading flow.
