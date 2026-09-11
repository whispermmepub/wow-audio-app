package com.whisper.wowaudio;

import android.content.Context;
import android.content.SharedPreferences;

final class VoiceSettings {
    static final String PREFS = "voice_settings_v2";
    static final String ENGINE_EDGE = "edge";
    static final String ENGINE_GEMINI = "gemini";
    static final String ENGINE_OFFLINE = "offline";

    static final String DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-tts-preview";
    static final String DEFAULT_GEMINI_VOICE = "Achernar";
    static final String DEFAULT_STYLE = "Warm, calm audiobook narration in natural Burmese. Read clearly at a comfortable pace and preserve the text exactly.";

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

    static String engineLabel(Context context) {
        String engine = engine(context);
        if (ENGINE_GEMINI.equals(engine)) return "Gemini • " + geminiVoice(context);
        if (ENGINE_OFFLINE.equals(engine)) return "Offline Burmese";
        return EdgeMyanmarTtsClient.VOICE_THIHA.equals(edgeVoice(context)) ? "Thiha" : "Nilar";
    }
}
