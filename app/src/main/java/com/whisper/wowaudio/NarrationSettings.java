package com.whisper.wowaudio;

import android.content.Context;
import android.content.SharedPreferences;

final class NarrationSettings {
    static final String DEFAULT_VOICE = "Achernar";
    static final String DEFAULT_STYLE = "Narrate naturally and warmly like a professional audiobook reader. Preserve the original Burmese or English text exactly, with clear pronunciation, natural pauses, and calm pacing.";
    static final String ENGINE_AUTO = "auto";
    static final String ENGINE_OFFLINE = "offline";
    static final String ENGINE_GEMINI = "gemini";

    private static final String PREFS = "wow_audio_narration";
    private final SharedPreferences prefs;
    private final Context context;

    NarrationSettings(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String voice() { return prefs.getString("voice", DEFAULT_VOICE); }
    String style() { return prefs.getString("style", DEFAULT_STYLE); }
    String engineMode() {
        String value = prefs.getString("engine", ENGINE_AUTO);
        if (ENGINE_OFFLINE.equals(value) || ENGINE_GEMINI.equals(value)) return value;
        return ENGINE_AUTO;
    }
    float speed() { return clampSpeed(prefs.getFloat("speed", 1f)); }
    int sleepMinutes() { return Math.max(0, prefs.getInt("sleep_minutes", 0)); }

    boolean useOfflineEngine() {
        String mode = engineMode();
        if (ENGINE_GEMINI.equals(mode)) return false;
        return OfflineBurmeseTtsClient.isEspeakInstalled(context);
    }

    boolean narrationAvailable() {
        if (useOfflineEngine()) return true;
        return new SecretStore(context).hasApiKey();
    }

    String effectiveCacheVoice() {
        return useOfflineEngine()
                ? OfflineBurmeseTtsClient.ENGINE_ID
                : "gemini-" + voice();
    }

    String engineLabel() {
        if (useOfflineEngine()) return "Offline Burmese • eSpeak NG";
        if (new SecretStore(context).hasApiKey()) return "Natural voice • Gemini";
        return "Voice setup needed";
    }

    void save(String voice, String style) {
        String nextVoice = empty(voice) ? DEFAULT_VOICE : voice.trim();
        String nextStyle = empty(style) ? DEFAULT_STYLE : style.trim();
        boolean changed = !nextVoice.equals(this.voice()) || !nextStyle.equals(this.style());
        prefs.edit()
                .putString("voice", nextVoice)
                .putString("style", nextStyle)
                .commit();
        if (changed && narrationAvailable()) NarrationGenerationService.enqueueAllLibrary(context);
    }

    void saveEngineMode(String mode) {
        String next = ENGINE_OFFLINE.equals(mode) ? ENGINE_OFFLINE : ENGINE_GEMINI.equals(mode) ? ENGINE_GEMINI : ENGINE_AUTO;
        if (next.equals(engineMode())) return;
        prefs.edit().putString("engine", next).commit();
        if (narrationAvailable()) NarrationGenerationService.enqueueAllLibrary(context);
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
