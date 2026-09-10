package com.whisper.wowaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.Collections;
import java.util.List;

/**
 * Foreground audiobook reader backed by the app's bundled Burmese MMS/VITS model.
 * No Android TextToSpeech engine, Google service, API key, or network is used at runtime.
 */
public final class ReadingService extends Service {
    static final String ACTION_PLAY_BOOK = "com.whisper.wowaudio.PLAY_BOOK";
    static final String ACTION_TOGGLE = "com.whisper.wowaudio.TOGGLE";
    static final String ACTION_STOP = "com.whisper.wowaudio.STOP";
    static final String ACTION_STATE = "com.whisper.wowaudio.STATE";
    static final String EXTRA_BOOK_ID = "book_id";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_PLAYING = "playing";

    private static final String CHANNEL = "reading";
    private static final int NOTIFICATION_ID = 1001;

    private final Object pauseLock = new Object();
    private volatile long generation;
    private volatile boolean paused;
    private volatile AudioTrack currentTrack;
    private volatile Thread worker;
    private volatile BookStore.Book activeBook;
    private volatile int activeChunk;
    private volatile int activeChunkCount;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopReading(true);
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) {
            if (activeBook == null) return START_NOT_STICKY;
            if (paused) resumeReading(); else pauseReading();
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY_BOOK.equals(action)) {
            String id = intent.getStringExtra(EXTRA_BOOK_ID);
            if (id != null && !id.trim().isEmpty()) startBook(id);
        }
        return START_NOT_STICKY;
    }

    private void startBook(String id) {
        long token = ++generation;
        releaseCurrentTrack();
        paused = false;

        BookStore.Book target = new BookStore(this).get(id);
        activeBook = target;
        activeChunk = 0;
        activeChunkCount = 0;
        if (target == null) {
            broadcast("Book not found.", false);
            stopSelf();
            return;
        }

        startForeground(NOTIFICATION_ID, notification("Loading built-in Myanmar voice…", false));
        broadcast("Starting " + target.title, false);

        Thread t = new Thread(() -> readBookLoop(target, token), "wow-burmese-reader");
        worker = t;
        t.start();
    }

    private void readBookLoop(BookStore.Book target, long token) {
        MmsMyanmarTtsEngine engine = null;
        try {
            String text = new BookStore(this).readText(target);
            List<String> chunks = TtsText.chunks(text);
            if (chunks.isEmpty()) throw new IllegalArgumentException("No readable Myanmar text was found.");

            SharedPreferences p = prefs();
            int index = clamp(p.getInt(key(target.id, "chunk"), 0), 0, chunks.size() - 1);
            activeChunkCount = chunks.size();
            activeChunk = index;

            updateNotification("Loading built-in Myanmar voice…", false);
            engine = new MmsMyanmarTtsEngine(this);
            if (token != generation) return;

            broadcast("Built-in Myanmar voice ready", true);
            while (index < chunks.size() && token == generation) {
                waitWhilePaused(token);
                if (token != generation) return;

                activeChunk = index;
                String status = "Reading " + target.title + " • " + (index + 1) + " of " + chunks.size();
                updateNotification(status, true);

                MmsMyanmarTtsEngine.Audio audio = engine.synthesize(chunks.get(index), 1.0f);
                if (token != generation) return;
                waitWhilePaused(token);
                if (token != generation) return;

                playBlocking(audio, token);
                if (token != generation) return;

                index++;
                activeChunk = index;
                saveProgress(target.id, Math.min(index, chunks.size() - 1));
            }

            if (token == generation && index >= chunks.size()) {
                clearProgress(target.id);
                String title = target.title;
                broadcast("Finished " + title, false);
                updateNotification("Finished", false);
                generation++;
                activeBook = null;
                stopForeground(true);
                stopSelf();
            }
        } catch (Throwable e) {
            if (token == generation) {
                paused = true;
                saveProgress(target.id, activeChunk);
                String message = "Built-in Myanmar voice failed: " + safeMessage(e);
                broadcast(message, false);
                updateNotification("Myanmar voice error • tap Resume", false);
            }
        } finally {
            releaseCurrentTrack();
            if (engine != null) {
                try { engine.close(); } catch (Throwable ignored) { }
            }
            if (worker == Thread.currentThread()) worker = null;
        }
    }

    private void playBlocking(MmsMyanmarTtsEngine.Audio audio, long token) {
        if (audio == null || audio.samples == null || audio.samples.length == 0) return;
        int sampleRate = audio.sampleRate > 0 ? audio.sampleRate : 16000;
        short[] pcm = floatToPcm16(audio.samples);
        if (pcm.length == 0) return;

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();

        int bytes = pcm.length * 2;
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build();
        currentTrack = track;

        int written = track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
        if (written <= 0) {
            releaseTrack(track);
            if (currentTrack == track) currentTrack = null;
            throw new IllegalStateException("Audio output could not be prepared (" + written + ").");
        }

        waitWhilePaused(token);
        if (token != generation) {
            releaseTrack(track);
            if (currentTrack == track) currentTrack = null;
            return;
        }
        track.play();

        int targetFrames = written;
        while (token == generation) {
            if (paused) {
                try { track.pause(); } catch (IllegalStateException ignored) { }
                waitWhilePaused(token);
                if (token != generation) break;
                try { track.play(); } catch (IllegalStateException ignored) { }
            }
            int head;
            try { head = track.getPlaybackHeadPosition(); }
            catch (IllegalStateException e) { break; }
            if (head >= targetFrames) break;
            SystemClock.sleep(25);
        }

        releaseTrack(track);
        if (currentTrack == track) currentTrack = null;
    }

    private void pauseReading() {
        paused = true;
        AudioTrack track = currentTrack;
        if (track != null) {
            try { track.pause(); } catch (IllegalStateException ignored) { }
        }
        BookStore.Book b = activeBook;
        if (b != null) saveProgress(b.id, activeChunk);
        broadcast("Paused", false);
        updateNotification("Paused", false);
    }

    private void resumeReading() {
        if (activeBook == null) return;
        paused = false;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
        AudioTrack track = currentTrack;
        if (track != null) {
            try { track.play(); } catch (IllegalStateException ignored) { }
        }
        broadcast("Resumed", true);
        updateNotification(progressText(), true);

        // If a previous native/model error ended the worker, retry from the last saved chunk.
        Thread t = worker;
        if (t == null || !t.isAlive()) startBook(activeBook.id);
    }

    private void stopReading(boolean savePosition) {
        BookStore.Book b = activeBook;
        if (savePosition && b != null) saveProgress(b.id, activeChunk);
        generation++;
        paused = false;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
        releaseCurrentTrack();
        Thread t = worker;
        if (t != null) t.interrupt();
        activeBook = null;
        activeChunk = 0;
        activeChunkCount = 0;
        stopForeground(true);
        stopSelf();
    }

    private void waitWhilePaused(long token) {
        while (paused && token == generation) {
            synchronized (pauseLock) {
                if (!paused || token != generation) return;
                try { pauseLock.wait(250); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void releaseCurrentTrack() {
        AudioTrack track = currentTrack;
        currentTrack = null;
        if (track != null) releaseTrack(track);
    }

    private static void releaseTrack(AudioTrack track) {
        try { track.pause(); } catch (Exception ignored) { }
        try { track.stop(); } catch (Exception ignored) { }
        try { track.flush(); } catch (Exception ignored) { }
        try { track.release(); } catch (Exception ignored) { }
    }

    private static short[] floatToPcm16(float[] samples) {
        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float v = Math.max(-1.0f, Math.min(1.0f, samples[i]));
            out[i] = (short) Math.round(v * 32767.0f);
        }
        return out;
    }

    private void saveProgress(String bookId, int chunk) {
        prefs().edit()
                .putInt(key(bookId, "chunk"), Math.max(0, chunk))
                .putInt(key(bookId, "offset"), 0)
                .apply();
    }

    private void clearProgress(String bookId) {
        prefs().edit()
                .remove(key(bookId, "chunk"))
                .remove(key(bookId, "offset"))
                .apply();
    }

    private String progressText() {
        BookStore.Book b = activeBook;
        if (b == null) return "Reading";
        if (activeChunkCount <= 0) return "Preparing " + b.title;
        return "Built-in Myanmar Voice • " + b.title + " • "
                + Math.min(activeChunk + 1, activeChunkCount) + " of " + activeChunkCount;
    }

    private Notification notification(String text, boolean playing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent toggle = new Intent(this, ReadingService.class).setAction(ACTION_TOGGLE);
        PendingIntent togglePi = PendingIntent.getService(this, 2, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ReadingService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 3, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(activeBook == null ? "WoW Audio" : activeBook.title)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Resume", togglePi).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi).build())
                .build();
    }

    private void updateNotification(String text, boolean playing) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, notification(text, playing));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Book reading", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Offline Burmese audiobook playback controls");
            nm.createNotificationChannel(channel);
        }
    }

    private void broadcast(String message, boolean playing) {
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra(EXTRA_MESSAGE, message);
        state.putExtra(EXTRA_PLAYING, playing);
        sendBroadcast(state);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("reading_progress", MODE_PRIVATE);
    }

    private static String key(String bookId, String suffix) {
        return bookId + ":" + suffix;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    @Override public void onDestroy() {
        generation++;
        paused = false;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
        releaseCurrentTrack();
        Thread t = worker;
        if (t != null) t.interrupt();
        activeBook = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
