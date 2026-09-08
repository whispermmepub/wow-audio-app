package com.whisper.wowaudio;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MyanmarTextNormalizer {
    private static final Pattern DATE = Pattern.compile("([0-9\u1040-\u1049]{1,2})[/-]([0-9\u1040-\u1049]{1,2})[/-]([0-9\u1040-\u1049]{2,4})");
    private static final Pattern TIME = Pattern.compile("([0-9\u1040-\u1049]{1,2}):([0-9\u1040-\u1049]{1,2})");
    private static final Pattern PERCENT = Pattern.compile("([0-9\u1040-\u1049]+(?:[.,][0-9\u1040-\u1049]+)?)\\s*%");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z])([0-9\u1040-\u1049]+(?:[.,][0-9\u1040-\u1049]+)?)(?![A-Za-z])");
    private static final String[] DIGITS = {"သုည", "တစ်", "နှစ်", "သုံး", "လေး", "ငါး", "ခြောက်", "ခုနစ်", "ရှစ်", "ကိုး"};
    private static final long[] PLACES = {100000L, 10000L, 1000L, 100L, 10L, 1L};
    private static final String[] PLACE_NAMES = {"သိန်း", "သောင်း", "ထောင်", "ရာ", "ဆယ်", ""};

    private MyanmarTextNormalizer() { }

    static String normalizeForSpeech(String input) {
        if (input == null || input.trim().isEmpty()) return "";
        String value = Normalizer.normalize(input, Normalizer.Form.NFC)
                .replace('\u00a0', ' ')
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\uFEFF", "")
                .replace("...", "…")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n")
                .trim();

        if (containsMyanmar(value)) {
            value = replaceDates(value);
            value = replaceTimes(value);
            value = replacePercents(value);
            value = replaceNumbers(value);
        }

        value = value
                .replaceAll("\\s*။\\s*", "။\n")
                .replaceAll("\\s*၊\\s*", "၊ ")
                .replaceAll("\\s*…\\s*", " … ")
                .replaceAll("(?<=\\p{Lu})\\.(?=\\p{Lu}\\.)", " ")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n +", "\n")
                .replaceAll(" +\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return value;
    }

    private static String replaceDates(String input) {
        Matcher m = DATE.matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String day = numberWords(m.group(1));
            String month = numberWords(m.group(2));
            String year = numberWords(m.group(3));
            m.appendReplacement(out, Matcher.quoteReplacement(day + " ရက် " + month + " လ " + year + " ခုနှစ်"));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String replaceTimes(String input) {
        Matcher m = TIME.matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String hour = numberWords(m.group(1));
            String minute = numberWords(m.group(2));
            String spoken = hour + " နာရီ" + (numericValue(m.group(2)) == 0 ? "" : " " + minute + " မိနစ်");
            m.appendReplacement(out, Matcher.quoteReplacement(spoken));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String replacePercents(String input) {
        Matcher m = PERCENT.matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(numberWords(m.group(1)) + " ရာခိုင်နှုန်း"));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String replaceNumbers(String input) {
        Matcher m = NUMBER.matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(numberWords(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String numberWords(String token) {
        String ascii = toAsciiDigits(token).replace(",", "");
        int dot = ascii.indexOf('.');
        if (dot >= 0) {
            String whole = ascii.substring(0, dot);
            String fraction = ascii.substring(dot + 1);
            StringBuilder out = new StringBuilder(integerWords(parseLongSafe(whole)));
            if (!fraction.isEmpty()) {
                out.append(" ဒသမ");
                for (int i = 0; i < fraction.length(); i++) {
                    char c = fraction.charAt(i);
                    if (c >= '0' && c <= '9') out.append(' ').append(DIGITS[c - '0']);
                }
            }
            return out.toString().trim();
        }
        return integerWords(parseLongSafe(ascii));
    }

    private static String integerWords(long value) {
        if (value == 0) return DIGITS[0];
        if (value < 0) return "အနုတ် " + integerWords(Math.abs(value));
        if (value >= 1_000_000L) {
            long millions = value / 1_000_000L;
            long rest = value % 1_000_000L;
            return integerWords(millions) + " သန်း" + (rest == 0 ? "" : " " + integerWords(rest));
        }
        StringBuilder out = new StringBuilder();
        long remaining = value;
        for (int i = 0; i < PLACES.length; i++) {
            long place = PLACES[i];
            long digit = remaining / place;
            if (digit <= 0) continue;
            remaining %= place;
            if (out.length() > 0) out.append(' ');
            if (place == 10L && digit == 1L) out.append("ဆယ်");
            else {
                out.append(DIGITS[(int) digit]);
                if (!PLACE_NAMES[i].isEmpty()) out.append(' ').append(PLACE_NAMES[i]);
            }
        }
        return out.toString().trim();
    }

    private static long parseLongSafe(String value) {
        try { return Long.parseLong(value.isEmpty() ? "0" : value); }
        catch (Exception ignored) {
            long out = 0;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c >= '0' && c <= '9') {
                    if (out > Long.MAX_VALUE / 10L) return out;
                    out = out * 10L + (c - '0');
                }
            }
            return out;
        }
    }

    private static long numericValue(String value) { return parseLongSafe(toAsciiDigits(value)); }

    private static String toAsciiDigits(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '\u1040' && c <= '\u1049') out.append((char) ('0' + (c - '\u1040')));
            else out.append(c);
        }
        return out.toString();
    }

    private static boolean containsMyanmar(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '\u1000' && c <= '\u109F') || (c >= '\uAA60' && c <= '\uAA7F')) return true;
        }
        return false;
    }
}
