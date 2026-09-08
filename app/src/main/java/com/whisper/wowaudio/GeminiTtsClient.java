package com.whisper.wowaudio;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class GeminiTtsClient {
    static final String MODEL = "gemini-3.1-flash-tts-preview";
    private static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final int MAX_CHARS_PER_REQUEST = 1400;
    private static final int MAX_ATTEMPTS = 3;

    interface Progress {
        void onChunk(int completed, int total);
    }

    GenerationResult generateToWav(String apiKey, String text, String voice, String style, File output, Progress progress) throws Exception {
        if (empty(apiKey)) throw new Exception("Gemini API key is missing");
        if (empty(text)) throw new Exception("Chapter has no readable text");
        String speechText = MyanmarTextNormalizer.normalizeForSpeech(text);
        if (empty(speechText)) throw new Exception("Chapter has no speakable text");
        List<String> chunks = chunk(speechText);
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        List<AudioTimingStore.Segment> timings = new ArrayList<>();
        int outputSampleRate = 24000;
        long cursorMs = 0;
        for (int i = 0; i < chunks.size(); i++) {
            AudioBlock block = request(apiKey.trim(), chunks.get(i), voice, style);
            int sampleRate = block.sampleRate > 0 ? block.sampleRate : 24000;
            if (i == 0) outputSampleRate = sampleRate;
            byte[] blockPcm = asPcm(block.data);
            pcm.write(blockPcm);
            long durationMs = pcmDurationMs(blockPcm, sampleRate);
            appendSentenceTimings(timings, chunks.get(i), cursorMs, durationMs);
            cursorMs += durationMs;
            if (progress != null) progress.onChunk(i + 1, chunks.size());
        }
        WavUtil.writePcm16Mono(output, pcm.toByteArray(), outputSampleRate);
        AudioTimingStore.write(output, timings);
        return new GenerationResult(speechText, timings);
    }

    private AudioBlock request(String apiKey, String text, String voice, String style) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(30000);
                c.setReadTimeout(180000);
                c.setDoOutput(true);
                c.setRequestProperty("x-goog-api-key", apiKey);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setRequestProperty("Api-Revision", "2026-05-20");

                JSONObject body = new JSONObject();
                body.put("model", MODEL);
                body.put("input", prompt(style, text));
                body.put("response_format", new JSONObject().put("type", "audio"));
                JSONArray speech = new JSONArray().put(new JSONObject().put("voice", empty(voice) ? NarrationSettings.DEFAULT_VOICE : voice));
                body.put("generation_config", new JSONObject().put("speech_config", speech));
                byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(request.length);
                try (OutputStream out = c.getOutputStream()) { out.write(request); }

                int code = c.getResponseCode();
                String response = readText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
                if (code < 200 || code >= 300) {
                    ApiException api = new ApiException(code, apiError(code, response));
                    if (retryable(code) && attempt < MAX_ATTEMPTS) {
                        last = api;
                        sleepBackoff(attempt);
                        continue;
                    }
                    throw api;
                }
                return parseAudio(response);
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempt >= MAX_ATTEMPTS) throw e;
                sleepBackoff(attempt);
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last == null ? new Exception("Gemini request failed") : last;
    }

    private AudioBlock parseAudio(String response) throws Exception {
        JSONObject root = new JSONObject(response);
        JSONArray steps = root.optJSONArray("steps");
        if (steps != null) {
            for (int i = steps.length() - 1; i >= 0; i--) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null || !"model_output".equals(step.optString("type"))) continue;
                JSONArray content = step.optJSONArray("content");
                if (content == null) continue;
                for (int j = content.length() - 1; j >= 0; j--) {
                    JSONObject item = content.optJSONObject(j);
                    if (item == null || !"audio".equals(item.optString("type"))) continue;
                    String data = item.optString("data", "");
                    if (data.isEmpty()) continue;
                    return new AudioBlock(Base64.decode(data, Base64.DEFAULT), item.optInt("sample_rate", 24000), item.optString("mime_type", "audio/l16"));
                }
            }
        }
        throw new Exception("Gemini returned no audio");
    }

    private static String prompt(String style, String text) {
        String direction = empty(style) ? NarrationSettings.DEFAULT_STYLE : style.trim();
        return direction + "\n\nRead only the following prepared book text. Do not add, omit, translate, summarize, or explain anything:\n\n" + text;
    }

    static List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        String value = text == null ? "" : text.trim();
        int start = 0;
        while (start < value.length()) {
            int end = Math.min(value.length(), start + MAX_CHARS_PER_REQUEST);
            if (end < value.length()) {
                int best = -1;
                for (int i = end; i > start + MAX_CHARS_PER_REQUEST / 2; i--) {
                    char ch = value.charAt(i - 1);
                    if (ch == '\n' || ch == '။' || ch == '.' || ch == '!' || ch == '?' || ch == '၊') { best = i; break; }
                }
                if (best > start) end = best;
            }
            String part = value.substring(start, end).trim();
            if (!part.isEmpty()) out.add(part);
            start = end;
        }
        if (out.isEmpty() && !value.isEmpty()) out.add(value);
        return out;
    }

    private static void appendSentenceTimings(List<AudioTimingStore.Segment> output, String chunk, long startMs, long durationMs) {
        List<String> sentences = sentenceUnits(chunk);
        if (sentences.isEmpty()) {
            output.add(new AudioTimingStore.Segment(chunk, startMs, startMs + durationMs));
            return;
        }
        int totalWeight = 0;
        for (String sentence : sentences) totalWeight += Math.max(1, speechWeight(sentence));
        long cursor = startMs;
        long chunkEnd = startMs + Math.max(1, durationMs);
        int usedWeight = 0;
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            int weight = Math.max(1, speechWeight(sentence));
            usedWeight += weight;
            long end = i == sentences.size() - 1
                    ? chunkEnd
                    : startMs + Math.round(durationMs * (usedWeight / (double) Math.max(1, totalWeight)));
            end = Math.max(cursor + 1, Math.min(chunkEnd, end));
            output.add(new AudioTimingStore.Segment(sentence, cursor, end));
            cursor = end;
        }
    }

    private static List<String> sentenceUnits(String text) {
        List<String> result = new ArrayList<>();
        if (empty(text)) return result;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            boolean boundary = c == '။' || c == '.' || c == '!' || c == '?' || c == '\n';
            if (boundary && current.toString().trim().length() > 1) {
                String sentence = current.toString().trim();
                if (!sentence.isEmpty()) result.add(sentence);
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) result.add(tail);
        return result;
    }

    private static int speechWeight(String text) {
        int weight = 0;
        for (int i = 0; i < text.length(); i++) if (!Character.isWhitespace(text.charAt(i))) weight++;
        return weight;
    }

    private static long pcmDurationMs(byte[] pcm, int sampleRate) {
        if (pcm == null || sampleRate <= 0) return 0;
        return Math.max(1L, Math.round((pcm.length * 1000.0) / (sampleRate * 2.0)));
    }

    private static byte[] asPcm(byte[] data) {
        if (data == null) return new byte[0];
        if (data.length > 44 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') {
            int offset = findData(data);
            if (offset > 0 && offset < data.length) {
                byte[] pcm = new byte[data.length - offset];
                System.arraycopy(data, offset, pcm, 0, pcm.length);
                return pcm;
            }
        }
        return data;
    }

    private static int findData(byte[] wav) {
        for (int i = 12; i + 8 <= wav.length; ) {
            if (wav[i] == 'd' && wav[i + 1] == 'a' && wav[i + 2] == 't' && wav[i + 3] == 'a') return i + 8;
            int size = (wav[i + 4] & 0xff) | ((wav[i + 5] & 0xff) << 8) | ((wav[i + 6] & 0xff) << 16) | ((wav[i + 7] & 0xff) << 24);
            if (size < 0) break;
            i += 8 + size + (size & 1);
        }
        return 44;
    }

    private static String readText(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = source.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static boolean retryable(int code) { return code == 408 || code == 429 || code >= 500; }

    private static void sleepBackoff(int attempt) throws InterruptedException {
        Thread.sleep(Math.min(6000L, 750L * (1L << Math.max(0, attempt - 1))));
    }

    private static String apiError(int code, String response) {
        try {
            JSONObject root = new JSONObject(response);
            JSONObject error = root.optJSONObject("error");
            if (error != null && !error.optString("message").isEmpty()) return "Gemini " + code + ": " + error.optString("message");
        } catch (Exception ignored) { }
        return "Gemini request failed (HTTP " + code + ")";
    }

    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }

    static final class GenerationResult {
        final String speechText;
        final List<AudioTimingStore.Segment> timings;
        GenerationResult(String speechText, List<AudioTimingStore.Segment> timings) {
            this.speechText = speechText;
            this.timings = timings;
        }
    }

    private static final class ApiException extends Exception {
        final int statusCode;
        ApiException(int statusCode, String message) { super(message); this.statusCode = statusCode; }
    }

    private static final class AudioBlock {
        final byte[] data;
        final int sampleRate;
        final String mimeType;
        AudioBlock(byte[] data, int sampleRate, String mimeType) {
            this.data = data; this.sampleRate = sampleRate; this.mimeType = mimeType;
        }
    }
}
