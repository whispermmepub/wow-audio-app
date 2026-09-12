package com.whisper.wowaudio;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Local-only F5 Myanmar custom voice pack descriptor and safe importer.
 *
 * The large model files and the private reference voice are intentionally NOT committed to the
 * public repository. Model files are a Q4 ONNX export of freococo/F5-Myanmar-TTS (CC BY-NC 4.0).
 * A user-authorized reference WAV is imported separately into app-private storage.
 */
final class F5MyanmarVoicePack {
    static final String ENGINE_ID = "f5_myanmar";
    static final String VOICE_ID_AUNG_GYI = "aung_gyi";
    static final String DISPLAY_NAME_AUNG_GYI = "အောင်ကြီး";
    static final String RUNTIME_VERSION = "f5-myanmar-q4-v1";

    static final String MODEL_REPO = "freococo/F5-Myanmar-TTS";
    static final String MODEL_LICENSE = "CC BY-NC 4.0";
    static final int SAMPLE_RATE = 24_000;
    static final int MOBILE_NFE_STEPS = 8;

    static final String PREPROCESS_MODEL = "F5_Preprocess.onnx";
    static final String TRANSFORMER_MODEL = "F5_Transformer.onnx";
    static final String DECODE_MODEL = "F5_Decode.onnx";
    static final String METADATA_MODEL = "F5_Metadata.onnx";
    static final String VOCAB_FILE = "vocab.txt";

    static final String REFERENCE_AUDIO = "aung-gyi-reference.wav";
    static final String REFERENCE_TEXT = "အင်နာကာရနီနာကို ဘာသာပြန်ဖြစ်ခြင်း အကြောင်းကြောင်းများ";

    private static final long MAX_PACK_BYTES = 360L * 1024L * 1024L;
    private static final long MAX_REFERENCE_BYTES = 4L * 1024L * 1024L;

    private static final Map<String, String> EXPECTED_SHA256 = new HashMap<>();
    static {
        EXPECTED_SHA256.put(PREPROCESS_MODEL, "f33df4dd99f976671d486df4114af3dabe943b48ecf11bb281ccc9775a256f8e");
        EXPECTED_SHA256.put(TRANSFORMER_MODEL, "38f5308163d5f79cdc078e88d41363f10e6d4d256815bab2af51e6cdcdcdc56c");
        EXPECTED_SHA256.put(DECODE_MODEL, "d84278be6c29264795fd85dba8778dc37bb86c29c13e1b0397cb5c6cabdf0c7a");
        EXPECTED_SHA256.put(METADATA_MODEL, "2129b831262547084df2d09218b4c1fcdf11c22f76e195830f4e924e3969d71c");
        EXPECTED_SHA256.put(VOCAB_FILE, "e54c50f322f8322659ce072c1ac3ee0ed78985a03111286580d8111ad6a4c2cd");
    }

    private F5MyanmarVoicePack() { }

    static File root(Context context) {
        return new File(context.getFilesDir(), "voice-packs/f5-myanmar/aung-gyi");
    }

    static File preprocess(Context context) { return new File(root(context), PREPROCESS_MODEL); }
    static File transformer(Context context) { return new File(root(context), TRANSFORMER_MODEL); }
    static File decode(Context context) { return new File(root(context), DECODE_MODEL); }
    static File metadata(Context context) { return new File(root(context), METADATA_MODEL); }
    static File vocab(Context context) { return new File(root(context), VOCAB_FILE); }
    static File referenceAudio(Context context) { return new File(root(context), REFERENCE_AUDIO); }

    static boolean modelInstalled(Context context) {
        return valid(preprocess(context), 1_000_000L)
                && valid(transformer(context), 100_000_000L)
                && valid(decode(context), 1_000_000L)
                && valid(metadata(context), 100L)
                && valid(vocab(context), 100L);
    }

    static boolean referenceInstalled(Context context) {
        return valid(referenceAudio(context), 50_000L);
    }

    static boolean isInstalled(Context context) {
        return modelInstalled(context) && referenceInstalled(context);
    }

    static String status(Context context) {
        if (isInstalled(context)) return DISPLAY_NAME_AUNG_GYI + " • ready for local generation";
        if (!modelInstalled(context) && !referenceInstalled(context)) return "Model pack + private reference voice needed";
        if (!modelInstalled(context)) return "Q4 model pack needed";
        return "Private reference WAV needed";
    }

    static String missingReason(Context context) {
        if (!valid(preprocess(context), 1_000_000L)) return PREPROCESS_MODEL + " missing";
        if (!valid(transformer(context), 100_000_000L)) return TRANSFORMER_MODEL + " missing";
        if (!valid(decode(context), 1_000_000L)) return DECODE_MODEL + " missing";
        if (!valid(metadata(context), 100L)) return METADATA_MODEL + " missing";
        if (!valid(vocab(context), 100L)) return VOCAB_FILE + " missing";
        if (!valid(referenceAudio(context), 50_000L)) return REFERENCE_AUDIO + " missing";
        return "ready";
    }

    static void installModelZip(Context context, Uri uri) throws Exception {
        if (context == null || uri == null) throw new IllegalArgumentException("Missing model pack.");
        File root = root(context);
        File incoming = new File(root.getParentFile(), ".aung-gyi-import");
        deleteRecursively(incoming);
        if (!incoming.mkdirs()) throw new IllegalStateException("Could not prepare voice-pack storage.");

        long total = 0L;
        byte[] buffer = new byte[128 * 1024];
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IllegalArgumentException("Could not open selected model pack.");
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw, 128 * 1024))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String name = new File(entry.getName()).getName();
                    if (!EXPECTED_SHA256.containsKey(name)) continue;
                    File out = new File(incoming, name);
                    try (BufferedOutputStream fileOut = new BufferedOutputStream(new FileOutputStream(out), 128 * 1024)) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) {
                            total += n;
                            if (total > MAX_PACK_BYTES) throw new IllegalArgumentException("Voice model pack is too large.");
                            fileOut.write(buffer, 0, n);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            deleteRecursively(incoming);
            throw t;
        }

        for (Map.Entry<String, String> expected : EXPECTED_SHA256.entrySet()) {
            File file = new File(incoming, expected.getKey());
            if (!file.isFile()) {
                deleteRecursively(incoming);
                throw new IllegalArgumentException("Model pack is missing " + expected.getKey());
            }
            String actual = sha256(file);
            if (!expected.getValue().equals(actual)) {
                deleteRecursively(incoming);
                throw new SecurityException("Model verification failed for " + expected.getKey());
            }
        }

        if (!root.exists() && !root.mkdirs()) {
            deleteRecursively(incoming);
            throw new IllegalStateException("Could not create voice-pack directory.");
        }
        for (String name : EXPECTED_SHA256.keySet()) {
            File src = new File(incoming, name);
            File dst = new File(root, name);
            if (dst.exists() && !dst.delete()) {
                deleteRecursively(incoming);
                throw new IllegalStateException("Could not replace " + name);
            }
            if (!src.renameTo(dst)) {
                copy(src, dst, MAX_PACK_BYTES);
                //noinspection ResultOfMethodCallIgnored
                src.delete();
            }
        }
        deleteRecursively(incoming);
    }

    static void installReferenceWav(Context context, Uri uri) throws Exception {
        if (context == null || uri == null) throw new IllegalArgumentException("Missing reference WAV.");
        File root = root(context);
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("Could not create voice-pack directory.");
        File tmp = new File(root, REFERENCE_AUDIO + ".part");
        File dst = referenceAudio(context);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("Could not open selected reference WAV.");
            copy(in, tmp, MAX_REFERENCE_BYTES);
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw t;
        }
        if (!looksLikeWav(tmp) || tmp.length() < 50_000L) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IllegalArgumentException("Reference must be a valid WAV voice sample.");
        }
        if (dst.exists() && !dst.delete()) throw new IllegalStateException("Could not replace private reference voice.");
        if (!tmp.renameTo(dst)) {
            copy(tmp, dst, MAX_REFERENCE_BYTES);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static boolean looksLikeWav(File file) {
        byte[] h = new byte[12];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(h) != h.length) return false;
            return new String(h, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(h, 8, 4, StandardCharsets.US_ASCII).equals("WAVE");
        } catch (Exception e) {
            return false;
        }
    }

    private static void copy(File src, File dst, long maxBytes) throws Exception {
        try (InputStream in = new FileInputStream(src)) { copy(in, dst, maxBytes); }
    }

    private static void copy(InputStream in, File dst, long maxBytes) throws Exception {
        byte[] buffer = new byte[128 * 1024];
        long total = 0L;
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dst), 128 * 1024)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) throw new IllegalArgumentException("Selected file is too large.");
                out.write(buffer, 0, n);
            }
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[128 * 1024];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 128 * 1024)) {
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static boolean valid(File file, long minBytes) {
        return file.isFile() && file.length() >= minBytes;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
