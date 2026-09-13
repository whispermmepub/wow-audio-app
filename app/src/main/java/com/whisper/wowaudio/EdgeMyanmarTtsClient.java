package com.whisper.wowaudio;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Small Android client for the same Microsoft Edge Read Aloud WebSocket protocol used by
 * open-source edge-tts clients. It needs Internet, but no user account or app API key.
 *
 * This is an unofficial consumer endpoint and can change. WoW Audio keeps it isolated behind
 * this class and always retains a fallback in ReadingService.
 *
 * Nilar / Thiha v5: keep synthesis itself conservative and human-like. Structural pauses are
 * handled between short Burmese phrase chunks rather than injecting fragile SSML break tags.
 */
final class EdgeMyanmarTtsClient implements AutoCloseable {
    static final String VOICE_NILAR = "my-MM-NilarNeural";
    static final String VOICE_THIHA = "my-MM-ThihaNeural";

    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String BASE = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1";
    private static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    private static final String SEC_MS_GEC_VERSION = "1-" + CHROMIUM_FULL_VERSION;
    private static final long WINDOWS_EPOCH_SECONDS = 11644473600L;
    private static final long TICKS_PER_SECOND = 10_000_000L;
    private static final int MIN_AUDIO_BYTES = 1024;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build();

    private volatile long clockSkewMillis;

    File synthesizeToFile(String text, String voice, float speed, File target) throws Exception {
        String clean = cleanInput(text);
        if (clean.isEmpty()) throw new IllegalArgumentException("No readable text for natural voice.");
        String selected = VOICE_THIHA.equals(voice) ? VOICE_THIHA : VOICE_NILAR;
        float safeSpeed = Math.max(0.75f, Math.min(1.5f, speed));

        Throwable last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return synthesizeOnce(clean, selected, safeSpeed, target);
            } catch (EdgeHttpException e) {
                last = e;
                if (attempt == 0 && e.code == 403 && e.serverDate != null && adjustClockSkew(e.serverDate)) {
                    continue;
                }
                throw e;
            } catch (Throwable t) {
                last = t;
                break;
            }
        }
        if (last instanceof Exception) throw (Exception) last;
        throw new IllegalStateException(last == null ? "Natural voice failed." : last.toString());
    }

    private File synthesizeOnce(String text, String voice, float speed, File target) throws Exception {
        if (target.getParentFile() != null && !target.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.getParentFile().mkdirs();
        }

        String connectionId = randomId();
        String url = BASE
                + "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN
                + "&ConnectionId=" + connectionId
                + "&Sec-MS-GEC=" + secMsGec()
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent())
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Cookie", "muid=" + randomMuid() + ";")
                .build();

        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger httpCode = new AtomicInteger(0);
        AtomicReference<String> serverDate = new AtomicReference<>();
        AtomicBoolean turnEnded = new AtomicBoolean(false);
        ByteArrayOutputStream audio = new ByteArrayOutputStream(128 * 1024);

        WebSocket webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                try {
                    String stamp = timestamp();
                    String config = "X-Timestamp:" + stamp + "\r\n"
                            + "Content-Type:application/json; charset=utf-8\r\n"
                            + "Path:speech.config\r\n\r\n"
                            + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                            + "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                            + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
                    if (!ws.send(config)) throw new IOException("Could not send speech configuration.");

                    // A tiny baseline slowdown gives the Burmese voices room to articulate without
                    // sounding dragged. Thiha also benefits from a very small pitch settling.
                    int voiceRateOffset = VOICE_NILAR.equals(voice) ? -2 : -1;
                    int percent = Math.round((speed - 1.0f) * 100f) + voiceRateOffset;
                    String rate = (percent >= 0 ? "+" : "") + percent + "%";
                    String pitch = "+0Hz";
                    String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='my-MM'>"
                            + "<voice name='" + voice + "'><prosody pitch='" + pitch + "' rate='" + rate
                            + "' volume='+0%'>" + escapeXml(text) + "</prosody></voice></speak>";
                    String speech = "X-RequestId:" + randomId() + "\r\n"
                            + "Content-Type:application/ssml+xml\r\n"
                            + "X-Timestamp:" + stamp + "Z\r\n"
                            + "Path:ssml\r\n\r\n" + ssml;
                    if (!ws.send(speech)) throw new IOException("Could not send speech text.");
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    finished.countDown();
                    ws.cancel();
                }
            }

            @Override public void onMessage(WebSocket ws, String textMessage) {
                if (textMessage.contains("Path:turn.end")) {
                    turnEnded.set(true);
                    finished.countDown();
                    ws.close(1000, "done");
                }
            }

            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                byte[] frame = bytes.toByteArray();
                if (frame.length < 2) return;
                int headerLength = ((frame[0] & 0xff) << 8) | (frame[1] & 0xff);
                int payloadStart = 2 + headerLength;
                if (payloadStart > frame.length) {
                    failure.compareAndSet(null, new IOException("Invalid natural voice audio frame."));
                    finished.countDown();
                    ws.cancel();
                    return;
                }
                String headers = new String(frame, 2, Math.min(headerLength, frame.length - 2), java.nio.charset.StandardCharsets.US_ASCII);
                if (!headers.contains("Path:audio")) return;
                if (payloadStart < frame.length) {
                    synchronized (audio) {
                        audio.write(frame, payloadStart, frame.length - payloadStart);
                    }
                }
            }

            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                if (response != null) {
                    httpCode.set(response.code());
                    serverDate.set(response.header("Date"));
                }
                failure.compareAndSet(null, t);
                finished.countDown();
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                if (!turnEnded.get()) finished.countDown();
            }
        });

        if (!finished.await(35, TimeUnit.SECONDS)) {
            webSocket.cancel();
            throw new IOException("Natural Myanmar voice timed out.");
        }

        Throwable problem = failure.get();
        if (problem != null) {
            if (httpCode.get() > 0) {
                throw new EdgeHttpException(httpCode.get(), serverDate.get(), problem);
            }
            if (problem instanceof Exception) throw (Exception) problem;
            throw new IOException(problem);
        }

        byte[] mp3;
        synchronized (audio) { mp3 = audio.toByteArray(); }
        if (mp3.length < MIN_AUDIO_BYTES) {
            throw new IOException("Natural Myanmar voice returned no usable audio.");
        }

        File temp = new File(target.getAbsolutePath() + ".part");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(mp3);
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IOException("Could not replace cached speech.");
        if (!temp.renameTo(target)) {
            copy(temp, target);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
        return target;
    }

    private String secMsGec() throws Exception {
        long unixSeconds = (System.currentTimeMillis() + clockSkewMillis) / 1000L;
        long windowsSeconds = unixSeconds + WINDOWS_EPOCH_SECONDS;
        windowsSeconds -= windowsSeconds % 300L;
        long ticks = windowsSeconds * TICKS_PER_SECOND;
        String value = Long.toString(ticks) + TRUSTED_CLIENT_TOKEN;
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return hex(digest);
    }

    private boolean adjustClockSkew(String header) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT"));
            Date server = f.parse(header);
            if (server == null) return false;
            clockSkewMillis += server.getTime() - (System.currentTimeMillis() + clockSkewMillis);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override public void close() {
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
    }

    private static String cleanInput(String value) {
        if (value == null) return "";
        // TtsText owns EPUB cleanup. Real newlines are useful to the local pause engine but must
        // never be passed to the network voice. A second n/N guard here prevents any future caller
        // from bypassing the main cleanup path.
        String normalized = TtsText.normalizeForSpeech(value).replace('\n', ' ');
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length();) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            if ((cp >= 0 && cp <= 8) || (cp >= 11 && cp <= 12) || (cp >= 14 && cp <= 31)) out.append(' ');
            else out.appendCodePoint(cp);
        }
        return out.toString()
                // Last-line defence: even if another caller bypasses TtsText, no standalone/run
                // n/N artefact may be sent to the online Burmese voice. English words are protected.
                .replaceAll("(?i)(?<![A-Za-z0-9])(?:[\\\\/|]+\\s*)?[nN]+(?:\\s+[nN]+)*(?:\\s*[\\\\/|]+)?(?![A-Za-z0-9])", " ")
                .replaceAll("(?i)(?<![A-Za-z0-9])[nN]+(?![A-Za-z0-9])", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String timestamp() {
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String userAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";
    }

    private static String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String randomMuid() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return hex(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02X", b & 0xff));
        return out.toString();
    }

    private static void copy(File from, File to) throws IOException {
        try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            out.getFD().sync();
        }
    }

    private static final class EdgeHttpException extends IOException {
        final int code;
        final String serverDate;

        EdgeHttpException(int code, String serverDate, Throwable cause) {
            super("Natural voice HTTP " + code, cause);
            this.code = code;
            this.serverDate = serverDate;
        }
    }
}
