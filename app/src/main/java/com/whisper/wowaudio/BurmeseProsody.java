package com.whisper.wowaudio;

import java.util.Locale;

/**
 * Lightweight deterministic Burmese prosody hints.
 *
 * It deliberately works at playback time so the proven fast Nilar / Thiha synthesis request is
 * untouched. No extra network request and no SSML break injection is required.
 */
final class BurmeseProsody {
    static final String RENDER_VERSION = "burmese-prosody-v1";

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
        return new Profile(1.0f, 1.0f, 0, 80, "Natural");
    }

    static Profile analyze(String text, String readingStyle) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) return neutral();

        float intensity = styleIntensity(readingStyle);
        float baseSpeed = 1.0f;
        float basePitch = 1.0f;
        int basePause = 95;

        if (VoiceSettings.STYLE_NORMAL.equals(readingStyle)) intensity = 0.0f;
        else if (VoiceSettings.STYLE_CALM.equals(readingStyle)) {
            baseSpeed = 0.975f;
            basePitch = 0.992f;
            basePause = 130;
        } else if (VoiceSettings.STYLE_DRAMATIC.equals(readingStyle)) {
            baseSpeed = 0.985f;
            basePause = 115;
        } else if (VoiceSettings.STYLE_NARRATOR.equals(readingStyle)) {
            baseSpeed = 0.992f;
            basePause = 105;
        }

        boolean question = s.indexOf('?') >= 0 || containsAny(s,
                "လား", "သလား", "မလဲ", "ဘယ်လို", "ဘာကြောင့်", "ဘယ်သူ", "ဘယ်မှာ", "ဘယ်တော့");
        boolean exclamation = s.indexOf('!') >= 0 || containsAny(s,
                "အံ့ဩ", "အော်", "ဟာ", "အမလေး", "အလိုလေး", "မဖြစ်ဘူး");
        boolean dialogue = looksLikeDialogue(s);
        boolean sad = containsAny(s,
                "ဝမ်းနည်း", "မျက်ရည်", "ငို", "ဆုံးရှုံး", "နာကျင်", "ကြေကွဲ", "လွမ်း", "သေဆုံး", "စိတ်မကောင်း");
        boolean tense = containsAny(s,
                "ကြောက်", "ထိတ်လန့်", "အန္တရာယ်", "ပြေး", "အော်ဟစ်", "တိုက်ခိုက်", "အရေးပေါ်", "တုန်", "ခြိမ်းခြောက်");
        boolean calm = containsAny(s,
                "တိတ်ဆိတ်", "ငြိမ်သက်", "အေးချမ်း", "ညင်သာ", "ဖြည်းဖြည်း", "နူးညံ့", "တည်ငြိမ်");
        boolean joyful = containsAny(s,
                "ပျော်", "ဝမ်းသာ", "ရယ်", "ကြည်နူး", "ပီတိ", "အောင်မြင်", "ချစ်စရာ");

        float speedDelta = 0.0f;
        float pitchDelta = 0.0f;
        int gain = 0;
        int pause = basePause;
        String label = "Natural";

        if (sad) {
            speedDelta -= 0.050f;
            pitchDelta -= 0.035f;
            pause += 80;
            label = "Tender";
        } else if (calm) {
            speedDelta -= 0.035f;
            pitchDelta -= 0.020f;
            pause += 55;
            label = "Calm";
        } else if (tense) {
            speedDelta += 0.035f;
            pitchDelta += 0.015f;
            gain += 100;
            pause = Math.max(60, pause - 20);
            label = "Tense";
        } else if (joyful) {
            speedDelta += 0.022f;
            pitchDelta += 0.022f;
            gain += 100;
            label = "Bright";
        }

        if (question) {
            speedDelta -= 0.010f;
            pitchDelta += 0.026f;
            pause += 35;
            if ("Natural".equals(label)) label = "Question";
        }
        if (exclamation) {
            speedDelta += 0.020f;
            pitchDelta += 0.024f;
            gain += 100;
            if ("Natural".equals(label)) label = "Emphasis";
        }
        if (dialogue) {
            pitchDelta += 0.012f;
            pause += 15;
            if ("Natural".equals(label)) label = "Dialogue";
        }

        float speed = baseSpeed + speedDelta * intensity;
        float pitch = basePitch + pitchDelta * intensity;
        int expressiveGain = Math.round(gain * intensity);
        int expressivePause = basePause + Math.round((pause - basePause) * Math.max(0.35f, intensity));

        speed = clamp(speed, 0.92f, 1.07f);
        pitch = clamp(pitch, 0.95f, 1.06f);
        expressiveGain = clamp(expressiveGain, 0, 200);
        expressivePause = clamp(expressivePause, 55, 220);
        return new Profile(speed, pitch, expressiveGain, expressivePause, label);
    }

    static String geminiDirection() {
        return "Use natural Burmese prosody and phrasing. Respect Myanmar punctuation and paragraph rhythm; "
                + "give questions a natural rising contour, statements a settled ending, dialogue subtle character contrast, "
                + "and emotional passages appropriate emphasis without overacting. Preserve every written word exactly.";
    }

    private static float styleIntensity(String style) {
        if (VoiceSettings.STYLE_NARRATOR.equals(style)) return 0.35f;
        if (VoiceSettings.STYLE_STORYTELLER.equals(style)) return 0.62f;
        if (VoiceSettings.STYLE_CALM.equals(style)) return 0.40f;
        if (VoiceSettings.STYLE_DRAMATIC.equals(style)) return 0.82f;
        if (VoiceSettings.STYLE_EMOTIONAL.equals(style)) return 0.78f;
        if (VoiceSettings.STYLE_AUTO.equals(style)) return 0.66f;
        return 0.0f;
    }

    private static boolean looksLikeDialogue(String s) {
        String value = s.trim();
        if (value.isEmpty()) return false;
        char first = value.charAt(0);
        return first == '“' || first == '‘' || first == '"' || first == '\'' || first == '«'
                || value.contains("”") || value.contains("’") || value.contains("»");
    }

    private static boolean containsAny(String value, String... terms) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (lower.contains(term.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
