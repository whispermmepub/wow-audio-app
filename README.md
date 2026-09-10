# WoW Audio — Clean Rebuild

A simple Myanmar-first accessible reading app.

## Product rule

**Add a book → tap Play → hear it read aloud.**

No chapter generation workflow, no audio-cache preparation screen, and no required cloud account in the core experience.

## First clean build

- EPUB and UTF-8 TXT import
- direct Android TextToSpeech playback
- Myanmar locale request (`my-MM`)
- foreground reading service for screen-off playback
- Play / Pause / Resume
- persistent reading position
- delete books from the private library
- ACTION_VIEW / ACTION_SEND import
- TalkBack-friendly large controls

The app uses the Android TextToSpeech API. Actual Myanmar speech depends on an installed TTS engine that reports Myanmar support. Google Translate being able to speak Burmese does not guarantee that the Google Android system TTS engine exposes Burmese to third-party apps.

Old experiments are archived outside main. This rebuild starts with a new application signing line.