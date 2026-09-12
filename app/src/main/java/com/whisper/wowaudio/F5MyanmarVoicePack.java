package com.whisper.wowaudio;

import android.content.Context;

import java.io.File;

/**
 * Local-only F5 Myanmar custom voice pack descriptor.
 *
 * The large model files are intentionally NOT committed to the public repository.
 * They live in app-private storage and are expected to come from the
 * CC BY-NC 4.0 freococo/F5-Myanmar-TTS model export.
 */
final class F5MyanmarVoicePack {
    static final String ENGINE_ID = "f5_myanmar";
    static final String VOICE_ID_AUNG_GYI = "aung_gyi";
    static final String DISPLAY_NAME_AUNG_GYI = "အောင်ကြီး";

    static final String MODEL_REPO = "freococo/F5-Myanmar-TTS";
    static final String MODEL_LICENSE = "CC BY-NC 4.0";
    static final int SAMPLE_RATE = 24_000;

    // DakeQQ/F5-TTS-ONNX style split runtime artifacts.
    static final String PREPROCESS_MODEL = "F5_Preprocess.onnx";
    static final String TRANSFORMER_MODEL = "F5_Transformer.onnx";
    static final String DECODE_MODEL = "F5_Decode.onnx";
    static final String VOCAB_FILE = "vocab.txt";

    // A private, user-authorized reference clip should be installed separately.
    static final String REFERENCE_AUDIO = "aung-gyi-reference.wav";
    static final String REFERENCE_TEXT = "အင်နာကာရနီနာကို ဘာသာပြန်ဖြစ်ခြင်း အကြောင်းကြောင်းများ";

    private F5MyanmarVoicePack() { }

    static File root(Context context) {
        return new File(context.getFilesDir(), "voice-packs/f5-myanmar/aung-gyi");
    }

    static File preprocess(Context context) { return new File(root(context), PREPROCESS_MODEL); }
    static File transformer(Context context) { return new File(root(context), TRANSFORMER_MODEL); }
    static File decode(Context context) { return new File(root(context), DECODE_MODEL); }
    static File vocab(Context context) { return new File(root(context), VOCAB_FILE); }
    static File referenceAudio(Context context) { return new File(root(context), REFERENCE_AUDIO); }

    static boolean isInstalled(Context context) {
        return valid(preprocess(context), 1_000_000L)
                && valid(transformer(context), 10_000_000L)
                && valid(decode(context), 1_000_000L)
                && valid(vocab(context), 100L)
                && valid(referenceAudio(context), 1_000L);
    }

    static String missingReason(Context context) {
        if (!valid(preprocess(context), 1_000_000L)) return PREPROCESS_MODEL + " missing";
        if (!valid(transformer(context), 10_000_000L)) return TRANSFORMER_MODEL + " missing";
        if (!valid(decode(context), 1_000_000L)) return DECODE_MODEL + " missing";
        if (!valid(vocab(context), 100L)) return VOCAB_FILE + " missing";
        if (!valid(referenceAudio(context), 1_000L)) return REFERENCE_AUDIO + " missing";
        return "ready";
    }

    private static boolean valid(File file, long minBytes) {
        return file.isFile() && file.length() >= minBytes;
    }
}
