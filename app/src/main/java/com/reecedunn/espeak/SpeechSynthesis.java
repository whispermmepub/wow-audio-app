package com.reecedunn.espeak;

/**
 * Minimal in-process bridge for the pinned eSpeak NG Android JNI library.
 * The JNI symbols intentionally use this package/class name.
 */
public final class SpeechSynthesis {
    public interface Callback {
        void onAudio(byte[] pcm16Mono);
        void onComplete();
        void onWordBoundary(int textPosition, int textLength, int markerInFrames);
    }

    static {
        System.loadLibrary("ttsespeak");
        if (!nativeClassInit()) throw new UnsatisfiedLinkError("eSpeak JNI init failed");
    }

    private final Callback callback;
    private final int sampleRate;

    public SpeechSynthesis(String dataParentPath, Callback callback) {
        this.callback = callback;
        this.sampleRate = nativeCreate(dataParentPath);
        if (sampleRate <= 0) throw new IllegalStateException("Bundled Burmese voice could not initialize");
    }

    public int getSampleRate() { return sampleRate; }

    public boolean useMyanmarVoice() {
        return nativeSetVoiceByName("my");
    }

    public void setRate(int wordsPerMinute) {
        nativeSetParameter(1, Math.max(80, Math.min(450, wordsPerMinute)));
    }

    public void setPitch(int value) {
        nativeSetParameter(3, Math.max(0, Math.min(100, value)));
    }

    public boolean synthesize(String text) {
        return nativeSynthesize(text == null ? "" : text, false);
    }

    public void stop() {
        nativeStop();
    }

    @SuppressWarnings("unused")
    private void nativeSynthCallback(byte[] audioData) {
        if (callback == null) return;
        if (audioData == null) callback.onComplete();
        else callback.onAudio(audioData);
    }

    @SuppressWarnings("unused")
    private void nativeSynthWordCallback(int textPosition, int textLength, int markerInFrames) {
        if (callback != null) callback.onWordBoundary(textPosition, textLength, markerInFrames);
    }

    private static native boolean nativeClassInit();
    private native int nativeCreate(String path);
    private native boolean nativeSetVoiceByName(String name);
    private native boolean nativeSetParameter(int parameter, int value);
    private native boolean nativeSynthesize(String text, boolean isSsml);
    private native boolean nativeStop();
}
