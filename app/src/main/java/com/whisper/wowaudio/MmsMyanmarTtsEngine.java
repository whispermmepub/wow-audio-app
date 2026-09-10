package com.whisper.wowaudio;

import android.content.Context;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

/**
 * Built-in, fully offline Burmese TTS.
 *
 * Runtime: sherpa-onnx (Apache-2.0)
 * Voice model: Meta MMS-TTS Burmese, converted to Sherpa-compatible ONNX by willwade
 * (CC BY-NC 4.0). This app is intentionally free/non-commercial.
 */
final class MmsMyanmarTtsEngine implements AutoCloseable {
    static final String MODEL_ASSET = "tts/mya/model.onnx";
    static final String TOKENS_ASSET = "tts/mya/tokens.txt";

    static final class Audio {
        final float[] samples;
        final int sampleRate;

        Audio(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    private final OfflineTts tts;

    MmsMyanmarTtsEngine(Context context) {
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(MODEL_ASSET);
        vits.setTokens(TOKENS_ASSET);
        vits.setLexicon("");
        vits.setDataDir("");
        vits.setNoiseScale(0.667f);
        vits.setNoiseScaleW(0.8f);
        vits.setLengthScale(1.0f);

        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setVits(vits);
        model.setNumThreads(bestThreadCount());
        model.setDebug(false);
        model.setProvider("cpu");

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(model);
        config.setMaxNumSentences(1);
        config.setSilenceScale(0.18f);

        tts = new OfflineTts(context.getAssets(), config);
        if (tts.sampleRate() <= 0) {
            tts.release();
            throw new IllegalStateException("Built-in Myanmar voice returned an invalid sample rate.");
        }
    }

    Audio synthesize(String text, float speed) {
        String normalized = normalizeForModel(text);
        if (normalized.isEmpty()) {
            return new Audio(new float[0], tts.sampleRate());
        }
        float safeSpeed = Math.max(0.65f, Math.min(1.6f, speed));
        GeneratedAudio generated = tts.generate(normalized, 0, safeSpeed);
        if (generated == null || generated.getSamples() == null || generated.getSamples().length == 0) {
            throw new IllegalStateException("Built-in Myanmar voice produced no audio.");
        }
        return new Audio(generated.getSamples(), generated.getSampleRate());
    }

    int sampleRate() {
        return tts.sampleRate();
    }

    @Override public void close() {
        tts.release();
    }

    private static int bestThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        if (cores >= 8) return 4;
        if (cores >= 4) return 2;
        return 1;
    }

    static String normalizeForModel(String value) {
        if (value == null) return "";
        String s = value
                .replace('\u00a0', ' ')
                .replace("\u200B", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('၊', ' ')
                .replace('။', ' ')
                .replace(',', ' ')
                .replace('.', ' ')
                .replace('!', ' ')
                .replace('?', ' ')
                .replace(':', ' ')
                .replace(';', ' ')
                .replace('“', ' ')
                .replace('”', ' ')
                .replace('‘', ' ')
                .replace('’', ' ')
                .replace('(', ' ')
                .replace(')', ' ')
                .replace('[', ' ')
                .replace(']', ' ')
                .replace('{', ' ')
                .replace('}', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ');

        // MMS Burmese is a character frontend. Keep Burmese code points plus spaces;
        // unsupported Latin/punctuation is dropped instead of being read as garbage.
        StringBuilder out = new StringBuilder(s.length());
        boolean lastSpace = true;
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            boolean myanmar = (cp >= 0x1000 && cp <= 0x109F)
                    || (cp >= 0xAA60 && cp <= 0xAA7F)
                    || (cp >= 0xA9E0 && cp <= 0xA9FF);
            if (myanmar) {
                out.appendCodePoint(cp);
                lastSpace = false;
            } else if (Character.isWhitespace(cp) && !lastSpace) {
                out.append(' ');
                lastSpace = true;
            }
        }
        return out.toString().trim();
    }
}
