package com.whisper.wowaudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Client for the public freococo/F5-Myanmar-TTS-Demo Gradio API.
 *
 * This is intentionally a network fallback for the Aung Gyi voice. The local Q4 ONNX runtime is
 * currently unsafe on some Android devices because a native ONNX Runtime failure can terminate the
 * entire app process. The public Space runs the actual F5 Myanmar model and accepts the user's
 * imported reference WAV for zero-shot voice cloning.
 */
final class HuggingFaceF5Client implements AutoCloseable {
    private static final String BASE = "https://freococo-f5-myanmar-tts-demo.hf.space";
    private static final MediaType WAV = MediaType.get("audio/wav");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .build();

    private String uploadedReferencePath;

    File synthesizeDefaultToFile(String text, float speed, File target) throws Exception {
        String cleanText = TtsText.normalizeForSpeech(text);
        if (cleanText.isEmpty()) throw new IllegalArgumentException("No readable text for F5 Myanmar voice.");
        float safeSpeed = Math.max(0.7f, Math.min(1.5f, speed));

        Throwable first = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String eventId = startGeneration(cleanText, null, "", safeSpeed);
                String outputUrl = awaitOutputUrl(eventId);
                download(outputUrl, target);
                if (!target.isFile() || target.length() < 1000L) {
                    throw new IOException("F5 Myanmar default voice returned empty audio.");
                }
                return target;
            } catch (Throwable problem) {
                if (first == null) first = problem;
                if (attempt == 1) {
                    if (problem instanceof Exception) throw (Exception) problem;
                    throw new IOException(problem);
                }
            }
        }
        if (first instanceof Exception) throw (Exception) first;
        throw new IOException("F5 Myanmar default voice failed.");
    }

    File synthesizeToFile(String text, File referenceWav, String referenceText,
                          float speed, File target) throws Exception {
        String cleanText = TtsText.normalizeForSpeech(text);
        if (cleanText.isEmpty()) throw new IllegalArgumentException("No readable text for Aung Gyi voice.");
        if (referenceWav == null || !referenceWav.isFile() || referenceWav.length() < 1000L) {
            throw new IllegalStateException("Aung Gyi reference WAV is missing.");
        }
        String cleanReferenceText = referenceText == null ? "" : referenceText.trim();
        if (cleanReferenceText.isEmpty()) throw new IllegalStateException("Aung Gyi reference transcript is missing.");
        float safeSpeed = Math.max(0.7f, Math.min(1.5f, speed));

        Throwable first = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (uploadedReferencePath == null || uploadedReferencePath.isEmpty()) {
                    uploadedReferencePath = uploadReference(referenceWav);
                }
                String eventId = startGeneration(cleanText, uploadedReferencePath,
                        cleanReferenceText, safeSpeed);
                String outputUrl = awaitOutputUrl(eventId);
                download(outputUrl, target);
                if (!target.isFile() || target.length() < 1000L) {
                    throw new IOException("Online F5 returned empty audio.");
                }
                return target;
            } catch (Throwable problem) {
                if (first == null) first = problem;
                // A Space restart can invalidate an uploaded temp path. Re-upload once before giving up.
                uploadedReferencePath = null;
                if (attempt == 1) {
                    if (problem instanceof Exception) throw (Exception) problem;
                    throw new IOException(problem);
                }
            }
        }
        if (first instanceof Exception) throw (Exception) first;
        throw new IOException("Online F5 generation failed.");
    }

    private String uploadReference(File referenceWav) throws Exception {
        RequestBody fileBody = RequestBody.create(referenceWav, WAV);
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("files", "aung-gyi-reference.wav", fileBody)
                .build();
        Request request = new Request.Builder()
                .url(BASE + "/gradio_api/upload")
                .header("Accept", "application/json")
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Online F5 reference upload HTTP " + response.code() + ": " + compact(raw));
            }
            JSONArray paths = new JSONArray(raw);
            if (paths.length() == 0) throw new IOException("Online F5 reference upload returned no path.");
            String path = paths.optString(0, "").trim();
            if (path.isEmpty()) throw new IOException("Online F5 reference upload returned an empty path.");
            return path;
        }
    }

    private String startGeneration(String text, String referencePath,
                                   String referenceText, float speed) throws Exception {
        Object refAudio = JSONObject.NULL;
        if (referencePath != null && !referencePath.trim().isEmpty()) {
            refAudio = new JSONObject()
                    .put("path", referencePath)
                    .put("meta", new JSONObject().put("_type", "gradio.FileData"));
        }
        JSONObject payload = new JSONObject()
                .put("text", text)
                .put("ref_audio", refAudio)
                .put("ref_text", referenceText == null ? "" : referenceText)
                .put("speed", speed);
        Request request = new Request.Builder()
                .url(BASE + "/gradio_api/call/v2/generate_speech")
                .header("Accept", "application/json")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Online F5 generation HTTP " + response.code() + ": " + compact(raw));
            }
            String eventId = new JSONObject(raw).optString("event_id", "").trim();
            if (eventId.isEmpty()) throw new IOException("Online F5 did not return an event id.");
            return eventId;
        }
    }

    private String awaitOutputUrl(String eventId) throws Exception {
        Request request = new Request.Builder()
                .url(BASE + "/gradio_api/call/generate_speech/" + url(eventId))
                .header("Accept", "text/event-stream")
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Online F5 result HTTP " + response.code() + ": " + compact(raw));
            }
            String[] lines = raw.replace("\r\n", "\n").split("\n");
            String event = "";
            String lastData = "";
            for (String line : lines) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    lastData = data;
                    if ("error".equals(event)) throw new IOException("Online F5 failed: " + compact(data));
                    if ("complete".equals(event)) {
                        return outputUrlFromData(data);
                    }
                }
            }
            // Some Gradio versions omit the event line when returning a single final payload.
            if (!lastData.isEmpty()) return outputUrlFromData(lastData);
            throw new IOException("Online F5 returned no completed audio result.");
        }
    }

    private String outputUrlFromData(String data) throws Exception {
        JSONArray outputs = new JSONArray(data);
        if (outputs.length() == 0) throw new IOException("Online F5 returned no audio output.");
        Object first = outputs.get(0);
        if (first instanceof JSONObject) {
            JSONObject file = (JSONObject) first;
            String value = file.optString("url", "").trim();
            if (!value.isEmpty()) return absolute(value);
            String path = file.optString("path", "").trim();
            if (!path.isEmpty()) return BASE + "/gradio_api/file=" + url(path);
        } else if (first instanceof String) {
            String value = String.valueOf(first).trim();
            if (!value.isEmpty()) return absolute(value);
        }
        throw new IOException("Online F5 audio result has no download URL.");
    }

    private static String absolute(String value) {
        if (value.startsWith("https://") || value.startsWith("http://")) return value;
        if (value.startsWith("/")) return BASE + value;
        return BASE + "/" + value;
    }

    private void download(String url, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create Aung Gyi audio cache directory.");
        }
        File temp = new File(target.getAbsolutePath() + ".part");
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Online F5 audio download HTTP " + response.code());
            }
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(temp), 64 * 1024)) {
                byte[] buffer = new byte[64 * 1024];
                java.io.InputStream in = response.body().byteStream();
                int n;
                long total = 0L;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > 80L * 1024L * 1024L) throw new IOException("Online F5 audio is unexpectedly large.");
                    out.write(buffer, 0, n);
                }
                out.flush();
            }
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            throw t;
        }
        if (target.exists() && !target.delete()) throw new IOException("Could not replace Aung Gyi cached audio.");
        if (!temp.renameTo(target)) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(temp);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.getFD().sync();
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static String url(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private static String compact(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= 220 ? text : text.substring(0, 220) + "…";
    }

    @Override public void close() {
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
    }
}
