package com.whisper.wowaudio;

import java.util.ArrayList;
import java.util.List;

final class TtsText {
    private static final int FIRST_MAX_CHARS = 170;
    private static final int NEXT_MAX_CHARS = 620;

    private TtsText() { }

    /**
     * Small first segment gives fast first speech; subsequent larger segments give the prefetch
     * thread enough playback time to stay ahead. Boundaries prefer Burmese sentence punctuation.
     */
    static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        String normalized = normalizeForSpeech(text);
        if (normalized.isEmpty()) return out;
        int start = 0;
        int length = normalized.length();
        boolean first = true;
        while (start < length) {
            while (start < length && Character.isWhitespace(normalized.charAt(start))) start++;
            if (start >= length) break;

            int max = first ? FIRST_MAX_CHARS : NEXT_MAX_CHARS;
            int limit = Math.min(length, start + max);
            int end = findBreak(normalized, start, limit, first ? 60 : 180);
            if (end <= start) end = limit;
            String chunk = clean(normalized.substring(start, end));
            if (!chunk.isEmpty() && containsMyanmar(chunk)) {
                out.add(chunk);
                first = false;
            }
            start = end;
        }
        return out;
    }

    /**
     * Normalizes text once before every voice engine sees it. Some EPUB generators accidentally
     * store escaped line endings (\\n / \\r / \\t) or stray runs such as "n n n n" in text
     * nodes. Those artifacts should never be narrated, but ordinary English words are preserved.
     */
    static String normalizeForSpeech(String value) {
        if (value == null) return "";
        String s = value
                .replace('\u00a0', ' ')
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\uFEFF", "")
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n")
                .replace("\\t", " ")
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        // Remove malformed EPUB filler that consists only of isolated Latin n tokens.
        s = s.replaceAll("(?im)^[ \\t]*(?:[nN][ \\t]+){1,}[nN][ \\t]*$", "")
                .replaceAll("(?im)^[ \\t]*[nN][ \\t]*$", "")
                .replaceAll("(?i)(?<![A-Za-z])n(?:[ \\t]+n){1,}(?![A-Za-z])", " ");

        return clean(s);
    }

    private static int findBreak(String text, int start, int limit, int minChars) {
        if (limit >= text.length()) return text.length();
        int floor = Math.min(limit - 1, start + Math.max(24, minChars));
        for (int i = limit - 1; i >= floor; i--) {
            char c = text.charAt(i);
            if (c == '။' || c == '!' || c == '?' || c == '\n') return i + 1;
        }
        for (int i = limit - 1; i >= floor; i--) {
            char c = text.charAt(i);
            if (c == '၊' || c == ',' || c == ';' || c == ':') return i + 1;
        }
        for (int i = limit - 1; i >= floor; i--) {
            if (Character.isWhitespace(text.charAt(i))) return i + 1;
        }
        return limit;
    }

    private static boolean containsMyanmar(String value) {
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if ((cp >= 0x1000 && cp <= 0x109F)
                    || (cp >= 0xAA60 && cp <= 0xAA7F)
                    || (cp >= 0xA9E0 && cp <= 0xA9FF)) return true;
        }
        return false;
    }

    private static String clean(String value) {
        return value.replaceAll("[ \\t]+", " ")
                .replaceAll("\\n[ \\t]+", "\\n")
                .replaceAll("[ \\t]+\\n", "\\n")
                .replaceAll("\\n{3,}", "\\n\\n")
                .trim();
    }
}
