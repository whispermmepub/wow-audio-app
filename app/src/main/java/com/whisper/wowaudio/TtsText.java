package com.whisper.wowaudio;

import java.util.ArrayList;
import java.util.List;

final class TtsText {
    private static final int MAX_CHARS = 520;

    private TtsText() { }

    static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        int start = 0;
        int length = text.length();
        while (start < length) {
            while (start < length && Character.isWhitespace(text.charAt(start))) start++;
            if (start >= length) break;
            int limit = Math.min(length, start + MAX_CHARS);
            int end = findBreak(text, start, limit);
            if (end <= start) end = limit;
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) out.add(chunk);
            start = end;
        }
        return out;
    }

    private static int findBreak(String text, int start, int limit) {
        if (limit >= text.length()) return text.length();
        int floor = start + Math.max(90, (limit - start) / 2);
        for (int i = limit - 1; i >= floor; i--) {
            char c = text.charAt(i);
            if (c == '။' || c == '၊' || c == '.' || c == '!' || c == '?' || c == '\n') return i + 1;
        }
        for (int i = limit - 1; i >= floor; i--) if (Character.isWhitespace(text.charAt(i))) return i + 1;
        return limit;
    }
}
