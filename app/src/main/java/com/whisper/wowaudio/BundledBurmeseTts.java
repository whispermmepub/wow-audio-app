package com.whisper.wowaudio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.reecedunn.espeak.SpeechSynthesis;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class BundledBurmeseTts {
    interface Listener {
        void onWord(int textPosition, int textLength);
    }

    private final SpeechSynthesis engine;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicLong chunkFramesWritten = new AtomicLong();
    private volatile AudioTrack track;
    private volatile boolean completed;

    BundledBurmeseTts(Context context, Listener listener) throws Exception {
        File dataParent = BundledTtsData.ensureInstalled(context.getApplicationContext());
        engine = new SpeechSynthesis(dataParent.getAbsolutePath(), new SpeechSynthesis.Callback() {
            @Override public void onAudio(byte[] pcm16Mono) {
                AudioTrack t = track;
                if (stopped.get() || t == null || pcm16Mono == null || pcm16Mono.length == 0) return;
                int offset = 0;
                while (!stopped.get() && offset < pcm16Mono.length) {
                    int written = t.write(pcm16Mono, offset, pcm16Mono.length - offset);
                    if (written <= 0) break;
                    offset += written;
                    chunkFramesWritten.addAndGet(written / 2L);
                }
            }

            @Override public void onComplete() {
                completed = true;
            }

            @Override public void onWordBoundary(int textPosition, int textLength, int markerInFrames) {
                if (!stopped.get() && listener != null) listener.onWord(textPosition, textLength);
            }
        });
        if (!engine.useMyanmarVoice()) throw new IllegalStateException("Bundled Myanmar voice is missing");
        engine.setRate(165);
        engine.setPitch(50);

        int sampleRate = engine.getSampleRate();
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) minBuffer = sampleRate;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        track = new AudioTrack(attrs, format, Math.max(minBuffer * 2, 8192), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("Audio output could not start");
        }
    }

    boolean speakBlocking(String text) {
        AudioTrack t = track;
        if (t == null) return false;
        stopped.set(false);
        completed = false;
        chunkFramesWritten.set(0L);
        long headStart = unsignedHead(t);
        t.play();
        boolean accepted = engine.synthesize(text);
        if (accepted && completed && !stopped.get()) drainChunk(t, headStart, chunkFramesWritten.get());
        return accepted && completed && !stopped.get();
    }

    private void drainChunk(AudioTrack t, long start, long frames) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000L;
        while (!stopped.get() && android.os.SystemClock.elapsedRealtime() < deadline) {
            long played = unsignedHead(t) - start;
            if (played < 0) played += 0x1_0000_0000L;
            if (played >= frames) return;
            try { Thread.sleep(12L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static long unsignedHead(AudioTrack t) {
        return ((long) t.getPlaybackHeadPosition()) & 0xffffffffL;
    }

    void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        try { engine.stop(); } catch (Exception ignored) { }
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); } catch (Exception ignored) { }
            try { t.flush(); } catch (Exception ignored) { }
        }
    }

    void release() {
        stop();
        AudioTrack t = track;
        track = null;
        if (t != null) try { t.release(); } catch (Exception ignored) { }
    }
}
