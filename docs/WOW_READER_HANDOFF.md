# WoW Reader → WoW Audio handoff

## Intent contract

WoW Reader should launch an explicit intent targeting `com.whisper.wowaudio`:

- action: `com.whisper.wowaudio.action.OPEN_BOOK`
- data: read-only `content://` URI from WoW Reader FileProvider
- type: `application/epub+zip` for EPUB, `application/pdf` for PDF
- flags: `Intent.FLAG_GRANT_READ_URI_PERMISSION`
- clipData: include the same URI so Android propagates the grant consistently
- optional extras:
  - `wow_book_title`
  - `wow_book_author`
  - `wow_source_app` = `com.whisper.wowreader`

WoW Audio must resolve the display name through `ContentResolver`, copy the stream into app-private storage, and then release all dependence on the temporary URI grant.

## UX contract

WoW Reader shows `Open in WoW Audio` from the existing per-book actions menu. Do not add a persistent reader toolbar or bottom navigation element for this handoff.

If WoW Audio is not installed, WoW Reader should show a clear local message and must not silently send the file to another app.

## Supported files

First integration contract: EPUB and PDF. The audiobook implementation can prioritize EPUB narration first; PDF support may initially import the book and mark narration support as pending.
