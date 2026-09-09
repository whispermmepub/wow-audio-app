package com.whisper.wowaudio;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class AudioCache {
    private static final String CACHE_VERSION = "v2-normalized-follow";
    private final File root;
    private final Context context;

    AudioCache(Context context) {
        this.context = context.getApplicationContext();
        root = new File(this.context.getFilesDir(), "audio-cache");
        if (!root.exists()) root.mkdirs();
    }

    File fileFor(String bookId, int chapterIndex, String text, String voice, String style) throws Exception {
        String cacheVoice = normalizeVoiceKey(voice);
        String material = CACHE_VERSION + "\n" + safe(bookId) + "\n" + chapterIndex + "\n" + cacheVoice + "\n" + safe(style) + "\n" + safe(text);
        String hash = sha256(material);
        File bookDir = new File(root, sha256(safe(bookId)).substring(0, 16));
        if (!bookDir.exists()) bookDir.mkdirs();
        return new File(bookDir, String.format("%04d-%s.wav", chapterIndex + 1, hash.substring(0, 20)));
    }

    boolean isReady(File file) { return file != null && file.isFile() && file.length() > 44; }

    boolean hasFollowData(File file) { return isReady(file) && AudioTimingStore.sidecar(file).isFile(); }

    long totalBytes() { return size(root); }

    void clear() { deleteChildren(root); }

    private String normalizeVoiceKey(String requested) {
        if (OfflineBurmeseTtsClient.ENGINE_ID.equals(requested)) return requested;
        try {
            NarrationSettings settings = new NarrationSettings(context);
            if (settings.useOfflineEngine()) return OfflineBurmeseTtsClient.ENGINE_ID;
        } catch (Exception ignored) { }
        return safe(requested);
    }

    private static long size(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] children = f.listFiles();
        if (children != null) for (File child : children) total += size(child);
        return total;
    }

    private static void deleteChildren(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) deleteChildren(child);
            child.delete();
        }
    }

    private static String sha256(String value) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte x : digest) b.append(String.format("%02x", x));
        return b.toString();
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
