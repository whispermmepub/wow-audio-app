package com.whisper.wowaudio;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class OfflineBurmeseTtsClient {
    static final String ESPEAK_PACKAGE = "com.reecedunn.espeak";
    static final String ENGINE_ID = "offline-espeak-ng-my";
    private static final Locale BURMESE = new Locale("my", "MM");
    private static final int INIT_TIMEOUT_SECONDS = 20;
    private static final int SYNTH_TIMEOUT_SECONDS = 180;

    interface Progress {
        void onPart(int completed, int total);
    }

    static final class OfflineTtsException extends Exception {
        OfflineTtsException(String message) { super(message); }
        OfflineTtsException(String message, Throwable cause) { super(message, cause); }
    }

    private OfflineBurmeseTtsClient() { }

    static boolean isEspeakInstalled(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getPackageInfo(ESPEAK_PACKAGE, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static String effectiveCacheVoice(Context context, String geminiVoice) {
        if (isEspeakInstalled(context)) return ENGINE_ID;
        return "gemini-" + safe(geminiVoice);
    }

    static void generateToWav(Context context, String text, File output, Progress progress) throws Exception {
        if (context == null) throw new OfflineTtsException("Android context is unavailable");
        if (!isEspeakInstalled(context)) throw new OfflineTtsException("eSpeak NG is not installed");
        String prepared = MyanmarTextNormalizer.normalizeForSpeech(text);
        if (empty(prepared)) throw new OfflineTtsException("Chapter has no speakable text");

        Context app = context.getApplicationContext();
        CountDownLatch initLatch = new CountDownLatch(1);
        AtomicInteger initStatus = new AtomicInteger(TextToSpeech.ERROR);
        AtomicReference<TextToSpeech> engineRef = new AtomicReference<>();

        TextToSpeech tts = new TextToSpeech(app, status -> {
            initStatus.set(status);
            initLatch.countDown();
        }, ESPEAK_PACKAGE);
        engineRef.set(tts);

        try {
            if (!initLatch.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new OfflineTtsException("Offline Burmese voice timed out while starting");
            }
            if (initStatus.get() != TextToSpeech.SUCCESS) {
                throw new OfflineTtsException("Offline Burmese voice could not start");
            }
            int language = tts.setLanguage(BURMESE);
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                throw new OfflineTtsException("Installed eSpeak NG does not have Burmese voice data");
            }
            tts.setSpeechRate(1.0f);
            tts.setPitch(1.0f);

            int safeLimit = Math.max(500, Math.min(3200, TextToSpeech.getMaxSpeechInputLength() - 200));
            List<String> parts = split(prepared, safeLimit);
            if (parts.isEmpty()) throw new OfflineTtsException("Chapter has no speakable text");

            List<AudioTimingStore.Segment> timings = new ArrayList<>();
            long cursorMs = 0;
            try (WavUtil.StreamWriter writer = WavUtil.streamingPcm16Mono(output)) {
                for (int i = 0; i < parts.size(); i++) {
                    String part = parts.get(i);
                    File temp = new File(app.getCacheDir(), "wow-offline-tts-" + System.nanoTime() + "-" + i + ".wav");
                    try {
                        String utteranceId = "wow-offline-" + System.nanoTime() + "-" + i;
                        CountDownLatch done = new CountDownLatch(1);
                        AtomicReference<String> error = new AtomicReference<>();
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String id) { }
                            @Override public void onDone(String id) { if (utteranceId.equals(id)) done.countDown(); }
                            @Override public void onError(String id) {
                                if (utteranceId.equals(id)) {
                                    error.set("Offline Burmese synthesis failed");
                                    done.countDown();
                                }
                            }
                            @Override public void onError(String id, int errorCode) {
                                if (utteranceId.equals(id)) {
                                    error.set("Offline Burmese synthesis failed (" + errorCode + ")");
                                    done.countDown();
                                }
                            }
                        });
                        int queued = tts.synthesizeToFile(part, new Bundle(), temp, utteranceId);
                        if (queued != TextToSpeech.SUCCESS) throw new OfflineTtsException("Offline Burmese synthesis request was rejected");
                        if (!done.await(SYNTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            tts.stop();
                            throw new OfflineTtsException("Offline Burmese synthesis timed out");
                        }
                        if (error.get() != null) throw new OfflineTtsException(error.get());
                        PcmBlock block = readPcm16MonoWav(temp);
                        writer.write(block.pcm, block.sampleRate);
                        long durationMs = Math.max(1L, Math.round(block.pcm.length * 1000.0 / (block.sampleRate * 2.0)));
                        appendSentenceTimings(timings, part, cursorMs, durationMs);
                        cursorMs += durationMs;
                        if (progress != null) progress.onPart(i + 1, parts.size());
                    } finally {
                        if (temp.exists()) temp.delete();
                    }
                }
                writer.commit();
            }
            AudioTimingStore.write(output, timings);
        } finally {
            TextToSpeech engine = engineRef.get();
            if (engine != null) {
                try { engine.stop(); } catch (Exception ignored) { }
                try { engine.shutdown(); } catch (Exception ignored) { }
            }
        }
    }

    private static List<String> split(String text, int limit) {
        List<String> out = new ArrayList<>();
        String value = safe(text).trim();
        int start = 0;
        while (start < value.length()) {
            int end = Math.min(value.length(), start + limit);
            if (end < value.length()) {
                int best = -1;
                int floor = start + Math.max(120, limit / 2);
                for (int i = end; i > floor; i--) {
                    char ch = value.charAt(i - 1);
                    if (ch == '\n' || ch == '။' || ch == '.' || ch == '!' || ch == '?' || ch == '၊' || ch == ';') {
                        best = i;
                        break;
                    }
                }
                if (best > start) end = best;
            }
            String part = value.substring(start, end).trim();
            if (!part.isEmpty()) out.add(part);
            start = end;
        }
        return out;
    }

    private static PcmBlock readPcm16MonoWav(File file) throws Exception {
        if (file == null || !file.isFile() || file.length() < 44) throw new OfflineTtsException("Offline voice returned an invalid audio file");
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (!"RIFF".equals(readAscii(in, 4))) throw new OfflineTtsException("Offline voice audio is not WAV");
            readLe32(in);
            if (!"WAVE".equals(readAscii(in, 4))) throw new OfflineTtsException("Offline voice audio is not WAV");
            int format = 0, channels = 0, sampleRate = 0, bits = 0;
            long dataOffset = -1, dataSize = 0;
            while (in.getFilePointer() + 8 <= in.length()) {
                String id = readAscii(in, 4);
                long size = readLe32(in);
                long next = in.getFilePointer() + size + (size & 1L);
                if (next > in.length() + 1) throw new OfflineTtsException("Offline voice returned a damaged WAV file");
                if ("fmt ".equals(id) && size >= 16) {
                    format = readLe16(in);
                    channels = readLe16(in);
                    sampleRate = (int) readLe32(in);
                    readLe32(in);
                    readLe16(in);
                    bits = readLe16(in);
                } else if ("data".equals(id)) {
                    dataOffset = in.getFilePointer();
                    dataSize = Math.min(size, in.length() - dataOffset);
                    break;
                }
                in.seek(Math.min(next, in.length()));
            }
            if (format != 1 || channels != 1 || bits != 16 || sampleRate <= 0 || dataOffset < 0 || dataSize <= 0 || dataSize > Integer.MAX_VALUE) {
                throw new OfflineTtsException("Offline Burmese voice returned an unsupported audio format");
            }
            byte[] pcm = new byte[(int) dataSize];
            in.seek(dataOffset);
            in.readFully(pcm);
            return new PcmBlock(pcm, sampleRate);
        }
    }

    private static void appendSentenceTimings(List<AudioTimingStore.Segment> output, String text, long startMs, long durationMs) {
        List<String> sentences = sentenceUnits(text);
        if (sentences.isEmpty()) {
            output.add(new AudioTimingStore.Segment(text, startMs, startMs + durationMs));
            return;
        }
        int totalWeight = 0;
        for (String sentence : sentences) totalWeight += Math.max(1, speechWeight(sentence));
        long cursor = startMs;
        long endOfPart = startMs + Math.max(1, durationMs);
        int usedWeight = 0;
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            usedWeight += Math.max(1, speechWeight(sentence));
            long end = i == sentences.size() - 1
                    ? endOfPart
                    : startMs + Math.round(durationMs * (usedWeight / (double) Math.max(1, totalWeight)));
            end = Math.max(cursor + 1, Math.min(endOfPart, end));
            output.add(new AudioTimingStore.Segment(sentence, cursor, end));
            cursor = end;
        }
    }

    private static List<String> sentenceUnits(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < safe(text).length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '။' || c == '.' || c == '!' || c == '?' || c == '\n') {
                String part = current.toString().trim();
                if (!part.isEmpty()) out.add(part);
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    private static int speechWeight(String text) {
        int weight = 0;
        for (int i = 0; i < safe(text).length(); i++) if (!Character.isWhitespace(text.charAt(i))) weight++;
        return weight;
    }

    private static String readAscii(RandomAccessFile in, int length) throws Exception {
        byte[] b = new byte[length];
        in.readFully(b);
        return new String(b, StandardCharsets.US_ASCII);
    }

    private static int readLe16(RandomAccessFile in) throws Exception {
        return in.readUnsignedByte() | (in.readUnsignedByte() << 8);
    }

    private static long readLe32(RandomAccessFile in) throws Exception {
        return (long) in.readUnsignedByte()
                | ((long) in.readUnsignedByte() << 8)
                | ((long) in.readUnsignedByte() << 16)
                | ((long) in.readUnsignedByte() << 24);
    }

    private static final class PcmBlock {
        final byte[] pcm;
        final int sampleRate;
        PcmBlock(byte[] pcm, int sampleRate) { this.pcm = pcm; this.sampleRate = sampleRate; }
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
}
