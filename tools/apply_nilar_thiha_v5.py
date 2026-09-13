from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    s = p.read_text(encoding="utf-8")
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    p.write_text(s.replace(old, new), encoding="utf-8")


# v8 final pass: keep the native neural timbre, make Burmese phrasing/breath placement do
# most of the expressive work, and aggressively prevent stray n/N EPUB artefacts reaching TTS.
tts = "app/src/main/java/com/whisper/wowaudio/TtsText.java"
replace_once(tts,
             "    private static final int FIRST_MAX_CHARS = 132;\n    private static final int NEXT_MAX_CHARS = 190;\n",
             "    private static final int FIRST_MAX_CHARS = 128;\n    private static final int NEXT_MAX_CHARS = 178;\n")
replace_once(tts,
             "            int end = findBreak(normalized, start, limit, first ? 28 : 46);",
             "            int end = findBreak(normalized, start, limit, first ? 26 : 42);")
replace_once(tts,
             "                .replace(\"\\uFEFF\", \"\")\n                .replace(\"\\\\r\\\\n\", \"\\n\")",
             "                .replace(\"\\uFEFF\", \"\")\n                .replace('ｎ', 'n')\n                .replace('Ｎ', 'N')\n                .replace(\"\\\\r\\\\n\", \"\\n\")")
replace_once(tts,
             '''        s = s.replaceAll("(?im)^[ \\\\t]*(?:[nN]+[ \\\\t]*)+$", "")\n                .replaceAll("(?i)(?<![A-Za-z0-9])(?:[\\\\\\\\/|]+[ \\\\t]*)?[nN]+(?:[ \\\\t]+[nN]+)*(?:[ \\\\t]*[\\\\\\\\/|]+)?(?![A-Za-z0-9])", " ")\n                .replaceAll("(?i)(?<![A-Za-z0-9])[nN]+(?![A-Za-z0-9])", " ");\n\n        return clean(s);\n''',
             '''        s = s.replaceAll("(?im)^[ \\\\t]*(?:[nN]+[ \\\\t]*)+$", "")\n                .replaceAll("(?i)(?<![A-Za-z0-9])(?:[\\\\\\\\/|]+[ \\\\t]*)?[nN]+(?:[ \\\\t]+[nN]+)*(?:[ \\\\t]*[\\\\\\\\/|]+)?(?![A-Za-z0-9])", " ")\n                .replaceAll("(?i)(?<![A-Za-z0-9])[nN]+(?![A-Za-z0-9])", " ")\n                // Bad EPUB spacing often leaves a visual gap before Burmese punctuation. Remove\n                // that gap so the neural voice sees the punctuation as part of the phrase.\n                .replaceAll("[ \\\\t]+([။၊!?…])", "$1")\n                .replaceAll("([။၊!?…])[ \\\\t]{2,}", "$1 ");\n\n        return clean(s);\n''')
replace_once(tts,
             '''        // Burmese phrase comma gets a smaller breath and should not be swallowed by a long chunk.\n        for (int i = start; i < limit; i++) {\n            char c = text.charAt(i);\n            if (c == '၊' || c == ',' || c == ';' || c == ':') return includeClosingQuotes(text, i + 1, limit);\n        }\n\n        if (limit >= text.length()) return text.length();\n''',
             '''        // Burmese phrase comma gets a smaller breath and should not be swallowed by a long chunk.\n        for (int i = start; i < limit; i++) {\n            char c = text.charAt(i);\n            if (c == '၊' || c == ',' || c == ';' || c == ':') return includeClosingQuotes(text, i + 1, limit);\n        }\n\n        // If punctuation is missing, prefer a Burmese grammatical connector near the end of the\n        // phrase. Human readers naturally take a micro-breath after these connectors instead of\n        // cutting at an arbitrary character count.\n        int naturalBreak = naturalBurmesePhraseBreak(text, start, limit, floor);\n        if (naturalBreak > start) return naturalBreak;\n\n        if (limit >= text.length()) return text.length();\n''')
replace_once(tts,
             '''    private static boolean englishSentenceEnd(String text, int index) {\n''',
             '''    private static int naturalBurmesePhraseBreak(String text, int start, int limit, int floor) {\n        int safeFloor = Math.max(floor, Math.min(limit - 1, start + 72));\n        String[] cues = {\n                "ပြီးတော့", "သော်လည်း", "သော်ငြား", "သဖြင့်", "သောကြောင့်", "ကြောင့်",\n                "ဖြစ်၍", "နေစဉ်", "စဉ်", "အခါ", "ဆိုပြီး", "ဟု", "လို့", "ပြီး", "ကာ", "လျက်", "၍"\n        };\n        for (int i = limit - 1; i >= safeFloor; i--) {\n            if (!Character.isWhitespace(text.charAt(i))) continue;\n            int phraseEnd = i;\n            while (phraseEnd > start && Character.isWhitespace(text.charAt(phraseEnd - 1))) phraseEnd--;\n            int from = Math.max(start, phraseEnd - 28);\n            String tail = text.substring(from, phraseEnd).trim();\n            for (String cue : cues) {\n                if (tail.endsWith(cue)) return i + 1;\n            }\n        }\n        return -1;\n    }\n\n    private static boolean englishSentenceEnd(String text, int index) {\n''')

prosody = "app/src/main/java/com/whisper/wowaudio/BurmeseProsody.java"
replace_once(prosody,
             'static final String RENDER_VERSION = "burmese-prosody-v7-final-human-reader";',
             'static final String RENDER_VERSION = "burmese-prosody-v8-studio-reader";')
replace_once(prosody,
             'return new Profile(1.0f, 1.0f, 0, 102, "Natural");',
             'return new Profile(1.0f, 1.0f, 0, 106, "Natural");')
replace_once(prosody,
             '? new Profile(0.99f, 0.99f, 0, 320, "Line Break")',
             '? new Profile(0.995f, 0.998f, 0, 500, "Line Break")')
replace_once(prosody,
             '''                speedDelta -= 0.045f;\n                pitchDelta -= 0.018f;''',
             '''                speedDelta -= 0.038f;\n                pitchDelta -= 0.010f;''')
replace_once(prosody,
             '''                speedDelta -= 0.028f;\n                pitchDelta -= 0.009f;''',
             '''                speedDelta -= 0.026f;\n                pitchDelta -= 0.005f;''')
replace_once(prosody,
             '''                speedDelta += 0.020f;\n                pitchDelta += 0.008f;''',
             '''                speedDelta += 0.016f;\n                pitchDelta += 0.004f;''')
replace_once(prosody,
             '''                speedDelta += 0.016f;\n                pitchDelta += 0.013f;''',
             '''                speedDelta += 0.013f;\n                pitchDelta += 0.007f;''')
replace_once(prosody,
             '''                speedDelta += 0.012f;\n                pitchDelta -= 0.004f;''',
             '''                speedDelta += 0.010f;\n                pitchDelta -= 0.002f;''')
replace_once(prosody,
             '''                speedDelta -= 0.024f;\n                pitchDelta -= 0.008f;''',
             '''                speedDelta -= 0.021f;\n                pitchDelta -= 0.004f;''')
replace_once(prosody,
             'pitchDelta += quotedQuestion ? 0.021f : 0.016f;',
             'pitchDelta += quotedQuestion ? 0.012f : 0.009f;')
replace_once(prosody,
             'pitchDelta += 0.010f;\n            gain += 55;',
             'pitchDelta += 0.006f;\n            gain += 45;')
replace_once(prosody,
             'pitchDelta += 0.006f;\n            pause += 10;',
             'pitchDelta += 0.003f;\n            pause += 12;')
replace_once(prosody,
             '''            speedDelta -= 0.010f;\n            pitchDelta -= 0.012f;\n            pause += 65;''',
             '''            speedDelta -= 0.009f;\n            pitchDelta -= 0.007f;\n            pause += 68;''')
replace_once(prosody,
             '''            speedDelta -= 0.005f;\n            pitchDelta -= 0.002f;\n            pause += 42;''',
             '''            speedDelta -= 0.004f;\n            pitchDelta -= 0.001f;\n            pause += 44;''')
replace_once(prosody,
             '''            speedDelta -= 0.004f;\n            pause += 36;''',
             '''            speedDelta -= 0.004f;\n            pause += s.length() >= 165 ? 48 : 40;''')
replace_once(prosody,
             '''            speedDelta -= 0.012f;\n            pitchDelta -= 0.010f;\n            pause += 115;''',
             '''            speedDelta -= 0.010f;\n            pitchDelta -= 0.006f;\n            pause += 120;''')
replace_once(prosody,
             '''            cueBoost += 0.18f;\n            speedDelta *= 1.16f;\n            pitchDelta *= 1.20f;\n            gain = Math.round(gain * 1.20f);''',
             '''            cueBoost += 0.12f;\n            speedDelta *= 1.10f;\n            pitchDelta *= 1.08f;\n            gain = Math.round(gain * 1.12f);''')
replace_once(prosody,
             '''                pitchDelta += question ? 0.010f : 0.006f;\n                gain += 28;''',
             '''                pitchDelta += question ? 0.004f : 0.002f;\n                gain += 20;''')
replace_once(prosody,
             '''        if (sentenceEnd) expressivePause = Math.max(expressivePause, 320);\n        if (phraseEnd) expressivePause = Math.max(expressivePause, 185);\n        if (softBreath) expressivePause = Math.max(expressivePause, 135);\n        if (paragraph) expressivePause = Math.max(expressivePause, 480);\n\n        // Final pass: human narration gets most of its expression from phrasing, breath and timing.\n        // Keep playback pitch extremely close to the neural voice's original timbre so Nilar/Thiha\n        // do not acquire a synthetic "processed" sound.\n        if (geminiLike) {\n            speed = clamp(speed, 0.925f, 1.055f);\n            pitch = clamp(pitch, 0.980f, 1.020f);\n            expressiveGain = clamp(expressiveGain, 0, 195);\n            expressivePause = clamp(expressivePause, 85, 560);\n        } else {\n            speed = clamp(speed, 0.945f, 1.040f);\n            pitch = clamp(pitch, 0.985f, 1.015f);\n            expressiveGain = clamp(expressiveGain, 0, 150);\n            expressivePause = clamp(expressivePause, 75, 560);\n        }\n''',
             '''        if (sentenceEnd) expressivePause = Math.max(expressivePause, 330);\n        if (phraseEnd) expressivePause = Math.max(expressivePause, 190);\n        if (softBreath) expressivePause = Math.max(expressivePause, s.length() >= 165 ? 155 : 145);\n        if (paragraph) expressivePause = Math.max(expressivePause, 500);\n\n        // Studio-reader pass: let the neural model supply the voice's natural intonation. Playback\n        // processing only adds a very small contour; phrasing, timing and breath placement carry\n        // the expression. This avoids the metallic/processed sound caused by broad pitch shifting.\n        if (geminiLike) {\n            speed = clamp(speed, 0.930f, 1.050f);\n            pitch = clamp(pitch, 0.988f, 1.012f);\n            expressiveGain = clamp(expressiveGain, 0, 180);\n            expressivePause = clamp(expressivePause, 90, 590);\n        } else {\n            speed = clamp(speed, 0.948f, 1.038f);\n            pitch = clamp(pitch, 0.991f, 1.009f);\n            expressiveGain = clamp(expressiveGain, 0, 140);\n            expressivePause = clamp(expressivePause, 80, 590);\n        }\n''')
replace_once(prosody,
             '''        if (hasStructuralLineBreak(raw)) return 480;\n        String value = stripClosingQuotes(raw.trim());\n        if (hasEllipsis(value)) return 390;\n        if (value.endsWith("?")) return 320;\n        if (value.endsWith("!")) return 270;\n        if (value.endsWith("။") || value.endsWith(".")) return 320;\n        if (value.endsWith("၊") || value.endsWith(",") || value.endsWith(";") || value.endsWith(":")) return 185;\n        return 96;''',
             '''        if (hasStructuralLineBreak(raw)) return 500;\n        String value = stripClosingQuotes(raw.trim());\n        if (hasEllipsis(value)) return 410;\n        if (value.endsWith("?")) return 330;\n        if (value.endsWith("!")) return 280;\n        if (value.endsWith("။") || value.endsWith(".")) return 330;\n        if (value.endsWith("၊") || value.endsWith(",") || value.endsWith(";") || value.endsWith(":")) return 190;\n        return 100;''')

edge = "app/src/main/java/com/whisper/wowaudio/EdgeMyanmarTtsClient.java"
replace_once(edge,
             'Nilar / Thiha v5: keep synthesis itself conservative and human-like.',
             'Nilar / Thiha v8: keep synthesis conservative and studio-reader natural.')
replace_once(edge,
             'int voiceRateOffset = VOICE_NILAR.equals(voice) ? -2 : -1;',
             'int voiceRateOffset = VOICE_NILAR.equals(voice) ? -3 : -2;')

# Lock the final cadence and cleanup behavior with tests.
text_test = "app/src/test/java/com/whisper/wowaudio/TtsTextTest.java"
replace_once(text_test,
             '            assertTrue(chunks.get(i).length() <= 195);',
             '            assertTrue(chunks.get(i).length() <= 182);')
insert_after = '''    @Test public void removesEveryStandaloneOrRepeatedNArtifactShape() {\n        String input = "အစ။ n nn NNN /n \\\\ n n n မြန်မာစာn ၊N။ အဆုံး။";\n        String cleaned = TtsText.normalizeForSpeech(input);\n        assertFalse(cleaned.matches("(?s).*(?<![A-Za-z0-9])[nN]+(?![A-Za-z0-9]).*"));\n        assertTrue(cleaned.contains("အစ"));\n        assertTrue(cleaned.contains("မြန်မာစာ"));\n        assertTrue(cleaned.contains("အဆုံး"));\n    }\n'''
addition = '''\n    @Test public void removesFullWidthNNoiseAndTightensBurmesePunctuationSpacing() {\n        String input = "ပထမ Ｎ ဒုတိယ ｎ တတိယ  ။   နောက်တစ်ပိုဒ်  ၊  ဆက်ဖတ်မယ်။";\n        String cleaned = TtsText.normalizeForSpeech(input);\n        assertFalse(cleaned.contains("Ｎ"));\n        assertFalse(cleaned.contains("ｎ"));\n        assertFalse(cleaned.contains("  ။"));\n        assertFalse(cleaned.contains("  ၊"));\n    }\n\n    @Test public void naturalBurmeseConnectorCanBecomeBreathBoundary() {\n        String input = "သူက မနက်စောစောအိမ်ကထွက်လာပြီး လမ်းတစ်လျှောက်အေးအေးဆေးဆေးလျှောက်သွားကာ စာအုပ်ဆိုင်ရှေ့ကိုရောက်လာပြီး နောက်ထပ်ဘာလုပ်ရမလဲစဉ်းစားနေခဲ့သည်";\n        List<String> chunks = TtsText.chunks(input);\n        assertTrue(chunks.size() >= 2);\n        assertTrue(chunks.get(0).endsWith("ပြီး") || chunks.get(0).endsWith("ကာ"));\n    }\n'''
replace_once(text_test, insert_after, insert_after + addition)

prosody_test = "app/src/test/java/com/whisper/wowaudio/BurmeseProsodyTest.java"
replace_once(prosody_test, 'assertTrue(p.pauseAfterMs >= 320);', 'assertTrue(p.pauseAfterMs >= 330);')
replace_once(prosody_test, 'assertTrue(p.pauseAfterMs >= 480);', 'assertTrue(p.pauseAfterMs >= 500);')
replace_once(prosody_test, 'assertTrue(p.pauseAfterMs >= 185);', 'assertTrue(p.pauseAfterMs >= 190);')
replace_once(prosody_test, 'assertTrue(p.pauseAfterMs >= 135);', 'assertTrue(p.pauseAfterMs >= 145);')
replace_once(prosody_test,
             'assertTrue(p.pitchMultiplier >= 0.985f && p.pitchMultiplier <= 1.015f);',
             'assertTrue(p.pitchMultiplier >= 0.991f && p.pitchMultiplier <= 1.009f);')

print("Applied Nilar/Thiha v8 studio-reader final tuning")
