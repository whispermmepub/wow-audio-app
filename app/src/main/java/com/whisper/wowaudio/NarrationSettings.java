package com.whisper.wowaudio;

import android.content.Context;
import android.content.SharedPreferences;

final class NarrationSettings {
    static final String DEFAULT_VOICE = "Achernar";
    static final String DEFAULT_STYLE = "Narrate naturally and warmly like a professional audiobook reader. Preserve the original Burmese or English text exactly, with clear pronunciation, natural pauses, and calm pacing.";

    private static final String PREFS = "wow_audio_narration";
    private final SharedPreferences prefs;

    NarrationSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String voice() { return prefs.getString("voice", DEFAULT_VOICE); }
    String style() { return prefs.getString("style", DEFAULT_STYLE); }
    float speed() { return clampSpeed(prefs.getFloat("speed", 1f)); }
    int sleepMinutes() { return Math.max(0, prefs.getInt("sleep_minutes", 0)); }

    void save(String voice, String style) {
        prefs.edit()
                .putString("voice", empty(voice) ? DEFAULT_VOICE : voice.trim())
                .putString("style", empty(style) ? DEFAULT_STYLE : style.trim())
                .apply();
    }

    void savePlayback(float speed, int sleepMinutes) {
        prefs.edit()
                .putFloat("speed", clampSpeed(speed))
                .putInt("sleep_minutes", Math.max(0, sleepMinutes))
                .apply();
    }

    private static float clampSpeed(float value) { return Math.max(0.6f, Math.min(2f, value)); }
    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
}
