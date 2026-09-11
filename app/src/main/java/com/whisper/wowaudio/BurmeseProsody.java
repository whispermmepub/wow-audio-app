package com.whisper.wowaudio;

import java.util.Locale;

/**
 * Lightweight deterministic Burmese prosody hints for the fast Nilar / Thiha path.
 *
 * The important constraint is that this never adds SSML <break> nodes or extra network calls.
 * It only adjusts the single prosody envelope that the proven Edge request already uses.
 */
final class BurmeseProsody {
    static final String RENDER_VERSION = "burmese-prosody-v1";

    static final class Profile {
        final float speedMultiplier;
        final int pitchHz;
        final int volumePercent;
        final String label;

        Profile(float speedMultiplier, int pitchHz, int volumePercent, String label) {
            this.speedMultiplier = speedMultiplier;
            this.pitchHz = pitchHz;
            this.volumePercent = volumePercent;
            this.label = label;
        }
    }

    private BurmeseProsody() { }

    static Profile analyze(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) return new Profile(1.0f, 0, 0, "Normal");

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

        float speed = 1.0f;
        int pitch = 0;
        int volume = 0;
        String label = "Normal";

        if (sad) {
            speed -= 0.055f;
            pitch -= 5;
            label = "Tender";
        } else if (calm) {
            speed -= 0.040f;
            pitch -= 3;
            label = "Calm";
        } else if (tense) {
            speed += 0.035f;
            pitch += 3;
            volume += 3;
            label = "Tense";
        } else if (joyful) {
            speed += 0.025f;
            pitch += 4;
            volume += 2;
            label = "Bright";
        }

        if (question) {
            speed -= 0.015f;
            pitch += 4;
            if ("Normal".equals(label)) label = "Question";
        }
        if (exclamation) {
            speed += 0.025f;
            pitch += 3;
            volume += 2;
            if ("Normal".equals(label)) label = "Emphasis";
        }
        if (dialogue) {
            pitch += 2;
            if ("Normal".equals(label)) label = "Dialogue";
        }

        speed = clamp(speed, 0.92f, 1.07f);
        pitch = clamp(pitch, -7, 9);
        volume = clamp(volume, 0, 5);
        return new Profile(speed, pitch, volume, label);
    }

    static String geminiDirection() {
        return "Use natural Burmese prosody and phrasing. Respect Myanmar punctuation and paragraph rhythm; "
                + "give questions a natural rising contour, statements a settled ending, dialogue subtle character contrast, "
                + "and emotional passages appropriate emphasis without overacting. Preserve every written word exactly.";
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
