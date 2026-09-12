package com.whisper.wowaudio;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class AudioCache {
    private AudioCache() { }

    static File edge(BookStore.Book book, int index, String voice, float speed, String text) {
        return SpeechCache.online(book, index, voice, speed, text);
    }

    static File offline(BookStore.Book book, int index, float speed, String text) {
        return SpeechCache.offline(book, index, speed, text);
    }

    static File f5(BookStore.Book book, int index, String text) {
        File dir = new File(book.directory, "speech-cache/f5/" + F5MyanmarVoicePack.VOICE_ID_AUNG_GYI);
        String signature = sha256(F5MyanmarVoicePack.RUNTIME_VERSION + "\n" + text);
        return new File(dir, String.format(Locale.US, "%05d-%s.wav", index, signature.substring(0, 16)));
    }

    static File gemini(BookStore.Book book, int index, String model, String voice,
                       String style, String text) {
        String profile = safe(model) + "__" + safe(voice);
        File dir = new File(book.directory, "speech-cache/gemini/" + profile);
        String signature = sha256(BurmeseProsody.RENDER_VERSION + "\n" + model + "\n" + voice + "\n" + style + "\n" + text);
        return new File(dir, String.format(Locale.US, "%05d-%s.wav", index, signature.substring(0, 16)));
    }

    static File existing(BookStore.Book book, int index, String engine, String edgeVoice,
                         String geminiModel, String geminiVoice, String geminiStyle,
                         float speed, String text) {
        if (VoiceSettings.ENGINE_F5.equals(engine)) {
            File f = f5(book, index, text);
            if (f.isFile() && f.length() > 44) return f;
        } else if (VoiceSettings.ENGINE_GEMINI.equals(engine)) {
            File f = gemini(book, index, geminiModel, geminiVoice, geminiStyle, text);
            if (f.isFile() && f.length() > 44) return f;
        } else if (VoiceSettings.ENGINE_OFFLINE.equals(engine)) {
            File f = offline(book, index, speed, text);
            if (f.isFile() && f.length() > 44) return f;
        } else {
            File f = edge(book, index, edgeVoice, speed, text);
            if (f.isFile() && f.length() > 1024) return f;
        }
        return null;
    }

    static void ensureParent(File file) {
        File parent = file == null ? null : file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
    }

    private static String safe(String value) {
        return value == null ? "value" : value.replaceAll("[^A-Za-z0-9._-]", "_");
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
