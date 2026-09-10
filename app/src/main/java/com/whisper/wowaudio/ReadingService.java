package com.whisper.wowaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import java.util.Collections;
import java.util.List;

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

    private volatile BundledBurmeseTts speaker;
    private BookStore.Book book;
    private List<String> chunks = Collections.emptyList();
    private int chunkIndex;
    private boolean paused;
    private long generation;

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
            if (book == null) return START_NOT_STICKY;
            if (paused) resumeReading(); else pauseReading();
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY_BOOK.equals(action)) {
            String id = intent.getStringExtra(EXTRA_BOOK_ID);
            if (id != null) startBook(id);
        }
        return START_NOT_STICKY;
    }

    private void startBook(String id) {
        generation++;
        long token = generation;
        releaseSpeaker();
        book = new BookStore(this).get(id);
        chunks = Collections.emptyList();
        chunkIndex = 0;
        paused = false;
        if (book == null) {
            broadcast("Book not found.", false);
            stopSelf();
            return;
        }
        startForeground(NOTIFICATION_ID, notification("Preparing offline Myanmar voice…", false));
        new Thread(() -> {
            try {
                String text = new BookStore(this).readText(book);
                List<String> parsed = TtsText.chunks(text);
                if (parsed.isEmpty()) throw new IllegalArgumentException("No readable text.");
                int savedChunk = clamp(prefs().getInt(key("chunk"), 0), 0, parsed.size() - 1);
                if (token != generation || book == null || !book.id.equals(id)) return;
                chunks = parsed;
                chunkIndex = savedChunk;
                runReader(token);
            } catch (Exception e) {
                if (token != generation) return;
                broadcast("Cannot read this book: " + safeMessage(e), false);
                updateNotification("Could not start • tap Play to retry", false);
                paused = true;
            }
        }, "book-loader").start();
    }

    private void runReader(long token) {
        if (token != generation || paused || book == null || chunks.isEmpty()) return;
        new Thread(() -> {
            BundledBurmeseTts local = null;
            try {
                local = new BundledBurmeseTts(getApplicationContext(), (textPosition, textLength) -> { });
                if (token != generation || paused || book == null) {
                    local.release();
                    return;
                }
                speaker = local;
                while (token == generation && !paused && book != null && chunkIndex < chunks.size()) {
                    String status = progressText();
                    broadcast(status, true);
                    updateNotification(status, true);
                    boolean ok = local.speakBlocking(chunks.get(chunkIndex));
                    if (token != generation || paused || book == null) return;
                    if (!ok) {
                        paused = true;
                        saveProgress();
                        broadcast("Speech stopped. Tap Resume to try again.", false);
                        updateNotification("Speech stopped • tap Resume", false);
                        return;
                    }
                    chunkIndex++;
                    if (chunkIndex < chunks.size()) saveProgress();
                }
                if (token == generation && !paused && book != null && chunkIndex >= chunks.size()) {
                    String finishedTitle = book.title;
                    clearProgress();
                    broadcast("Finished " + finishedTitle, false);
                    stopReading(false);
                }
            } catch (Throwable e) {
                if (token != generation) return;
                paused = true;
                saveProgress();
                broadcast("Offline Myanmar voice error: " + safeMessage(e), false);
                updateNotification("Voice error • tap Resume", false);
            } finally {
                if (local != null && speaker == local) {
                    local.release();
                    speaker = null;
                }
            }
        }, "offline-burmese-reader").start();
    }

    private void pauseReading() {
        paused = true;
        saveProgress();
        generation++;
        releaseSpeaker();
        broadcast("Paused", false);
        updateNotification("Paused", false);
    }

    private void resumeReading() {
        if (book == null) return;
        paused = false;
        generation++;
        long token = generation;
        if (chunks.isEmpty()) {
            startBook(book.id);
            return;
        }
        runReader(token);
    }

    private void stopReading(boolean savePosition) {
        if (savePosition) saveProgress();
        generation++;
        paused = true;
        releaseSpeaker();
        book = null;
        chunks = Collections.emptyList();
        stopForeground(true);
        stopSelf();
    }

    private void releaseSpeaker() {
        BundledBurmeseTts s = speaker;
        speaker = null;
        if (s != null) {
            try { s.release(); } catch (Exception ignored) { }
        }
    }

    private void saveProgress() {
        if (book == null || chunks.isEmpty()) return;
        prefs().edit()
                .putInt(key("chunk"), Math.max(0, Math.min(chunkIndex, chunks.size() - 1)))
                .putInt(key("offset"), 0)
                .apply();
    }

    private void clearProgress() {
        if (book == null) return;
        prefs().edit().remove(key("chunk")).remove(key("offset")).apply();
    }

    private String progressText() {
        if (book == null || chunks.isEmpty()) return "Reading";
        return "Reading " + book.title + " • " + Math.min(chunkIndex + 1, chunks.size()) + " of " + chunks.size();
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
                .setContentTitle(book == null ? "WoW Audio" : book.title)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(playing)
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
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Book reading", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Offline Myanmar book reading controls");
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

    private String key(String suffix) {
        return (book == null ? "none" : book.id) + ":" + suffix;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    @Override public void onDestroy() {
        releaseSpeaker();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
