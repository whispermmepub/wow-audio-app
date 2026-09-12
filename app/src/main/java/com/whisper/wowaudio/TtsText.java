package com.whisper.wowaudio;

import java.util.ArrayList;
import java.util.List;

final class TtsText {
    private static final int FIRST_MAX_CHARS = 170;
    private static final int NEXT_MAX_CHARS = 360;

    private TtsText() { }

    /**
     * Keep first speech fast, then use shorter phrase-focused chunks so narration can react to
     * Burmese punctuation and structural line breaks without changing the TTS provider.
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
            int end = findBreak(normalized, start, limit, first ? 42 : 80);
            if (end <= start) end = limit;

            String rawChunk = normalized.substring(start, end);
            boolean structuralBreak = rawChunk.indexOf('\n') >= 0;
            String chunk = clean(rawChunk);
            if (!chunk.isEmpty() && containsMyanmar(chunk)) {
                // Preserve an invisible structural cue for the playback prosody layer. The Edge
                // client trims it before synthesis, so it is never spoken, but it lets the service
                // distinguish a heading/paragraph boundary from an ordinary text split.
                if (structuralBreak) chunk = chunk + "\n";
                out.add(chunk);
                first = false;
            }
            start = end;
        }
        return out;
    }

    /**
     * Normalizes text once before every voice engine sees it. Some EPUB generators store escaped
     * line endings (\\n / \\r / \\t) or stray runs such as "n n n n" in text nodes. Escaped line
     * endings are converted to REAL newlines so heading/body boundaries can become audible pauses.
     * Isolated n filler is removed, but its surrounding structural newlines are preserved.
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

        // Remove malformed EPUB filler that consists only of isolated Latin n tokens. Keep the
        // line itself empty rather than replacing it with a space; this preserves a heading/body
        // or paragraph boundary for the pause engine.
        s = s.replaceAll("(?im)^[ \\t]*(?:[nN][ \\t]+){1,}[nN][ \\t]*$", "")
                .replaceAll("(?im)^[ \\t]*[nN][ \\t]*$", "")
                .replaceAll("(?i)(?<![A-Za-z])n(?:[ \\t]+n){1,}(?![A-Za-z])", " ");

        return clean(s);
    }

    private static int findBreak(String text, int start, int limit, int minChars) {
        if (start >= limit) return limit;
        int floor = Math.min(limit - 1, start + Math.max(18, minChars));

        // Structural newlines must not be swallowed. They are hard reading boundaries, even for
        // a short heading, so heading/body text cannot run together.
        for (int i = start; i + 1 < limit; i++) {
            if (text.charAt(i) == '\n' && text.charAt(i + 1) == '\n') return i + 2;
        }
        for (int i = start; i < limit; i++) {
            if (text.charAt(i) == '\n') return i + 1;
        }

        // Full Burmese sentence endings are hard boundaries. A short sentence must still stop at
        // '။' rather than run into the next sentence, including when the whole remaining text is
        // shorter than the nominal chunk size.
        for (int i = start; i < limit; i++) {
            char c = text.charAt(i);
            if (c == '။' || c == '!' || c == '?' || c == '…') return includeClosingQuotes(text, i + 1, limit);
            if (c == '.' && englishSentenceEnd(text, i)) return includeClosingQuotes(text, i + 1, limit);
        }

        // Burmese phrase comma also deserves a short breath, even in a short phrase.
        for (int i = start; i < limit; i++) {
            char c = text.charAt(i);
            if (c == '၊' || c == ',' || c == ';' || c == ':') return includeClosingQuotes(text, i + 1, limit);
        }

        // If the rest of the text already fits, keep it as the final segment after checking every
        // meaningful boundary above.
        if (limit >= text.length()) return text.length();

        // Only arbitrary whitespace splitting keeps the minimum-length guard.
        for (int i = limit - 1; i >= floor; i--) {
            if (Character.isWhitespace(text.charAt(i))) return i + 1;
        }
        return limit;
    }

    private static boolean englishSentenceEnd(String text, int index) {
        int next = index + 1;
        if (next >= text.length()) return true;
        char c = text.charAt(next);
        return Character.isWhitespace(c) || c == '”' || c == '’' || c == '"' || c == '\'' || c == ')' || c == ']';
    }

    private static int includeClosingQuotes(String text, int end, int limit) {
        int out = end;
        while (out < limit && out < text.length()) {
            char c = text.charAt(out);
            if (c == '”' || c == '’' || c == '"' || c == '\'' || c == '»' || c == ')' || c == ']') out++;
            else break;
        }
        return out;
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
