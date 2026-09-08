# WoW Audio

Premium Myanmar audiobook companion for WoW Reader.

## Integration contract with WoW Reader

- Android application id: `com.whisper.wowaudio`
- WoW Reader sends a book as a one-time read-only `content://` URI with `FLAG_GRANT_READ_URI_PERMISSION`.
- Primary action: `com.whisper.wowaudio.action.OPEN_BOOK`
- Data/MIME: `application/epub+zip` and `application/pdf`.
- Receiver must copy the incoming URI into WoW Audio private app storage immediately; never depend on the temporary grant after import.
- Preserve the original display name when possible.
- Do not copy API keys or secrets between apps.

## Product direction

WoW Audio is a separate app/repository. It will use BYOK Gemini TTS, premium modern UI/UX, whole-book narration, sentence-follow highlighting, background playback, lock-screen controls, and offline generated audio. No shared WoW-hosted TTS quota/billing service is planned.

## Security

Never commit Gemini/API keys or signing secrets. User API keys must remain device-local and excluded from backups where appropriate.
