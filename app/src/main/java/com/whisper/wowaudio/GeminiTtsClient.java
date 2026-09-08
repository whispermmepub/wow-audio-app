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
    private static final int MAX_CHARS_PER_REQUEST = 5000;

    interface Progress {
        void onChunk(int completed, int total);
    }

    void generateToWav(String apiKey, String text, String voice, String style, File output, Progress progress) throws Exception {
        if (empty(apiKey)) throw new Exception("Gemini API key is missing");
        if (empty(text)) throw new Exception("Chapter has no readable text");
        List<String> chunks = chunk(text);
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        int sampleRate = 24000;
        for (int i = 0; i < chunks.size(); i++) {
            AudioBlock block = request(apiKey.trim(), chunks.get(i), voice, style);
            sampleRate = block.sampleRate > 0 ? block.sampleRate : sampleRate;
            pcm.write(asPcm(block.data));
            if (progress != null) progress.onChunk(i + 1, chunks.size());
        }
        WavUtil.writePcm16Mono(output, pcm.toByteArray(), sampleRate);
    }

    private AudioBlock request(String apiKey, String text, String voice, String style) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
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
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception(apiError(code, response));

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
        return direction + "\n\nRead only the following book text. Do not add, omit, translate, summarize, or explain anything:\n\n" + text;
    }

    private static List<String> chunk(String text) {
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
        if (out.isEmpty()) out.add(value);
        return out;
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

    private static String apiError(int code, String response) {
        try {
            JSONObject root = new JSONObject(response);
            JSONObject error = root.optJSONObject("error");
            if (error != null && !error.optString("message").isEmpty()) return "Gemini " + code + ": " + error.optString("message");
        } catch (Exception ignored) { }
        return "Gemini request failed (HTTP " + code + ")";
    }

    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }

    private static final class AudioBlock {
        final byte[] data;
        final int sampleRate;
        final String mimeType;
        AudioBlock(byte[] data, int sampleRate, String mimeType) {
            this.data = data; this.sampleRate = sampleRate; this.mimeType = mimeType;
        }
    }
}
