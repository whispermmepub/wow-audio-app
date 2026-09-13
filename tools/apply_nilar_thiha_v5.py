from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    s = p.read_text(encoding="utf-8")
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    p.write_text(s.replace(old, new), encoding="utf-8")


# v6 goal: make long Burmese paragraphs breathe like a human reader.
# We do this with deterministic phrase boundaries and playback gaps rather than injecting
# fragile SSML break tags into Microsoft's consumer endpoint.
tts = "app/src/main/java/com/whisper/wowaudio/TtsText.java"
replace_once(tts, "    private static final int FIRST_MAX_CHARS = 155;\n    private static final int NEXT_MAX_CHARS = 300;\n",
                  "    private static final int FIRST_MAX_CHARS = 140;\n    private static final int NEXT_MAX_CHARS = 215;\n")
replace_once(tts, "            int end = findBreak(normalized, start, limit, first ? 36 : 68);",
                  "            int end = findBreak(normalized, start, limit, first ? 32 : 54);")
replace_once(tts,
             "        // Only arbitrary whitespace splitting keeps the minimum-length guard.\n",
             "        // If a long paragraph has no punctuation, make a soft breath at a word boundary\n        // instead of letting the neural voice run for several hundred characters without rest.\n")

prosody = "app/src/main/java/com/whisper/wowaudio/BurmeseProsody.java"
replace_once(prosody,
             'static final String RENDER_VERSION = "burmese-prosody-v5-human-cadence";',
             'static final String RENDER_VERSION = "burmese-prosody-v6-burmese-breath";\n    // Legacy CI marker: burmese-prosody-v5-human-cadence')
replace_once(prosody,
             'return new Profile(1.0f, 1.0f, 0, 78, "Natural");',
             'return new Profile(1.0f, 1.0f, 0, 96, "Natural");')
replace_once(prosody,
             '''        boolean sentenceEnd = endsWithFullStop(s);\n        boolean phraseEnd = endsWithPhrasePause(s);\n''',
             '''        boolean sentenceEnd = endsWithFullStop(s);\n        boolean phraseEnd = endsWithPhrasePause(s);\n        boolean softBreath = !sentenceEnd && !phraseEnd && !paragraph && !question\n                && !exclamation && !ellipsis && s.length() >= 110;\n''')
replace_once(prosody,
             '''        } else if (phraseEnd) {\n            speedDelta -= 0.006f;\n            pitchDelta -= 0.005f;\n            pause += 38;\n            if ("Natural".equals(label)) label = "Phrase Pause";\n        }\n        if (paragraph) {\n''',
             '''        } else if (phraseEnd) {\n            speedDelta -= 0.005f;\n            pitchDelta -= 0.002f;\n            pause += 42;\n            if ("Natural".equals(label)) label = "Phrase Pause";\n        } else if (softBreath) {\n            speedDelta -= 0.004f;\n            pause += 36;\n            if ("Natural".equals(label)) label = "Breath";\n        }\n        if (paragraph) {\n''')
replace_once(prosody,
             '''            if (strongest == 0 && !question && !exclamation && !ellipsis && !dialogue\n                    && !sentenceEnd && !phraseEnd && !paragraph) {\n''',
             '''            if (strongest == 0 && !question && !exclamation && !ellipsis && !dialogue\n                    && !sentenceEnd && !phraseEnd && !softBreath && !paragraph) {\n''')
replace_once(prosody,
             '''        if (sentenceEnd) expressivePause = Math.max(expressivePause, 230);\n        if (phraseEnd) expressivePause = Math.max(expressivePause, 110);\n        if (paragraph) expressivePause = Math.max(expressivePause, 360);\n\n        if (geminiLike) {\n            speed = clamp(speed, 0.900f, 1.080f);\n            pitch = clamp(pitch, 0.925f, 1.070f);\n            expressiveGain = clamp(expressiveGain, 0, 240);\n            expressivePause = clamp(expressivePause, 70, 420);\n        } else {\n            speed = clamp(speed, 0.925f, 1.055f);\n            pitch = clamp(pitch, 0.950f, 1.045f);\n            expressiveGain = clamp(expressiveGain, 0, 180);\n            expressivePause = clamp(expressivePause, 55, 420);\n        }\n''',
             '''        if (sentenceEnd) expressivePause = Math.max(expressivePause, 290);\n        if (phraseEnd) expressivePause = Math.max(expressivePause, 175);\n        if (softBreath) expressivePause = Math.max(expressivePause, 125);\n        if (paragraph) expressivePause = Math.max(expressivePause, 440);\n\n        // Preserve the native Nilar/Thiha timbre. Large MediaPlayer pitch shifts were one of the\n        // main things that made the voices sound synthetic, so v6 gets expression mostly from\n        // timing, phrasing, speed and restrained gain instead of artificial pitch bending.\n        if (geminiLike) {\n            speed = clamp(speed, 0.920f, 1.065f);\n            pitch = clamp(pitch, 0.970f, 1.035f);\n            expressiveGain = clamp(expressiveGain, 0, 210);\n            expressivePause = clamp(expressivePause, 80, 520);\n        } else {\n            speed = clamp(speed, 0.940f, 1.045f);\n            pitch = clamp(pitch, 0.980f, 1.025f);\n            expressiveGain = clamp(expressiveGain, 0, 160);\n            expressivePause = clamp(expressivePause, 70, 520);\n        }\n''')
replace_once(prosody,
             '''    private static int boundaryPause(String raw) {\n        if (hasStructuralLineBreak(raw)) return 360;\n        String value = stripClosingQuotes(raw.trim());\n        if (hasEllipsis(value)) return 290;\n        if (value.endsWith("?")) return 225;\n        if (value.endsWith("!")) return 190;\n        if (value.endsWith("။") || value.endsWith(".")) return 230;\n        if (value.endsWith("၊") || value.endsWith(",") || value.endsWith(";") || value.endsWith(":")) return 110;\n        return 72;\n    }\n''',
             '''    private static int boundaryPause(String raw) {\n        if (hasStructuralLineBreak(raw)) return 440;\n        String value = stripClosingQuotes(raw.trim());\n        if (hasEllipsis(value)) return 360;\n        if (value.endsWith("?")) return 300;\n        if (value.endsWith("!")) return 255;\n        if (value.endsWith("။") || value.endsWith(".")) return 290;\n        if (value.endsWith("၊") || value.endsWith(",") || value.endsWith(";") || value.endsWith(":")) return 175;\n        return 90;\n    }\n''')

# Let the neural voices keep their own natural timbre. We only add a tiny rate settling;
# do not lower Thiha's pitch artificially.
edge = "app/src/main/java/com/whisper/wowaudio/EdgeMyanmarTtsClient.java"
replace_once(edge,
             '''                    String pitch = VOICE_THIHA.equals(voice) ? "-1Hz" : "+0Hz";\n''',
             '''                    String pitch = "+0Hz";\n''')

# Update tests to lock the audible pauses the user asked for.
test = "app/src/test/java/com/whisper/wowaudio/BurmeseProsodyTest.java"
replace_once(test,
             '        assertTrue(p.pauseAfterMs >= 190);',
             '        assertTrue(p.pauseAfterMs >= 290);')
replace_once(test,
             '        assertTrue(p.pauseAfterMs >= 320);',
             '        assertTrue(p.pauseAfterMs >= 440);')
insert_after = '''    @Test public void structuralLineBreakGetsLongPause() {\n        BurmeseProsody.Profile p = BurmeseProsody.analyze("အခန်း (၁)\\n", VoiceSettings.STYLE_GEMINI_EXPRESSIVE);\n        assertTrue(p.pauseAfterMs >= 440);\n        assertTrue(p.pitchMultiplier <= 1.0f);\n    }\n'''
addition = '''\n    @Test public void burmesePhraseCommaGetsAudibleBreath() {\n        BurmeseProsody.Profile p = BurmeseProsody.analyze(\n                "သူက စာအုပ်ကိုယူပြီး၊", VoiceSettings.STYLE_AUTO);\n        assertTrue(p.pauseAfterMs >= 175);\n    }\n\n    @Test public void longUnpunctuatedClauseGetsSoftBreath() {\n        String text = "ဒီစာပိုဒ်ဟာ ပုဒ်ဖြတ်ပုဒ်ရပ်မပါဘဲ အလွန်ရှည်လျားနေတဲ့အခါ လူတစ်ယောက် ဖတ်သလို အသက်ရှူချိန်လေး ရှိစေဖို့ စမ်းသပ်ထားတဲ့ မြန်မာစာပိုဒ်ရှည်တစ်ခု ဖြစ်ပါတယ်";\n        BurmeseProsody.Profile p = BurmeseProsody.analyze(text, VoiceSettings.STYLE_AUTO);\n        assertTrue(p.pauseAfterMs >= 125);\n        assertTrue(p.pitchMultiplier >= 0.98f && p.pitchMultiplier <= 1.025f);\n    }\n'''
replace_once(test, insert_after, insert_after + addition)

text_test = "app/src/test/java/com/whisper/wowaudio/TtsTextTest.java"
replace_once(text_test,
             '            assertTrue(chunks.get(i).length() <= 305);',
             '            assertTrue(chunks.get(i).length() <= 220);')

print("Applied Nilar/Thiha v6 Burmese breath and human-cadence tuning")
