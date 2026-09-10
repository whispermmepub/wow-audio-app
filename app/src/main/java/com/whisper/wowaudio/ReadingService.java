package com.whisper.wowaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real audiobook player: natural online Burmese first, persistent local speech cache, exact
 * resume and 15-second seeking. The bundled MMS/VITS voice is emergency fallback only.
 */
public final class ReadingService extends Service {
    static final String ACTION_PLAY_BOOK = "com.whisper.wowaudio.PLAY_BOOK";
    static final String ACTION_TOGGLE = "com.whisper.wowaudio.TOGGLE";
    static final String ACTION_SEEK_BACK = "com.whisper.wowaudio.SEEK_BACK";
    static final String ACTION_SEEK_FORWARD = "com.whisper.wowaudio.SEEK_FORWARD";
    static final String ACTION_STOP = "com.whisper.wowaudio.STOP";
    static final String ACTION_STATE = "com.whisper.wowaudio.STATE";
    static final String EXTRA_BOOK_ID = "book_id";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_PLAYING = "playing";

    static final String PREFS_VOICE = "voice_settings";
    static final String KEY_VOICE = "voice";
    static final String DEFAULT_VOICE = EdgeMyanmarTtsClient.VOICE_NILAR;

    private static final String CHANNEL = "reading";
    private static final int NOTIFICATION_ID = 1001;
    private static final int SEEK_MS = 15_000;
    private static final int PREFETCH_COUNT = 3;

    private final Object pauseLock = new Object();
    private final Object synthesisLock = new Object();
    private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor();

    private volatile long sessionToken;
    private volatile boolean paused;
    private volatile MediaPlayer currentPlayer;
    private volatile Thread worker;
    private volatile Future<?> prefetchFuture;
    private volatile BookStore.Book activeBook;
    private volatile List<String> activeSegments = Collections.emptyList();
    private volatile int activeSegment;
    private volatile int activeSegmentCount;
    private volatile int activePositionMs;
    private volatile long onlineRetryAfterMs;

    private EdgeMyanmarTtsClient onlineTts;
    private MmsMyanmarTtsEngine offlineTts;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        onlineTts = new EdgeMyanmarTtsClient();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopReading(true);
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) {
            if (activeBook != null) {
                if (paused) resumeReading(); else pauseReading();
            } else {
                broadcast("Nothing is playing.", false);
            }
            return START_NOT_STICKY;
        }
        if (ACTION_SEEK_BACK.equals(action)) {
            seekBy(-SEEK_MS);
            return START_NOT_STICKY;
        }
        if (ACTION_SEEK_FORWARD.equals(action)) {
            seekBy(SEEK_MS);
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY_BOOK.equals(action)) {
            String id = intent.getStringExtra(EXTRA_BOOK_ID);
            if (id != null && !id.trim().isEmpty()) startBook(id, false);
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        sessionToken++;
        releaseCurrentPlayer();
        Thread t = worker;
        if (t != null) t.interrupt();
        Future<?> future = prefetchFuture;
        if (future != null) future.cancel(true);
        prefetchExecutor.shutdownNow();
        if (onlineTts != null) {
            try { onlineTts.close(); } catch (Throwable ignored) { }
        }
        synchronized (synthesisLock) {
            if (offlineTts != null) {
                try { offlineTts.close(); } catch (Throwable ignored) { }
                offlineTts = null;
            }
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void startBook(String id, boolean startPaused) {
        final long token = ++sessionToken;
        releaseCurrentPlayer();
        paused = startPaused;
        synchronized (pauseLock) { pauseLock.notifyAll(); }

        Thread old = worker;
        if (old != null && old.isAlive()) old.interrupt();
        Future<?> oldPrefetch = prefetchFuture;
        if (oldPrefetch != null) oldPrefetch.cancel(true);

        BookStore.Book target = new BookStore(this).get(id);
        activeBook = target;
        activePositionMs = 0;
        if (target == null) {
            broadcast("Book not found.", false);
            stopSelf();
            return;
        }

        startForeground(NOTIFICATION_ID, notification(startPaused ? "Paused" : "Preparing natural Myanmar voice…", false));
        if (!startPaused) broadcast("Preparing natural Myanmar voice…", false);

        Thread t = new Thread(() -> readBookLoop(target, token), "wow-natural-reader");
        worker = t;
        t.start();
    }

    private void readBookLoop(BookStore.Book target, long token) {
        try {
            String text = new BookStore(this).readText(target);
            List<String> segments = TtsText.chunks(text);
            if (segments.isEmpty()) throw new IllegalArgumentException("No readable Myanmar text was found.");
            activeSegments = segments;
            activeSegmentCount = segments.size();

            SharedPreferences p = progressPrefs();
            int index = clamp(p.getInt(key(target.id, "chunk"), 0), 0, segments.size() - 1);
            final int resumeIndex = index;
            final int firstPositionMs = Math.max(0, p.getInt(key(target.id, "offset"), 0));
            String voice = selectedVoice();
            float speed = 1.0f;

            while (index < segments.size() && token == sessionToken) {
                waitWhilePaused(token);
                if (token != sessionToken) return;

                activeSegment = index;
                String segment = segments.get(index);
                File audio = ensureAudio(target, index, segment, voice, speed, true, token);
                if (audio == null || token != sessionToken) return;

                schedulePrefetch(target, segments, index, voice, speed, token);

                int startMs = index == resumeIndex ? firstPositionMs : 0;
                activePositionMs = startMs;
                updateNotification("Reading • " + voiceLabel(voice) + " • " + (index + 1) + "/" + segments.size(), true);
                if (index == resumeIndex) broadcast("Playing " + voiceLabel(voice), true);

                boolean completed = playFileBlocking(audio, target.id, index, startMs, token);
                if (token != sessionToken) return;
                if (!completed) return;

                index++;
                activeSegment = index;
                activePositionMs = 0;
                if (index < segments.size()) saveProgress(target.id, index, 0);
            }

            if (token == sessionToken && index >= segments.size()) {
                clearProgress(target.id);
                broadcast("Finished " + target.title, false);
                activeBook = null;
                activeSegments = Collections.emptyList();
                activeSegment = 0;
                activeSegmentCount = 0;
                activePositionMs = 0;
                stopForeground(true);
                stopSelf();
            }
        } catch (Throwable e) {
            if (token == sessionToken) {
                paused = true;
                BookStore.Book b = activeBook;
                if (b != null) saveProgress(b.id, activeSegment, activePositionMs);
                String message = "Myanmar voice failed: " + safeMessage(e);
                broadcast(message, false);
                updateNotification("Voice error • tap Resume", false);
            }
        } finally {
            if (worker == Thread.currentThread()) worker = null;
        }
    }

    private File ensureAudio(BookStore.Book book, int index, String text, String voice,
                             float speed, boolean allowOffline, long token) throws Exception {
        File cached = SpeechCache.existing(book, index, voice, speed, text);
        File natural = SpeechCache.online(book, index, voice, speed, text);
        if (natural.isFile() && natural.length() > 1024) return natural;

        synchronized (synthesisLock) {
            if (token != sessionToken) return null;
            if (natural.isFile() && natural.length() > 1024) return natural;

            Throwable onlineFailure = null;
            if (System.currentTimeMillis() >= onlineRetryAfterMs) {
                try {
                    if (allowOffline) {
                        updateNotification("Getting " + voiceLabel(voice) + "…", false);
                        broadcast("Getting natural Myanmar speech…", false);
                    }
                    SpeechCache.ensureParent(natural);
                    onlineTts.synthesizeToFile(text, voice, speed, natural);
                    onlineRetryAfterMs = 0L;
                    return natural;
                } catch (Throwable t) {
                    onlineFailure = t;
                    onlineRetryAfterMs = System.currentTimeMillis() + 45_000L;
                }
            }

            if (!allowOffline) return null;
            if (cached != null && cached.isFile()) return cached;

            File backup = SpeechCache.offline(book, index, speed, text);
            if (backup.isFile() && backup.length() > 44) return backup;

            String reason = onlineFailure == null ? "network unavailable" : safeMessage(onlineFailure);
            broadcast("Natural voice unavailable • using offline backup", false);
            updateNotification("Offline backup • " + reason, false);
            SpeechCache.ensureParent(backup);
            MmsMyanmarTtsEngine engine = offlineEngine();
            MmsMyanmarTtsEngine.Audio generated = engine.synthesize(text, speed);
            if (generated == null || generated.samples == null || generated.samples.length == 0) {
                throw new IllegalStateException("Offline backup produced no audio.");
            }
            WavFile.writeMonoPcm16(backup, generated.samples, generated.sampleRate);
            return backup;
        }
    }

    private MmsMyanmarTtsEngine offlineEngine() {
        synchronized (synthesisLock) {
            if (offlineTts == null) offlineTts = new MmsMyanmarTtsEngine(this);
            return offlineTts;
        }
    }

    private void schedulePrefetch(BookStore.Book book, List<String> segments, int current,
                                  String voice, float speed, long token) {
        Future<?> previous = prefetchFuture;
        if (previous != null && !previous.isDone()) return;
        prefetchFuture = prefetchExecutor.submit(() -> {
            for (int i = current + 1; i <= current + PREFETCH_COUNT && i < segments.size(); i++) {
                if (token != sessionToken || Thread.currentThread().isInterrupted()) return;
                try {
                    ensureAudio(book, i, segments.get(i), voice, speed, false, token);
                } catch (Throwable ignored) {
                    return;
                }
            }
        });
    }

    private boolean playFileBlocking(File file, String bookId, int segmentIndex,
                                     int startPositionMs, long token) throws Exception {
        MediaPlayer player = new MediaPlayer();
        currentPlayer = player;
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();

        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setVolume(1.0f, 1.0f);
            player.setOnCompletionListener(mp -> finished.countDown());
            player.setOnErrorListener((mp, what, extra) -> {
                error.compareAndSet(null, "Android audio error " + what + "/" + extra);
                finished.countDown();
                return true;
            });
            player.setDataSource(file.getAbsolutePath());
            player.prepare();
            int duration = player.getDuration();
            if (duration <= 0) throw new IllegalStateException("Speech audio has zero duration.");
            int seek = clamp(startPositionMs, 0, Math.max(0, duration - 250));
            if (seek > 0) player.seekTo(seek);
            activePositionMs = seek;

            waitWhilePaused(token);
            if (token != sessionToken) return false;
            player.start();

            long lastSave = 0L;
            while (token == sessionToken && finished.getCount() > 0) {
                if (paused) {
                    try { if (player.isPlaying()) player.pause(); } catch (IllegalStateException ignored) { }
                    saveProgress(bookId, segmentIndex, safePosition(player));
                    waitWhilePaused(token);
                    if (token != sessionToken) break;
                    try { if (!player.isPlaying()) player.start(); } catch (IllegalStateException e) {
                        error.compareAndSet(null, "Could not resume audio output.");
                        break;
                    }
                }

                activePositionMs = safePosition(player);
                long now = System.currentTimeMillis();
                if (now - lastSave >= 900L) {
                    saveProgress(bookId, segmentIndex, activePositionMs);
                    lastSave = now;
                }
                if (finished.await(150, TimeUnit.MILLISECONDS)) break;
            }

            String playbackError = error.get();
            if (playbackError != null) throw new IllegalStateException(playbackError);
            return token == sessionToken && finished.getCount() == 0;
        } finally {
            if (currentPlayer == player) currentPlayer = null;
            releasePlayer(player);
        }
    }

    private void seekBy(int deltaMs) {
        BookStore.Book book = activeBook;
        List<String> segments = activeSegments;
        if (book == null || segments == null || segments.isEmpty()) {
            broadcast("Nothing is playing.", false);
            return;
        }

        MediaPlayer player = currentPlayer;
        int index = clamp(activeSegment, 0, segments.size() - 1);
        int current = activePositionMs;
        int duration = 0;
        if (player != null) {
            try {
                current = player.getCurrentPosition();
                duration = player.getDuration();
            } catch (IllegalStateException ignored) { }
        }
        int target = current + deltaMs;

        if (duration > 0 && target >= 0 && target < duration && player != null) {
            try {
                player.seekTo(target);
                activePositionMs = target;
                saveProgress(book.id, index, target);
                broadcast(deltaMs < 0 ? "Back 15 seconds" : "Forward 15 seconds", !paused);
                return;
            } catch (Exception ignored) { }
        }

        String voice = selectedVoice();
        float speed = 1.0f;
        boolean keepPaused = paused;
        if (target < 0 && index > 0) {
            int previousIndex = index - 1;
            File previous = SpeechCache.existing(book, previousIndex, voice, speed, segments.get(previousIndex));
            int previousDuration = durationOf(previous);
            int previousPosition = previousDuration > 0 ? Math.max(0, previousDuration + target) : 0;
            saveProgress(book.id, previousIndex, previousPosition);
            broadcast("Back 15 seconds", !keepPaused);
            startBook(book.id, keepPaused);
            return;
        }

        if (duration > 0 && target >= duration && index + 1 < segments.size()) {
            int nextPosition = Math.max(0, target - duration);
            saveProgress(book.id, index + 1, nextPosition);
            broadcast("Forward 15 seconds", !keepPaused);
            startBook(book.id, keepPaused);
            return;
        }

        int clamped = duration > 0 ? clamp(target, 0, Math.max(0, duration - 250)) : Math.max(0, target);
        if (player != null && duration > 0) {
            try { player.seekTo(clamped); } catch (Exception ignored) { }
        }
        activePositionMs = clamped;
        saveProgress(book.id, index, clamped);
    }

    private void pauseReading() {
        paused = true;
        MediaPlayer player = currentPlayer;
        if (player != null) {
            try { if (player.isPlaying()) player.pause(); } catch (IllegalStateException ignored) { }
        }
        BookStore.Book book = activeBook;
        if (book != null) saveProgress(book.id, activeSegment, safePosition(player));
        broadcast("Paused", false);
        updateNotification("Paused • " + progressText(), false);
    }

    private void resumeReading() {
        BookStore.Book book = activeBook;
        if (book == null) return;
        paused = false;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
        broadcast("Resumed", true);
        updateNotification(progressText(), true);

        Thread t = worker;
        if (t == null || !t.isAlive()) startBook(book.id, false);
    }

    private void stopReading(boolean savePosition) {
        BookStore.Book book = activeBook;
        if (savePosition && book != null) saveProgress(book.id, activeSegment, safePosition(currentPlayer));
        sessionToken++;
        paused = false;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
        releaseCurrentPlayer();
        Thread t = worker;
        if (t != null) t.interrupt();
        Future<?> future = prefetchFuture;
        if (future != null) future.cancel(true);
        activeBook = null;
        activeSegments = Collections.emptyList();
        activeSegment = 0;
        activeSegmentCount = 0;
        activePositionMs = 0;
        stopForeground(true);
        stopSelf();
    }

    private void waitWhilePaused(long token) {
        while (paused && token == sessionToken) {
            synchronized (pauseLock) {
                if (!paused || token != sessionToken) return;
                try { pauseLock.wait(250); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private String selectedVoice() {
        String voice = getSharedPreferences(PREFS_VOICE, MODE_PRIVATE)
                .getString(KEY_VOICE, DEFAULT_VOICE);
        return EdgeMyanmarTtsClient.VOICE_THIHA.equals(voice)
                ? EdgeMyanmarTtsClient.VOICE_THIHA : EdgeMyanmarTtsClient.VOICE_NILAR;
    }

    private static String voiceLabel(String voice) {
        return EdgeMyanmarTtsClient.VOICE_THIHA.equals(voice) ? "Thiha" : "Nilar";
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

    private static int safePosition(MediaPlayer player) {
        if (player == null) return 0;
        try { return Math.max(0, player.getCurrentPosition()); }
        catch (IllegalStateException ignored) { return 0; }
    }

    private static int durationOf(File file) {
        if (file == null || !file.isFile()) return 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0 : Math.max(0, Integer.parseInt(value));
        } catch (Exception ignored) {
            return 0;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    private void saveProgress(String bookId, int segment, int positionMs) {
        progressPrefs().edit()
                .putInt(key(bookId, "chunk"), Math.max(0, segment))
                .putInt(key(bookId, "offset"), Math.max(0, positionMs))
                .apply();
    }

    private void clearProgress(String bookId) {
        progressPrefs().edit()
                .remove(key(bookId, "chunk"))
                .remove(key(bookId, "offset"))
                .apply();
    }

    private String progressText() {
        BookStore.Book book = activeBook;
        if (book == null) return "Reading";
        if (activeSegmentCount <= 0) return "Preparing " + book.title;
        return voiceLabel(selectedVoice()) + " • " + book.title + " • "
                + Math.min(activeSegment + 1, activeSegmentCount) + "/" + activeSegmentCount;
    }

    private Notification notification(String text, boolean playing) {
        PendingIntent content = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent back = serviceAction(2, ACTION_SEEK_BACK);
        PendingIntent toggle = serviceAction(3, ACTION_TOGGLE);
        PendingIntent forward = serviceAction(4, ACTION_SEEK_FORWARD);
        PendingIntent stop = serviceAction(5, ACTION_STOP);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(activeBook == null ? "WoW Audio" : activeBook.title)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(activeBook != null)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_rew, "Back 15s", back).build())
                .addAction(new Notification.Action.Builder(
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Resume", toggle).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_ff, "Forward 15s", forward).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build())
                .build();
    }

    private PendingIntent serviceAction(int requestCode, String action) {
        return PendingIntent.getService(this, requestCode,
                new Intent(this, ReadingService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
            channel.setDescription("Natural Myanmar audiobook playback controls");
            nm.createNotificationChannel(channel);
        }
    }

    private void broadcast(String message, boolean playing) {
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra(EXTRA_MESSAGE, message);
        state.putExtra(EXTRA_PLAYING, playing);
        sendBroadcast(state);
    }

    private SharedPreferences progressPrefs() {
        return getSharedPreferences("reading_progress", MODE_PRIVATE);
    }

    private static String key(String bookId, String suffix) {
        return bookId + ":" + suffix;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safeMessage(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return m == null || m.trim().isEmpty()
                ? (t == null ? "Unknown error" : t.getClass().getSimpleName()) : m;
    }
}
