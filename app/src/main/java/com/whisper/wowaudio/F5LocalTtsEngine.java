package com.whisper.wowaudio;

import android.content.Context;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Crash-safe bridge for the experimental Aung Gyi/F5 voice slot.
 *
 * The current Q4 F5 ONNX runtime can terminate the Android process inside native ONNX Runtime on
 * some phones before Java can catch an exception. Until the F5 runtime is moved to an isolated
 * process, never enter that native path from the main audiobook service. This bridge guarantees
 * audible playback instead: Gemini TTS is tried when the user already has a key, then the bundled
 * Burmese offline engine is used as the no-network fallback.
 *
 * The imported F5 model pack and private reference WAV remain untouched in app-private storage so
 * the real Aung Gyi timbre can be re-enabled once the native runtime is isolated/proven safe.
 */
final class F5LocalTtsEngine implements AutoCloseable {
    static final class Audio {
        final float[] samples;
        final int sampleRate;

        Audio(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    private final Context appContext;
    private final GeminiTtsClient gemini = new GeminiTtsClient();
    private MmsMyanmarTtsEngine offline;
    private volatile boolean closed;

    F5LocalTtsEngine(Context context) {
        if (context == null) throw new IllegalArgumentException("Missing Android context.");
        if (!F5MyanmarVoicePack.isInstalled(context)) {
            throw new IllegalStateException("အောင်ကြီး voice is not installed: "
                    + F5MyanmarVoicePack.missingReason(context));
        }
        appContext = context.getApplicationContext();
    }

    synchronized Audio synthesize(Context context, String text) throws Exception {
        if (closed) throw new IllegalStateException("Voice engine is closed.");
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("No text to synthesize.");

        // Safe online path first. Gemini emits mono PCM16 WAV, so it can be converted directly to
        // the Audio object expected by the existing cache writer without touching MediaPlayer.
        String apiKey = SecureApiKeyStore.getGeminiKey(appContext);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            File temp = new File(appContext.getCacheDir(), "aung-gyi-safe-online.wav");
            try {
                gemini.synthesizeToFile(
                        clean,
                        apiKey,
                        VoiceSettings.geminiModel(appContext),
                        VoiceSettings.geminiVoice(appContext),
                        VoiceSettings.effectiveGeminiStyle(appContext),
                        temp);
                Audio online = readPcm16MonoWav(temp);
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                if (online.samples.length > 0) return online;
            } catch (Throwable ignored) {
                // Network/quota/model errors must never stop audiobook playback. Fall through to
                // the bundled Burmese engine, which is already covered by the app's CI smoke test.
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }

        if (offline == null) offline = new MmsMyanmarTtsEngine(appContext);
        MmsMyanmarTtsEngine.Audio generated = offline.synthesize(clean, 1.0f);
        if (generated == null || generated.samples == null || generated.samples.length == 0) {
            throw new IllegalStateException("Safe Burmese fallback produced no audio.");
        }
        return new Audio(generated.samples, generated.sampleRate);
    }

    private static Audio readPcm16MonoWav(File file) throws Exception {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 44) throw new IllegalArgumentException("Generated WAV is too short.");
            if (!"RIFF".equals(readAscii(in, 4))) throw new IllegalArgumentException("Generated audio is not RIFF WAV.");
            readLe32(in);
            if (!"WAVE".equals(readAscii(in, 4))) throw new IllegalArgumentException("Generated audio is not WAVE.");

            int channels = -1;
            int sampleRate = -1;
            int bits = -1;
            int format = -1;
            long dataOffset = -1;
            int dataSize = -1;

            while (in.getFilePointer() + 8 <= in.length()) {
                String chunk = readAscii(in, 4);
                int size = readLe32(in);
                if (size < 0 || in.getFilePointer() + size > in.length()) {
                    throw new IllegalArgumentException("Malformed generated WAV.");
                }
                long start = in.getFilePointer();
                if ("fmt ".equals(chunk)) {
                    format = readLe16(in);
                    channels = readLe16(in);
                    sampleRate = readLe32(in);
                    readLe32(in);
                    readLe16(in);
                    bits = readLe16(in);
                } else if ("data".equals(chunk)) {
                    dataOffset = start;
                    dataSize = size;
                }
                in.seek(start + size + (size & 1));
            }

            if (format != 1 || channels != 1 || bits != 16 || sampleRate < 8000
                    || sampleRate > 96000 || dataOffset < 0 || dataSize <= 0) {
                throw new IllegalArgumentException("Generated WAV must be mono PCM16 audio.");
            }

            int count = dataSize / 2;
            float[] samples = new float[count];
            in.seek(dataOffset);
            for (int i = 0; i < count; i++) {
                int lo = in.readUnsignedByte();
                int hi = in.readUnsignedByte();
                short pcm = (short) ((hi << 8) | lo);
                samples[i] = pcm / 32768.0f;
            }
            return new Audio(samples, sampleRate);
        }
    }

    private static String readAscii(RandomAccessFile in, int count) throws Exception {
        byte[] b = new byte[count];
        in.readFully(b);
        return new String(b, StandardCharsets.US_ASCII);
    }

    private static int readLe16(RandomAccessFile in) throws Exception {
        int a = in.readUnsignedByte();
        int b = in.readUnsignedByte();
        return a | (b << 8);
    }

    private static int readLe32(RandomAccessFile in) throws Exception {
        int a = in.readUnsignedByte();
        int b = in.readUnsignedByte();
        int c = in.readUnsignedByte();
        int d = in.readUnsignedByte();
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { gemini.close(); } catch (Throwable ignored) { }
        if (offline != null) {
            try { offline.close(); } catch (Throwable ignored) { }
            offline = null;
        }
    }
}
