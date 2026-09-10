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
        if (text == null) return out;
        int start = 0;
        int length = text.length();
        boolean first = true;
        while (start < length) {
            while (start < length && Character.isWhitespace(text.charAt(start))) start++;
            if (start >= length) break;

            int max = first ? FIRST_MAX_CHARS : NEXT_MAX_CHARS;
            int limit = Math.min(length, start + max);
            int end = findBreak(text, start, limit, first ? 60 : 180);
            if (end <= start) end = limit;
            String chunk = clean(text.substring(start, end));
            if (!chunk.isEmpty() && containsMyanmar(chunk)) {
                out.add(chunk);
                first = false;
            }
            start = end;
        }
        return out;
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
        return value.replace('\u00a0', ' ')
                .replace("\u200B", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n[ \\t]+", "\\n")
                .replaceAll("\\n{3,}", "\\n\\n")
                .trim();
    }
}
