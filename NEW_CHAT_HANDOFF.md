# WoW Audio — New Chat Handoff

Use this file as the first source of truth when continuing WoW Audio work in a new ChatGPT conversation.

## Current stable candidate

- App: **WoW Audio**
- Application ID: `com.whisper.wowaudio`
- Version: **1.8.1**
- Version code: **14**
- Current tested branch: `fix/v1.8.1-voice-pause-boundaries`
- Tested head before this handoff file: `8035681a4adfc1290559c3e039d68203aaa18285`
- Successful CI run: `34688713088`

The v1.8.1 CI passed the offline Burmese TTS smoke test, Nilar/Thiha live voice smoke test, contract checks, unit tests, debug/release build, release lint, and APK identity checks.

## Permanent update signing identity

**Never create a replacement signing key for an ordinary update.** All update-install APKs from the permanent v1.4.0 baseline onward must use the same certificate described in `SIGNING_IDENTITY.md`.

Expected certificate SHA-256:

`5F:58:AB:46:3C:3A:43:27:5D:D7:68:FB:3B:DB:2A:AE:F3:88:B5:C1:8A:4D:60:76:B0:C5:33:9B:4A:EE:0F:E1`

Expected signer DN:

`CN=WoW Audio Update, O=Whisper of Words, C=MM`

The repository must never contain the private keystore, keystore base64, aliases, or passwords.

## Update-install release rule

For every future Android release:

1. Keep `applicationId 'com.whisper.wowaudio'` unchanged.
2. Increment `versionCode` above the currently installed release.
3. Build the release APK from the approved source.
4. Sign the APK with the permanent WoW Audio update keystore.
5. Verify the final APK with `apksigner verify --verbose --print-certs`.
6. Confirm the signer SHA-256 exactly matches the fingerprint above before giving the APK to users.
7. Users on a permanent-signed prior version should update directly; do not tell them to uninstall unless there is a demonstrated package/signature migration reason.

## GitHub Actions signing

`.github/workflows/build.yml` already supports automatic permanent-key signing when these repository Actions secrets exist:

- `WOW_AUDIO_KEYSTORE_B64`
- `WOW_AUDIO_STORE_PASSWORD`
- `WOW_AUDIO_KEY_ALIAS`
- `WOW_AUDIO_KEY_PASSWORD`

If those secrets are not configured, CI still builds/tests and uploads the unsigned release, but the permanent-signed update artifact is skipped. A new chat must not generate a new signing key as a workaround. Either use the owner's offline permanent signing kit or configure the four GitHub Actions secrets once.

## Current functional state

v1.8.x includes:

- Home library view modes: List, Small List, Grid, Small Grid.
- Nilar and Thiha Myanmar natural voices.
- Gemini TTS and Gemini-like expressive playback style.
- Playback speed, tone, volume boost, and narration-style controls.
- Burmese prosody handling for sentence endings, phrase commas, dialogue/emotion cues, and structural line breaks.
- v1.8.1 voice isolation: the selected Nilar/Thiha voice must remain authoritative for the session; do not silently substitute the other Edge voice.
- v1.8.1 pause behavior: Burmese `။`, `၊`, and structural line breaks are reading boundaries so headings/body text do not run together.

## Safe continuation checklist

Before editing in a new chat, fetch `main`, this file, `SIGNING_IDENTITY.md`, `app/build.gradle`, and `.github/workflows/build.yml`. Verify the latest stable version and CI status before changing code. Preserve the Nilar/Thiha stable synthesis path unless a real regression requires changing it.
