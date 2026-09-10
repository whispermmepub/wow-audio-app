package com.whisper.wowaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Foreground audiobook reader backed by the app's bundled Burmese MMS/VITS model.
 *
 * Neural inference produces floating-point PCM. Each short chunk is written to a standard
 * PCM16 WAV file and handed to Android MediaPlayer. Using the platform media pipeline is
 * deliberately more conservative than driving an OEM AudioTrack static buffer directly.
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
    private static final double SILENCE_RMS = 0.00001;
    private static final double SILENCE_PEAK = 0.00010;

    private final Object pauseLock = new Object();
    private volatile long generation;
    private volatile boolean paused;
    private volatile MediaPlayer currentPlayer;
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
        releaseCurrentPlayer();
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
                String status = "Generating speech • " + (index + 1) + " of " + chunks.size();
                updateNotification(status, true);

                MmsMyanmarTtsEngine.Audio audio = engine.synthesize(chunks.get(index), 1.0f);
                if (token != generation) return;
                validateAudible(audio);

                waitWhilePaused(token);
                if (token != generation) return;

                updateNotification("Reading " + target.title + " • " + (index + 1) + " of " + chunks.size(), true);
                playBlocking(audio, token, index);
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
            releaseCurrentPlayer();
            if (engine != null) {
                try { engine.close(); } catch (Throwable ignored) { }
            }
            if (worker == Thread.currentThread()) worker = null;
        }
    }

    private void playBlocking(MmsMyanmarTtsEngine.Audio audio, long token, int chunkIndex) throws Exception {
        File dir = new File(getCacheDir(), "tts-playback");
        File wav = new File(dir, "speech-" + token + "-" + chunkIndex + ".wav");
        WavFile.writeMonoPcm16(wav, audio.samples, audio.sampleRate);
        if (!wav.isFile() || wav.length() <= 44) {
            throw new IllegalStateException("Generated speech WAV is empty.");
        }

        MediaPlayer player = new MediaPlayer();
        currentPlayer = player;
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> playbackError = new AtomicReference<>();

        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            player.setAudioAttributes(attributes);
            player.setVolume(1.0f, 1.0f);
            player.setOnCompletionListener(mp -> finished.countDown());
            player.setOnErrorListener((mp, what, extra) -> {
                playbackError.compareAndSet(null, "MediaPlayer error " + what + "/" + extra);
                finished.countDown();
                return true;
            });
            player.setDataSource(wav.getAbsolutePath());
            player.prepare();
            if (player.getDuration() <= 0) throw new IllegalStateException("Android decoded zero-length speech audio.");

            waitWhilePaused(token);
            if (token != generation) return;
            player.start();
            broadcast("Playing built-in Myanmar voice", true);

            while (token == generation && finished.getCount() > 0) {
                if (paused) {
                    try {
                        if (player.isPlaying()) player.pause();
                    } catch (IllegalStateException ignored) { }
                    waitWhilePaused(token);
                    if (token != generation) break;
                    try {
                        if (!player.isPlaying()) player.start();
                    } catch (IllegalStateException e) {
                        playbackError.compareAndSet(null, "Could not resume Android audio output");
                        break;
                    }
                }
                if (finished.await(100, TimeUnit.MILLISECONDS)) break;
            }

            String error = playbackError.get();
            if (error != null) throw new IllegalStateException(error);
            if (token == generation && finished.getCount() > 0) {
                throw new IllegalStateException("Android audio output stopped before speech completed.");
            }
        } finally {
            if (currentPlayer == player) currentPlayer = null;
            releasePlayer(player);
            //noinspection ResultOfMethodCallIgnored
            wav.delete();
        }
    }

    private static void validateAudible(MmsMyanmarTtsEngine.Audio audio) {
        if (audio == null || audio.samples == null || audio.samples.length == 0) {
            throw new IllegalStateException("The Myanmar model produced no samples.");
        }
        if (audio.sampleRate <= 0) throw new IllegalStateException("The Myanmar model returned an invalid sample rate.");

        double sum = 0.0;
        double peak = 0.0;
        int finite = 0;
        for (float sample : audio.samples) {
            if (!Float.isFinite(sample)) continue;
            double v = Math.max(-1.0, Math.min(1.0, sample));
            sum += v * v;
            peak = Math.max(peak, Math.abs(v));
            finite++;
        }
        if (finite == 0) throw new IllegalStateException("The Myanmar model produced invalid audio values.");
        double rms = Math.sqrt(sum / finite);
        if (rms < SILENCE_RMS || peak < SILENCE_PEAK) {
            throw new IllegalStateException("The Myanmar model produced silent audio (rms="
                    + shortNumber(rms) + ", peak=" + shortNumber(peak) + ").");
        }
    }

    private void pauseReading() {
        paused = true;
        MediaPlayer player = currentPlayer;
        if (player != null) {
            try { if (player.isPlaying()) player.pause(); } catch (IllegalStateException ignored) { }
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
        broadcast("Resumed", true);
        updateNotification(progressText(), true);

        Thread t = worker;
        if (t == null || !t.isAlive()) startBook(activeBook.id);
    }

    private void stopReading(boolean savePosition) {
        BookStore.Book b = activeBook;
        if (savePosition && b != null) saveProgress(b.id, activeChunk);
        generation++;
        paused = false;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
        releaseCurrentPlayer();
        Thread t = worker;
        if (t != null) t.interrupt();
        activeBook = null;
        activeChunk = 0;
        activeChunkCount = 0;
        clearPlaybackCache();
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

    private void releaseCurrentPlayer() {
        MediaPlayer player = currentPlayer;
        currentPlayer = null;
        if (player != null) releasePlayer(player);
    }

    private static void releasePlayer(MediaPlayer player) {
        try { player.stop(); } catch (Exception ignored) { }
        try { player.reset(); } catch (Exception ignored) { }
        try { player.release(); } catch (Exception ignored) { }
    }

    private void clearPlaybackCache() {
        File dir = new File(getCacheDir(), "tts-playback");
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
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

    private static String shortNumber(double value) {
        return String.format(java.util.Locale.US, "%.6f", value);
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    @Override public void onDestroy() {
        generation++;
        paused = false;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
        releaseCurrentPlayer();
        Thread t = worker;
        if (t != null) t.interrupt();
        activeBook = null;
        clearPlaybackCache();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
