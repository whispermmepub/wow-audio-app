package com.whisper.wowaudio;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Optional bring-your-own-key Gemini TTS provider. API keys are supplied at call time only. */
final class GeminiTtsClient implements AutoCloseable {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern RATE = Pattern.compile("rate=(\\d+)", Pattern.CASE_INSENSITIVE);

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    File synthesizeToFile(String text, String apiKey, String model, String voice,
                          String style, File target) throws Exception {
        String cleanText = text == null ? "" : text.trim();
        String cleanKey = apiKey == null ? "" : apiKey.trim();
        if (cleanText.isEmpty()) throw new IllegalArgumentException("No readable text for Gemini voice.");
        if (cleanKey.isEmpty()) throw new IllegalStateException("Gemini API key is not set.");

        String safeModel = safeModel(model);
        String safeVoice = safeVoice(voice);
        String prompt = buildPrompt(cleanText, style);

        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject content = new JSONObject();
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("text", prompt));
        content.put("parts", parts);
        contents.put(content);
        body.put("contents", contents);

        JSONObject prebuilt = new JSONObject().put("voiceName", safeVoice);
        JSONObject voiceConfig = new JSONObject().put("prebuiltVoiceConfig", prebuilt);
        JSONObject speechConfig = new JSONObject().put("voiceConfig", voiceConfig);
        JSONObject generation = new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", speechConfig);
        body.put("generationConfig", generation);

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + safeModel + ":generateContent";
        Request request = new Request.Builder()
                .url(url)
                .header("x-goog-api-key", cleanKey)
                .header("Accept", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Gemini TTS HTTP " + response.code() + ": " + errorMessage(responseBody));
            }
            JSONObject root = new JSONObject(responseBody);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                throw new IOException("Gemini TTS returned no audio candidate.");
            }
            JSONObject first = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0);
            JSONObject inline = first.optJSONObject("inlineData");
            if (inline == null) inline = first.optJSONObject("inline_data");
            if (inline == null) throw new IOException("Gemini TTS response did not contain audio data.");

            String data = inline.optString("data", "");
            if (data.isEmpty()) throw new IOException("Gemini TTS returned empty audio.");
            String mime = inline.optString("mimeType", inline.optString("mime_type", "audio/L16;rate=24000"));
            int sampleRate = parseSampleRate(mime);
            byte[] pcm = Base64.decode(data, Base64.DEFAULT);
            if (pcm.length < 1000) throw new IOException("Gemini TTS returned unusable audio.");
            writePcm16WavAtomic(target, pcm, sampleRate);
            return target;
        }
    }

    private static String buildPrompt(String text, String style) {
        String direction = style == null || style.trim().isEmpty()
                ? VoiceSettings.DEFAULT_STYLE : style.trim();
        return "Read the following Burmese audiobook passage aloud. "
                + "Do not add, remove, summarize, translate, or explain any words. "
                + "Performance direction: " + direction + "\n\nPASSAGE:\n" + text;
    }

    private static String safeModel(String model) {
        String value = model == null ? "" : model.trim();
        if (!value.matches("[A-Za-z0-9._-]{3,120}")) return VoiceSettings.DEFAULT_GEMINI_MODEL;
        return value;
    }

    private static String safeVoice(String voice) {
        String value = voice == null ? "" : voice.trim();
        if (!value.matches("[A-Za-z0-9_-]{2,80}")) return VoiceSettings.DEFAULT_GEMINI_VOICE;
        return value;
    }

    private static int parseSampleRate(String mime) {
        if (mime != null) {
            Matcher m = RATE.matcher(mime);
            if (m.find()) {
                try {
                    int rate = Integer.parseInt(m.group(1));
                    if (rate >= 8000 && rate <= 96000) return rate;
                } catch (Exception ignored) { }
            }
        }
        return 24000;
    }

    private static String errorMessage(String response) {
        try {
            JSONObject root = new JSONObject(response);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "");
                if (!message.isEmpty()) return message;
            }
        } catch (Exception ignored) { }
        String compact = response == null ? "" : response.replaceAll("\\s+", " ").trim();
        if (compact.length() > 180) compact = compact.substring(0, 180) + "…";
        return compact.isEmpty() ? "request failed" : compact;
    }

    private static void writePcm16WavAtomic(File target, byte[] pcm, int sampleRate) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create Gemini audio cache directory.");
        }
        if ((pcm.length & 1) != 0) {
            byte[] even = new byte[pcm.length - 1];
            System.arraycopy(pcm, 0, even, 0, even.length);
            pcm = even;
        }
        File temp = new File(target.getAbsolutePath() + ".part");
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(temp), 64 * 1024)) {
            ascii(out, "RIFF");
            le32(out, 36 + pcm.length);
            ascii(out, "WAVE");
            ascii(out, "fmt ");
            le32(out, 16);
            le16(out, 1);
            le16(out, 1);
            le32(out, sampleRate);
            le32(out, sampleRate * 2);
            le16(out, 2);
            le16(out, 16);
            ascii(out, "data");
            le32(out, pcm.length);
            out.write(pcm);
            out.flush();
        }
        if (target.exists() && !target.delete()) throw new IOException("Could not replace cached Gemini speech.");
        if (!temp.renameTo(target)) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(temp);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                out.getFD().sync();
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static void ascii(BufferedOutputStream out, String value) throws IOException {
        for (int i = 0; i < value.length(); i++) out.write((byte) value.charAt(i));
    }

    private static void le16(BufferedOutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void le32(BufferedOutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    @Override public void close() {
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
    }
}
