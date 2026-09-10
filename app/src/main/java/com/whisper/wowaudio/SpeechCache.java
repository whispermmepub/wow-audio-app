package com.whisper.wowaudio;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Persistent per-book speech cache. Deleting a book deletes its cached speech too. */
final class SpeechCache {
    private SpeechCache() { }

    static File online(BookStore.Book book, int index, String voice, float speed, String text) {
        return file(book, "online", index, voice, speed, text, ".mp3");
    }

    static File offline(BookStore.Book book, int index, float speed, String text) {
        return file(book, "offline", index, "mms-mya", speed, text, ".wav");
    }

    static File existing(BookStore.Book book, int index, String voice, float speed, String text) {
        File online = online(book, index, voice, speed, text);
        if (online.isFile() && online.length() > 1024) return online;
        File offline = offline(book, index, speed, text);
        if (offline.isFile() && offline.length() > 44) return offline;
        return null;
    }

    private static File file(BookStore.Book book, String engine, int index, String voice,
                             float speed, String text, String extension) {
        String voiceId = safe(voice);
        File dir = new File(book.directory, "speech-cache/" + engine + "/" + voiceId);
        String signature = sha256(voice + "\n" + String.format(Locale.US, "%.2f", speed)
                + "\n" + text);
        return new File(dir, String.format(Locale.US, "%05d-%s%s", index, signature.substring(0, 16), extension));
    }

    static void ensureParent(File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
    }

    private static String safe(String value) {
        return value == null ? "voice" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
