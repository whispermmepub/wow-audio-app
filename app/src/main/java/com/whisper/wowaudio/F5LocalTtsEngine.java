package com.whisper.wowaudio;

import android.content.Context;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Crash-safe F5 Myanmar default voice bridge.
 *
 * The public F5 Myanmar Space can synthesize its base/default Burmese voice with ref_audio=null.
 * This keeps the experimental native ONNX path out of the audiobook process and requires no model
 * ZIP or reference WAV. Gemini remains an optional safe online backup; the service then has a
 * non-native WoW Natural fallback if both are unavailable.
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
    private final HuggingFaceF5Client onlineF5 = new HuggingFaceF5Client();
    private final GeminiTtsClient gemini = new GeminiTtsClient();
    private volatile boolean closed;

    F5LocalTtsEngine(Context context) {
        if (context == null) throw new IllegalArgumentException("Missing Android context.");
        appContext = context.getApplicationContext();
    }

    synchronized Audio synthesize(Context context, String text) throws Exception {
        if (closed) throw new IllegalStateException("Voice engine is closed.");
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("No text to synthesize.");

        Throwable f5Failure = null;

        // 1) Verified F5 Myanmar base/default voice. No ZIP and no reference WAV are needed.
        File onlineTemp = new File(appContext.getCacheDir(), "f5-myanmar-default-online.wav");
        try {
            onlineF5.synthesizeDefaultToFile(clean, 1.0f, onlineTemp);
            Audio generated = readWav(onlineTemp);
            //noinspection ResultOfMethodCallIgnored
            onlineTemp.delete();
            if (generated.samples.length > 0) return generated;
            f5Failure = new IllegalStateException("Online F5 default voice returned no audio samples.");
        } catch (Throwable problem) {
            f5Failure = problem;
            // Network queue/cold-start/service errors must never close the audiobook app.
            //noinspection ResultOfMethodCallIgnored
            onlineTemp.delete();
        }

        // 2) Existing Gemini key, when available, gives a high-quality safe online fallback.
        String apiKey = SecureApiKeyStore.getGeminiKey(appContext);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            File temp = new File(appContext.getCacheDir(), "aung-gyi-safe-gemini.wav");
            try {
                gemini.synthesizeToFile(
                        clean,
                        apiKey,
                        VoiceSettings.geminiModel(appContext),
                        VoiceSettings.geminiVoice(appContext),
                        VoiceSettings.effectiveGeminiStyle(appContext),
                        temp);
                Audio online = readWav(temp);
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                if (online.samples.length > 0) return online;
            } catch (Throwable ignored) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }

        // Important: do NOT call MmsMyanmarTtsEngine here. A native runtime failure there cannot be
        // caught by Java and was the reason Play could throw the user back to Home. Let the service
        // use the normal Edge Myanmar fallback instead.
        String reason = f5Failure == null ? "F5 Myanmar default voice is unavailable." : safeMessage(f5Failure);
        throw new IllegalStateException("F5 Myanmar default voice unavailable: " + reason, f5Failure);
    }

    /** Read common PCM/float WAV output into mono float samples. */
    private static Audio readWav(File file) throws Exception {
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
                if (size < 0 || in.getFilePointer() + (long) size > in.length()) {
                    throw new IllegalArgumentException("Malformed generated WAV.");
                }
                long start = in.getFilePointer();
                if ("fmt ".equals(chunk)) {
                    format = readLe16(in);
                    channels = readLe16(in);
                    sampleRate = readLe32(in);
                    readLe32(in); // byte rate
                    readLe16(in); // block align
                    bits = readLe16(in);
                } else if ("data".equals(chunk)) {
                    dataOffset = start;
                    dataSize = size;
                }
                in.seek(start + size + (size & 1));
            }

            if ((format != 1 && format != 3) || channels < 1 || channels > 2
                    || sampleRate < 8000 || sampleRate > 96000 || dataOffset < 0 || dataSize <= 0) {
                throw new IllegalArgumentException("Unsupported generated WAV format.");
            }
            int bytesPerSample = bits / 8;
            if (bytesPerSample <= 0 || (format == 3 && bits != 32)
                    || (format == 1 && bits != 16 && bits != 24 && bits != 32)) {
                throw new IllegalArgumentException("Unsupported generated WAV bit depth.");
            }
            int frameBytes = bytesPerSample * channels;
            int frames = dataSize / frameBytes;
            if (frames <= 0) throw new IllegalArgumentException("Generated WAV contains no samples.");
            float[] samples = new float[frames];
            in.seek(dataOffset);
            for (int frame = 0; frame < frames; frame++) {
                float sum = 0f;
                for (int ch = 0; ch < channels; ch++) {
                    sum += readSample(in, format, bits);
                }
                samples[frame] = Math.max(-1f, Math.min(1f, sum / channels));
            }
            return new Audio(samples, sampleRate);
        }
    }

    private static float readSample(RandomAccessFile in, int format, int bits) throws Exception {
        if (format == 3 && bits == 32) {
            return Float.intBitsToFloat(readLe32(in));
        }
        if (bits == 16) {
            int lo = in.readUnsignedByte();
            int hi = in.readUnsignedByte();
            short pcm = (short) ((hi << 8) | lo);
            return pcm / 32768.0f;
        }
        if (bits == 24) {
            int value = in.readUnsignedByte()
                    | (in.readUnsignedByte() << 8)
                    | (in.readUnsignedByte() << 16);
            if ((value & 0x800000) != 0) value |= 0xff000000;
            return value / 8388608.0f;
        }
        return readLe32(in) / 2147483648.0f;
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

    private static String safeMessage(Throwable problem) {
        String message = problem == null ? "" : problem.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = problem == null ? "unknown error" : problem.getClass().getSimpleName();
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() <= 180 ? message : message.substring(0, 180) + "…";
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { onlineF5.close(); } catch (Throwable ignored) { }
        try { gemini.close(); } catch (Throwable ignored) { }
    }
}
