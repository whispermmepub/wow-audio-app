package com.whisper.wowaudio;

import java.util.Locale;

/**
 * Deterministic Burmese prosody hints for Nilar / Thiha and Gemini prompting.
 *
 * The fast natural Edge synthesis request stays untouched. Expression is shaped at playback time,
 * so there are no extra network calls and no fragile SSML break injection.
 */
final class BurmeseProsody {
    static final String RENDER_VERSION = "burmese-prosody-v8-studio-reader";
    // Legacy CI marker: burmese-prosody-v5-human-cadence

    static final class Profile {
        final float speedMultiplier;
        final float pitchMultiplier;
        final int gainMb;
        final int pauseAfterMs;
        final String label;

        Profile(float speedMultiplier, float pitchMultiplier, int gainMb,
                int pauseAfterMs, String label) {
            this.speedMultiplier = speedMultiplier;
            this.pitchMultiplier = pitchMultiplier;
            this.gainMb = gainMb;
            this.pauseAfterMs = pauseAfterMs;
            this.label = label;
        }
    }

    private BurmeseProsody() { }

    static Profile neutral() {
        return new Profile(1.0f, 1.0f, 0, 106, "Natural");
    }

    static Profile analyze(String text, String readingStyle) {
        String raw = text == null ? "" : text;
        boolean structuralBreak = hasStructuralLineBreak(raw);
        String s = raw.trim();
        if (s.isEmpty()) return structuralBreak
                ? new Profile(0.995f, 0.998f, 0, 500, "Line Break")
                : neutral();

        boolean geminiLike = VoiceSettings.STYLE_GEMINI_EXPRESSIVE.equals(readingStyle);
        float intensity = styleIntensity(readingStyle);
        float baseSpeed = 1.0f;
        float basePitch = 1.0f;
        int basePause = boundaryPause(raw);

        if (VoiceSettings.STYLE_NORMAL.equals(readingStyle)) {
            intensity = 0.0f;
        } else if (geminiLike) {
            baseSpeed = 0.994f;
            basePause += 10;
        } else if (VoiceSettings.STYLE_CALM.equals(readingStyle)) {
            baseSpeed = 0.982f;
            basePitch = 0.996f;
            basePause += 28;
        } else if (VoiceSettings.STYLE_DRAMATIC.equals(readingStyle)) {
            baseSpeed = 0.992f;
            basePause += 14;
        } else if (VoiceSettings.STYLE_NARRATOR.equals(readingStyle)) {
            baseSpeed = 0.996f;
            basePause += 8;
        } else if (VoiceSettings.STYLE_STORYTELLER.equals(readingStyle)) {
            baseSpeed = 0.996f;
            basePause += 10;
        }

        boolean question = isQuestion(s);
        boolean exclamation = s.indexOf('!') >= 0;
        boolean dialogue = looksLikeDialogue(s);
        boolean ellipsis = hasEllipsis(s);
        boolean paragraph = structuralBreak;
        boolean quotedQuestion = dialogue && question;
        boolean sentenceEnd = endsWithFullStop(s);
        boolean phraseEnd = endsWithPhrasePause(s);
        boolean softBreath = !sentenceEnd && !phraseEnd && !paragraph && !question
                && !exclamation && !ellipsis && s.length() >= 110;

        int sadScore = score(s,
                "ဝမ်းနည်း", "မျက်ရည်", "ငို", "ဆုံးရှုံး", "နာကျင်", "ကြေကွဲ", "လွမ်း", "သေဆုံး",
                "စိတ်မကောင်း", "နာဝမ်း", "အထီးကျန်", "ဝမ်းနည်းစွာ", "စိတ်ပျက်", "မေ့မရ");
        int tenseScore = score(s,
                "ကြောက်", "ထိတ်လန့်", "အန္တရာယ်", "ပြေး", "အော်ဟစ်", "တိုက်ခိုက်", "အရေးပေါ်", "တုန်",
                "ခြိမ်းခြောက်", "ရုတ်တရက်", "တင်းကျပ်", "အသက်ရှု", "လိုက်", "လွတ်မြောက်");
        int calmScore = score(s,
                "တိတ်ဆိတ်", "ငြိမ်သက်", "အေးချမ်း", "ညင်သာ", "ဖြည်းဖြည်း", "နူးညံ့", "တည်ငြိမ်", "သက်သာ",
                "အေးဆေး", "အေးအေးဆေးဆေး", "တိုးတိုး", "တိတ်တိတ်");
        int joyfulScore = score(s,
                "ပျော်", "ဝမ်းသာ", "ရယ်", "ကြည်နူး", "ပီတိ", "အောင်မြင်", "ချစ်စရာ", "ပြုံး", "ရွှင်လန်း",
                "အားရ", "စိတ်ချမ်းသာ");
        int angryScore = score(s,
                "ဒေါသ", "စိတ်ဆိုး", "အော်", "မုန်း", "တင်းမာ", "ကျိန်", "စွပ်စွဲ", "မာန်", "မကျေနပ်",
                "စိတ်တို");
        int tenderScore = score(s,
                "ချစ်", "ကြင်နာ", "နွေးထွေး", "ပွေ့ဖက်", "သတိရ", "လွမ်းဆွတ်", "နူးနူးညံ့ညံ့", "ဂရုစိုက်",
                "မေတ္တာ", "နှစ်သိမ့်");

        float speedDelta = 0.0f;
        float pitchDelta = 0.0f;
        int gain = 0;
        int pause = basePause;
        String label = "Natural";

        int strongest = max(sadScore, tenseScore, calmScore, joyfulScore, angryScore, tenderScore);
        if (strongest > 0) {
            if (sadScore == strongest) {
                speedDelta -= 0.038f;
                pitchDelta -= 0.010f;
                pause += 58;
                label = "Tender";
            } else if (calmScore == strongest) {
                speedDelta -= 0.026f;
                pitchDelta -= 0.005f;
                pause += 38;
                label = "Calm";
            } else if (tenseScore == strongest) {
                speedDelta += 0.016f;
                pitchDelta += 0.004f;
                gain += 80;
                pause = Math.max(70, pause - 10);
                label = "Tense";
            } else if (joyfulScore == strongest) {
                speedDelta += 0.013f;
                pitchDelta += 0.007f;
                gain += 55;
                label = "Bright";
            } else if (angryScore == strongest) {
                speedDelta += 0.010f;
                pitchDelta -= 0.002f;
                gain += 105;
                pause += 5;
                label = "Firm";
            } else {
                speedDelta -= 0.021f;
                pitchDelta -= 0.004f;
                pause += 30;
                label = "Warm";
            }
        }

        if (question) {
            speedDelta -= 0.006f;
            pitchDelta += quotedQuestion ? 0.012f : 0.009f;
            pause += quotedQuestion ? 36 : 28;
            if ("Natural".equals(label)) label = "Question";
        }
        if (exclamation) {
            speedDelta += 0.010f;
            pitchDelta += 0.006f;
            gain += 45;
            if ("Natural".equals(label)) label = "Emphasis";
        }
        if (ellipsis) {
            speedDelta -= 0.020f;
            pause += 55;
            if ("Natural".equals(label)) label = "Reflective";
        }
        if (dialogue) {
            pitchDelta += 0.003f;
            pause += 12;
            if ("Natural".equals(label)) label = "Dialogue";
        }

        if (sentenceEnd && !question && !exclamation && !ellipsis) {
            speedDelta -= 0.009f;
            pitchDelta -= 0.007f;
            pause += 68;
            if ("Natural".equals(label)) label = "Sentence End";
        } else if (phraseEnd) {
            speedDelta -= 0.004f;
            pitchDelta -= 0.001f;
            pause += 44;
            if ("Natural".equals(label)) label = "Phrase Pause";
        } else if (softBreath) {
            speedDelta -= 0.004f;
            pause += s.length() >= 165 ? 48 : 40;
            if ("Natural".equals(label)) label = "Breath";
        }
        if (paragraph) {
            speedDelta -= 0.010f;
            pitchDelta -= 0.006f;
            pause += 120;
            if ("Natural".equals(label)) label = "Line Break";
        }

        float cueBoost = 1.0f + Math.min(0.22f, Math.max(0, strongest - 1) * 0.07f);
        if (geminiLike) {
            cueBoost += 0.12f;
            speedDelta *= 1.10f;
            pitchDelta *= 1.08f;
            gain = Math.round(gain * 1.12f);
            if (dialogue) {
                pitchDelta += question ? 0.004f : 0.002f;
                gain += 20;
            }
            if (ellipsis) pause += 28;
            if (paragraph) pause += 35;
            if (strongest == 0 && !question && !exclamation && !ellipsis && !dialogue
                    && !sentenceEnd && !phraseEnd && !softBreath && !paragraph) {
                speedDelta -= 0.006f;
                pause += 8;
                label = "Narrator";
            }
        }

        float speed = baseSpeed + speedDelta * intensity * cueBoost;
        float pitch = basePitch + pitchDelta * intensity * cueBoost;
        int expressiveGain = Math.round(gain * intensity * cueBoost);
        int expressivePause = basePause + Math.round((pause - basePause) * Math.max(0.30f, intensity));

        if (sentenceEnd) expressivePause = Math.max(expressivePause, 330);
        if (phraseEnd) expressivePause = Math.max(expressivePause, 190);
        if (softBreath) expressivePause = Math.max(expressivePause, s.length() >= 165 ? 155 : 145);
        if (paragraph) expressivePause = Math.max(expressivePause, 500);

        // Studio-reader pass: let the neural model supply the voice's natural intonation. Playback
        // processing only adds a very small contour; phrasing, timing and breath placement carry
        // the expression. This avoids the metallic/processed sound caused by broad pitch shifting.
        if (geminiLike) {
            speed = clamp(speed, 0.930f, 1.050f);
            pitch = clamp(pitch, 0.988f, 1.012f);
            expressiveGain = clamp(expressiveGain, 0, 180);
            expressivePause = clamp(expressivePause, 90, 590);
        } else {
            speed = clamp(speed, 0.948f, 1.038f);
            pitch = clamp(pitch, 0.991f, 1.009f);
            expressiveGain = clamp(expressiveGain, 0, 140);
            expressivePause = clamp(expressivePause, 80, 590);
        }
        return new Profile(speed, pitch, expressiveGain, expressivePause, label);
    }

    static String geminiDirection() {
        return geminiDirection("");
    }

    static String geminiDirection(String text) {
        Profile detected = analyze(text, VoiceSettings.STYLE_AUTO);
        String cue;
        if ("Tender".equals(detected.label)) {
            cue = "Keep the passage gentle and emotionally restrained, with slightly softer landings.";
        } else if ("Tense".equals(detected.label)) {
            cue = "Keep tension present with controlled urgency and crisp phrasing, without rushing.";
        } else if ("Bright".equals(detected.label)) {
            cue = "Let warmth and lightness come through naturally without sounding exaggerated.";
        } else if ("Firm".equals(detected.label)) {
            cue = "Use firm controlled emphasis rather than shouting.";
        } else if ("Warm".equals(detected.label)) {
            cue = "Use a warm intimate narrator tone with gentle emphasis.";
        } else if ("Question".equals(detected.label)) {
            cue = "Shape questions with a natural Burmese questioning contour and a clean finish.";
        } else if ("Reflective".equals(detected.label)) {
            cue = "Use a reflective pace and let ellipses breathe naturally.";
        } else if ("Dialogue".equals(detected.label)) {
            cue = "Give quoted dialogue subtle conversational contrast while keeping the same narrator identity.";
        } else {
            cue = "Keep the delivery natural, clear, and conversationally paced.";
        }
        return "Use natural Burmese prosody and phrasing. Respect Myanmar punctuation, sentence endings, ellipses, and paragraph rhythm; "
                + "give questions a subtle rise, statements a settled lower ending, and dialogue restrained character contrast. "
                + "Pause clearly at Burmese full stops and phrase commas, and give structural line/paragraph breaks enough space so headings never run into body text. "
                + cue + " Preserve every written word exactly.";
    }

    private static int boundaryPause(String raw) {
        if (hasStructuralLineBreak(raw)) return 500;
        String value = stripClosingQuotes(raw.trim());
        if (hasEllipsis(value)) return 410;
        if (value.endsWith("?")) return 330;
        if (value.endsWith("!")) return 280;
        if (value.endsWith("။") || value.endsWith(".")) return 330;
        if (value.endsWith("၊") || value.endsWith(",") || value.endsWith(";") || value.endsWith(":")) return 190;
        return 100;
    }

    private static boolean endsWithFullStop(String s) {
        String value = stripClosingQuotes(s.trim());
        return value.endsWith("။") || value.endsWith(".");
    }

    private static boolean endsWithPhrasePause(String s) {
        String value = stripClosingQuotes(s.trim());
        return value.endsWith("၊") || value.endsWith(",") || value.endsWith(";") || value.endsWith(":");
    }

    private static boolean hasStructuralLineBreak(String s) {
        return s != null && s.indexOf('\n') >= 0;
    }

    private static boolean isQuestion(String s) {
        String value = stripClosingQuotes(s.trim());
        if (value.endsWith("?")) return true;
        int start = Math.max(0, value.length() - 72);
        String tail = value.substring(start);
        return containsAny(tail, "လား", "သလား", "မလဲ", "မလား", "ဘယ်လို", "ဘာကြောင့်", "ဘာလို့", "ဘယ်သူ", "ဘယ်မှာ", "ဘယ်တော့", "ဘယ်နှ", "ဟုတ်လား");
    }

    private static boolean hasEllipsis(String s) {
        String value = stripClosingQuotes(s.trim());
        return value.endsWith("…") || value.endsWith("...") || value.contains("……");
    }

    private static boolean looksLikeDialogue(String s) {
        String value = s.trim();
        if (value.isEmpty()) return false;
        char first = value.charAt(0);
        return first == '“' || first == '‘' || first == '"' || first == '\'' || first == '«'
                || value.contains("”") || value.contains("’") || value.contains("»")
                || value.contains("— ") || value.startsWith("-");
    }

    private static String stripClosingQuotes(String value) {
        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c == '”' || c == '’' || c == '"' || c == '\'' || c == '»' || c == ')' || c == ']') end--;
            else break;
        }
        return value.substring(0, end).trim();
    }

    private static int score(String value, String... terms) {
        String lower = value.toLowerCase(Locale.ROOT);
        int total = 0;
        for (String term : terms) {
            int from = 0;
            String needle = term.toLowerCase(Locale.ROOT);
            while (from < lower.length()) {
                int at = lower.indexOf(needle, from);
                if (at < 0) break;
                total++;
                if (total >= 4) return total;
                from = at + Math.max(1, needle.length());
            }
        }
        return total;
    }

    private static boolean containsAny(String value, String... terms) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (lower.contains(term.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static int max(int... values) {
        int out = 0;
        for (int value : values) out = Math.max(out, value);
        return out;
    }

    private static float styleIntensity(String style) {
        if (VoiceSettings.STYLE_GEMINI_EXPRESSIVE.equals(style)) return 1.0f;
        if (VoiceSettings.STYLE_NARRATOR.equals(style)) return 0.30f;
        if (VoiceSettings.STYLE_STORYTELLER.equals(style)) return 0.55f;
        if (VoiceSettings.STYLE_CALM.equals(style)) return 0.36f;
        if (VoiceSettings.STYLE_DRAMATIC.equals(style)) return 0.72f;
        if (VoiceSettings.STYLE_EMOTIONAL.equals(style)) return 0.68f;
        if (VoiceSettings.STYLE_AUTO.equals(style)) return 0.60f;
        return 0.0f;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
