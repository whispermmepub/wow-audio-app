from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    s = p.read_text(encoding="utf-8")
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    p.write_text(s.replace(old, new), encoding="utf-8")


# v7 final-pass goal:
# - never send stray EPUB n/N artefacts to Nilar/Thiha
# - make punctuation/long-paragraph spacing breathe more like a human reader
# - preserve the neural voices' own timbre instead of over-processing pitch
# - invalidate old speech cache so the new cleanup/tuning is actually heard

tts = "app/src/main/java/com/whisper/wowaudio/TtsText.java"
replace_once(
    tts,
    "    private static final int FIRST_MAX_CHARS = 140;\n    private static final int NEXT_MAX_CHARS = 215;\n",
    "    private static final int FIRST_MAX_CHARS = 132;\n    private static final int NEXT_MAX_CHARS = 190;\n",
)
replace_once(
    tts,
    "            int end = findBreak(normalized, start, limit, first ? 32 : 54);",
    "            int end = findBreak(normalized, start, limit, first ? 28 : 46);",
)
replace_once(
    tts,
    '''        // EPUB/HTML conversion noise seen in some books: n, N, "n n", "။ n", "(n)" etc.\n        // Remove a Latin n only when it is a standalone token. ASCII words/numbers are protected.\n        s = s.replaceAll("(?im)^[ \\\\t]*(?:[nN][ \\\\t]+){1,}[nN][ \\\\t]*$", "")\n                .replaceAll("(?im)^[ \\\\t]*[nN][ \\\\t]*$", "")\n                .replaceAll("(?i)(?<![A-Za-z0-9])n(?![A-Za-z0-9])", " ");\n\n        return clean(s);\n''',
    '''        // EPUB/HTML conversion noise seen in real books can be n, N, nn, NNN, n n, /n, \\ n,\n        // punctuation-wrapped n, or an n glued directly to Myanmar text. None of those may reach TTS.\n        // Normal English words remain protected because an artefact run is removed only when it is\n        // NOT touching another ASCII letter/digit.\n        s = s.replaceAll("(?im)^[ \\\\t]*(?:[nN]+[ \\\\t]*)+$", "")\n                .replaceAll("(?i)(?<![A-Za-z0-9])(?:[\\\\\\\\/|]+[ \\\\t]*)?[nN]+(?:[ \\\\t]+[nN]+)*(?:[ \\\\t]*[\\\\\\\\/|]+)?(?![A-Za-z0-9])", " ")\n                .replaceAll("(?i)(?<![A-Za-z0-9])[nN]+(?![A-Za-z0-9])", " ");\n\n        return clean(s);\n''',
)

prosody = "app/src/main/java/com/whisper/wowaudio/BurmeseProsody.java"
replace_once(
    prosody,
    'static final String RENDER_VERSION = "burmese-prosody-v6-burmese-breath";\n    // Legacy CI marker: burmese-prosody-v5-human-cadence',
    'static final String RENDER_VERSION = "burmese-prosody-v7-final-human-reader";\n    // Legacy CI marker: burmese-prosody-v5-human-cadence',
)
replace_once(
    prosody,
    'return new Profile(1.0f, 1.0f, 0, 96, "Natural");',
    'return new Profile(1.0f, 1.0f, 0, 102, "Natural");',
)
replace_once(
    prosody,
    '''        if (sentenceEnd) expressivePause = Math.max(expressivePause, 290);\n        if (phraseEnd) expressivePause = Math.max(expressivePause, 175);\n        if (softBreath) expressivePause = Math.max(expressivePause, 125);\n        if (paragraph) expressivePause = Math.max(expressivePause, 440);\n\n        // Preserve the native Nilar/Thiha timbre. Large MediaPlayer pitch shifts were one of the\n        // main things that made the voices sound synthetic, so v6 gets expression mostly from\n        // timing, phrasing, speed and restrained gain instead of artificial pitch bending.\n        if (geminiLike) {\n            speed = clamp(speed, 0.920f, 1.065f);\n            pitch = clamp(pitch, 0.970f, 1.035f);\n            expressiveGain = clamp(expressiveGain, 0, 210);\n            expressivePause = clamp(expressivePause, 80, 520);\n        } else {\n            speed = clamp(speed, 0.940f, 1.045f);\n            pitch = clamp(pitch, 0.980f, 1.025f);\n            expressiveGain = clamp(expressiveGain, 0, 160);\n            expressivePause = clamp(expressivePause, 70, 520);\n        }\n''',
    '''        if (sentenceEnd) expressivePause = Math.max(expressivePause, 320);\n        if (phraseEnd) expressivePause = Math.max(expressivePause, 185);\n        if (softBreath) expressivePause = Math.max(expressivePause, 135);\n        if (paragraph) expressivePause = Math.max(expressivePause, 480);\n\n        // Final pass: human narration gets most of its expression from phrasing, breath and timing.\n        // Keep playback pitch extremely close to the neural voice's original timbre so Nilar/Thiha\n        // do not acquire a synthetic "processed" sound.\n        if (geminiLike) {\n            speed = clamp(speed, 0.925f, 1.055f);\n            pitch = clamp(pitch, 0.980f, 1.020f);\n            expressiveGain = clamp(expressiveGain, 0, 195);\n            expressivePause = clamp(expressivePause, 85, 560);\n        } else {\n            speed = clamp(speed, 0.945f, 1.040f);\n            pitch = clamp(pitch, 0.985f, 1.015f);\n            expressiveGain = clamp(expressiveGain, 0, 150);\n            expressivePause = clamp(expressivePause, 75, 560);\n        }\n''',
)
replace_once(
    prosody,
    '''    private static int boundaryPause(String raw) {\n        if (hasStructuralLineBreak(raw)) return 440;\n        String value = stripClosingQuotes(raw.trim());\n        if (hasEllipsis(value)) return 360;\n        if (value.endsWith("?")) return 300;\n        if (value.endsWith("!")) return 255;\n        if (value.endsWith("။") || value.endsWith(".")) return 290;\n        if (value.endsWith("၊") || value.endsWith(",") || value.endsWith(";") || value.endsWith(":")) return 175;\n        return 90;\n    }\n''',
    '''    private static int boundaryPause(String raw) {\n        if (hasStructuralLineBreak(raw)) return 480;\n        String value = stripClosingQuotes(raw.trim());\n        if (hasEllipsis(value)) return 390;\n        if (value.endsWith("?")) return 320;\n        if (value.endsWith("!")) return 270;\n        if (value.endsWith("။") || value.endsWith(".")) return 320;\n        if (value.endsWith("၊") || value.endsWith(",") || value.endsWith(";") || value.endsWith(":")) return 185;\n        return 96;\n    }\n''',
)

edge = "app/src/main/java/com/whisper/wowaudio/EdgeMyanmarTtsClient.java"
replace_once(
    edge,
    '''        return out.toString()\n                .replaceAll("(?i)(?<![A-Za-z0-9])n(?![A-Za-z0-9])", " ")\n                .replaceAll("\\\\s+", " ")\n                .trim();\n''',
    '''        return out.toString()\n                // Last-line defence: even if another caller bypasses TtsText, no standalone/run\n                // n/N artefact may be sent to the online Burmese voice. English words are protected.\n                .replaceAll("(?i)(?<![A-Za-z0-9])(?:[\\\\\\\\/|]+\\\\s*)?[nN]+(?:\\\\s+[nN]+)*(?:\\\\s*[\\\\\\\\/|]+)?(?![A-Za-z0-9])", " ")\n                .replaceAll("(?i)(?<![A-Za-z0-9])[nN]+(?![A-Za-z0-9])", " ")\n                .replaceAll("\\\\s+", " ")\n                .trim();\n''',
)

cache = "app/src/main/java/com/whisper/wowaudio/SpeechCache.java"
replace_once(
    cache,
    '''        String signature = sha256(voice + "\\n" + String.format(Locale.US, "%.2f", speed)\n                + "\\n" + text);\n''',
    '''        // Include render version so cleanup/prosody upgrades never reuse stale generated audio.\n        String signature = sha256(BurmeseProsody.RENDER_VERSION + "\\n" + voice + "\\n"\n                + String.format(Locale.US, "%.2f", speed) + "\\n" + text);\n''',
)

text_test = "app/src/test/java/com/whisper/wowaudio/TtsTextTest.java"
replace_once(
    text_test,
    '            assertTrue(chunks.get(i).length() <= 220);',
    '            assertTrue(chunks.get(i).length() <= 195);',
)
anchor = '''    @Test public void preservesNormalEnglishInsideMyanmarText() {\n        String input = "မြန်မာစာနဲ့ China Dream, Internet နဲ့ Nilar ဆိုတဲ့ English စကားလုံးတွေကို ထိန်းထားမယ်။";\n        String cleaned = TtsText.normalizeForSpeech(input);\n        assertTrue(cleaned.contains("China Dream"));\n        assertTrue(cleaned.contains("Internet"));\n        assertTrue(cleaned.contains("Nilar"));\n    }\n'''
addition = '''\n    @Test public void removesEveryStandaloneOrRepeatedNArtifactShape() {\n        String input = "အစ။ n nn NNN /n \\\\ n n n မြန်မာစာn ၊N။ အဆုံး။";\n        String cleaned = TtsText.normalizeForSpeech(input);\n        assertFalse(cleaned.matches("(?s).*(?<![A-Za-z0-9])[nN]+(?![A-Za-z0-9]).*"));\n        assertTrue(cleaned.contains("အစ"));\n        assertTrue(cleaned.contains("မြန်မာစာ"));\n        assertTrue(cleaned.contains("အဆုံး"));\n    }\n'''
replace_once(text_test, anchor, anchor + addition)

prosody_test = "app/src/test/java/com/whisper/wowaudio/BurmeseProsodyTest.java"
replace_once(prosody_test, '        assertTrue(p.pauseAfterMs >= 290);', '        assertTrue(p.pauseAfterMs >= 320);')
replace_once(prosody_test, '        assertTrue(p.pauseAfterMs >= 440);', '        assertTrue(p.pauseAfterMs >= 480);')
replace_once(prosody_test, '        assertTrue(p.pauseAfterMs >= 175);', '        assertTrue(p.pauseAfterMs >= 185);')
replace_once(prosody_test, '        assertTrue(p.pauseAfterMs >= 125);', '        assertTrue(p.pauseAfterMs >= 135);')
replace_once(
    prosody_test,
    '        assertTrue(p.pitchMultiplier >= 0.98f && p.pitchMultiplier <= 1.025f);',
    '        assertTrue(p.pitchMultiplier >= 0.985f && p.pitchMultiplier <= 1.015f);',
)

print("Applied Nilar/Thiha v7 final human-reader tuning and hard n-noise filter")
