package com.whisper.wowaudio;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

final class VoiceSettings {
    static final String PREFS = "voice_settings_v2";
    static final String ENGINE_EDGE = "edge";
    static final String ENGINE_GEMINI = "gemini";
    static final String ENGINE_OFFLINE = "offline";

    static final String DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-tts-preview";
    static final String DEFAULT_GEMINI_VOICE = "Achernar";
    static final String DEFAULT_STYLE = "Warm, calm audiobook narration in natural Burmese. Read clearly at a comfortable pace and preserve the text exactly.";

    static final String TONE_NORMAL = "normal";
    static final String TONE_SOFT = "soft";
    static final String TONE_DEEP = "deep";
    static final String TONE_BRIGHT = "bright";

    static final String STYLE_AUTO = "auto";
    static final String STYLE_NORMAL = "normal";
    static final String STYLE_NARRATOR = "narrator";
    static final String STYLE_STORYTELLER = "storyteller";
    static final String STYLE_CALM = "calm";
    static final String STYLE_DRAMATIC = "dramatic";
    static final String STYLE_EMOTIONAL = "emotional";

    static final float[] PLAYBACK_SPEEDS = new float[]{0.75f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f};
    static final String[] TONES = new String[]{TONE_NORMAL, TONE_SOFT, TONE_DEEP, TONE_BRIGHT};
    static final String[] READING_STYLES = new String[]{
            STYLE_AUTO, STYLE_NORMAL, STYLE_NARRATOR, STYLE_STORYTELLER,
            STYLE_CALM, STYLE_DRAMATIC, STYLE_EMOTIONAL
    };

    static final String[] GEMINI_VOICES = new String[]{
            "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede",
            "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
            "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
            "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
            "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
    };

    private VoiceSettings() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String engine(Context context) {
        String value = prefs(context).getString("engine", ENGINE_EDGE);
        if (ENGINE_GEMINI.equals(value) || ENGINE_OFFLINE.equals(value)) return value;
        return ENGINE_EDGE;
    }

    static void setEngine(Context context, String value) {
        String safe = ENGINE_GEMINI.equals(value) || ENGINE_OFFLINE.equals(value) ? value : ENGINE_EDGE;
        prefs(context).edit().putString("engine", safe).apply();
    }

    static String edgeVoice(Context context) {
        String value = prefs(context).getString("edge_voice", EdgeMyanmarTtsClient.VOICE_NILAR);
        return EdgeMyanmarTtsClient.VOICE_THIHA.equals(value)
                ? EdgeMyanmarTtsClient.VOICE_THIHA : EdgeMyanmarTtsClient.VOICE_NILAR;
    }

    static void setEdgeVoice(Context context, String value) {
        prefs(context).edit().putString("edge_voice",
                EdgeMyanmarTtsClient.VOICE_THIHA.equals(value)
                        ? EdgeMyanmarTtsClient.VOICE_THIHA : EdgeMyanmarTtsClient.VOICE_NILAR).apply();
    }

    static String geminiVoice(Context context) {
        String value = prefs(context).getString("gemini_voice", DEFAULT_GEMINI_VOICE);
        if (value == null || value.trim().isEmpty()) return DEFAULT_GEMINI_VOICE;
        return value.trim();
    }

    static void setGeminiVoice(Context context, String value) {
        prefs(context).edit().putString("gemini_voice",
                value == null || value.trim().isEmpty() ? DEFAULT_GEMINI_VOICE : value.trim()).apply();
    }

    static String geminiModel(Context context) {
        String value = prefs(context).getString("gemini_model", DEFAULT_GEMINI_MODEL);
        return value == null || value.trim().isEmpty() ? DEFAULT_GEMINI_MODEL : value.trim();
    }

    static void setGeminiModel(Context context, String value) {
        prefs(context).edit().putString("gemini_model",
                value == null || value.trim().isEmpty() ? DEFAULT_GEMINI_MODEL : value.trim()).apply();
    }

    static String geminiStyle(Context context) {
        String value = prefs(context).getString("gemini_style", DEFAULT_STYLE);
        return value == null || value.trim().isEmpty() ? DEFAULT_STYLE : value.trim();
    }

    static void setGeminiStyle(Context context, String value) {
        prefs(context).edit().putString("gemini_style",
                value == null || value.trim().isEmpty() ? DEFAULT_STYLE : value.trim()).apply();
    }

    static float playbackSpeed(Context context) {
        float value = prefs(context).getFloat("playback_speed", 1.0f);
        return clamp(value, 0.75f, 2.0f);
    }

    static void setPlaybackSpeed(Context context, float value) {
        prefs(context).edit().putFloat("playback_speed", clamp(value, 0.75f, 2.0f)).apply();
    }

    static float cyclePlaybackSpeed(Context context) {
        float current = playbackSpeed(context);
        int best = 0;
        float distance = Float.MAX_VALUE;
        for (int i = 0; i < PLAYBACK_SPEEDS.length; i++) {
            float d = Math.abs(current - PLAYBACK_SPEEDS[i]);
            if (d < distance) {
                best = i;
                distance = d;
            }
        }
        float next = PLAYBACK_SPEEDS[(best + 1) % PLAYBACK_SPEEDS.length];
        setPlaybackSpeed(context, next);
        return next;
    }

    static String tone(Context context) {
        String value = prefs(context).getString("voice_tone", TONE_NORMAL);
        for (String allowed : TONES) if (allowed.equals(value)) return value;
        return TONE_NORMAL;
    }

    static void setTone(Context context, String value) {
        String safe = TONE_NORMAL;
        for (String allowed : TONES) if (allowed.equals(value)) safe = allowed;
        prefs(context).edit().putString("voice_tone", safe).apply();
    }

    static String cycleTone(Context context) {
        String current = tone(context);
        int index = 0;
        for (int i = 0; i < TONES.length; i++) if (TONES[i].equals(current)) index = i;
        String next = TONES[(index + 1) % TONES.length];
        setTone(context, next);
        return next;
    }

    static float playbackPitch(Context context) {
        String tone = tone(context);
        if (TONE_SOFT.equals(tone)) return 0.97f;
        if (TONE_DEEP.equals(tone)) return 0.90f;
        if (TONE_BRIGHT.equals(tone)) return 1.08f;
        return 1.0f;
    }

    static String readingStyle(Context context) {
        String value = prefs(context).getString("reading_style", STYLE_AUTO);
        for (String allowed : READING_STYLES) if (allowed.equals(value)) return value;
        return STYLE_AUTO;
    }

    static void setReadingStyle(Context context, String value) {
        String safe = STYLE_AUTO;
        for (String allowed : READING_STYLES) if (allowed.equals(value)) safe = allowed;
        prefs(context).edit().putString("reading_style", safe).apply();
    }

    static String cycleReadingStyle(Context context) {
        String current = readingStyle(context);
        int index = 0;
        for (int i = 0; i < READING_STYLES.length; i++) if (READING_STYLES[i].equals(current)) index = i;
        String next = READING_STYLES[(index + 1) % READING_STYLES.length];
        setReadingStyle(context, next);
        return next;
    }

    static String effectiveGeminiStyle(Context context) {
        String preset = readingStyle(context);
        String direction;
        if (STYLE_NORMAL.equals(preset)) {
            direction = "Use a natural neutral speaking voice with clear Burmese pronunciation and steady pacing.";
        } else if (STYLE_NARRATOR.equals(preset)) {
            direction = "Perform as a professional audiobook narrator: polished, clear, warm, and expressive without overacting.";
        } else if (STYLE_STORYTELLER.equals(preset)) {
            direction = "Perform as an engaging storyteller. Give dialogue and narration natural contrast while keeping every word unchanged.";
        } else if (STYLE_CALM.equals(preset)) {
            direction = "Use a calm, gentle, reassuring delivery with relaxed pacing and soft emotional expression.";
        } else if (STYLE_DRAMATIC.equals(preset)) {
            direction = "Use a cinematic dramatic delivery. Build tension and emphasis naturally, but never shout or alter the text.";
        } else if (STYLE_EMOTIONAL.equals(preset)) {
            direction = "Use emotionally expressive audiobook narration. Reflect sadness, joy, tenderness, fear, or excitement when supported by the text.";
        } else {
            direction = "Automatically match the narration to the meaning and emotional mood of each passage. Detect whether the text is calm, sad, joyful, tense, intimate, suspenseful, explanatory, or dialogue, and adapt pacing, emphasis, warmth, and emotion naturally. Keep transitions subtle and never alter the written words.";
        }
        return direction + " " + geminiStyle(context);
    }

    static String speedLabel(Context context) {
        return String.format(Locale.US, "%.2gx", playbackSpeed(context)).replace("1x", "1.0x");
    }

    static String toneLabel(Context context) {
        String value = tone(context);
        if (TONE_SOFT.equals(value)) return "Soft";
        if (TONE_DEEP.equals(value)) return "Deep";
        if (TONE_BRIGHT.equals(value)) return "Bright";
        return "Normal";
    }

    static String readingStyleLabel(Context context) {
        String value = readingStyle(context);
        if (STYLE_NORMAL.equals(value)) return "Normal";
        if (STYLE_NARRATOR.equals(value)) return "Narrator";
        if (STYLE_STORYTELLER.equals(value)) return "Storyteller";
        if (STYLE_CALM.equals(value)) return "Calm";
        if (STYLE_DRAMATIC.equals(value)) return "Dramatic";
        if (STYLE_EMOTIONAL.equals(value)) return "Emotional";
        return "Auto Mood";
    }

    static String engineLabel(Context context) {
        String engine = engine(context);
        if (ENGINE_GEMINI.equals(engine)) return "Gemini • " + geminiVoice(context);
        if (ENGINE_OFFLINE.equals(engine)) return "Offline Burmese";
        return EdgeMyanmarTtsClient.VOICE_THIHA.equals(edgeVoice(context)) ? "Thiha" : "Nilar";
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
