package com.whisper.wowaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NarrationGenerationService extends Service {
    static final String ACTION_GENERATE_BOOK = "com.whisper.wowaudio.GENERATE_BOOK";
    static final String EXTRA_BOOK_ID = "book_id";
    private static final String CHANNEL = "wow_audio_generation";
    private static final int NOTIFICATION_ID = 4201;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> queued = new HashSet<>();
    private int pendingJobs;

    static void enqueue(Context context, String bookId) {
        if (context == null || bookId == null || bookId.trim().isEmpty()) return;
        Intent intent = new Intent(context, NarrationGenerationService.class)
                .setAction(ACTION_GENERATE_BOOK)
                .putExtra(EXTRA_BOOK_ID, bookId);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String bookId = intent == null ? null : intent.getStringExtra(EXTRA_BOOK_ID);
        if (!ACTION_GENERATE_BOOK.equals(intent == null ? null : intent.getAction()) || bookId == null || bookId.trim().isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("Preparing audiobook", "Loading book…", 0, 0, true));
        synchronized (this) {
            if (queued.contains(bookId)) return START_NOT_STICKY;
            queued.add(bookId);
            pendingJobs++;
        }
        executor.execute(() -> generate(bookId));
        return START_REDELIVER_INTENT;
    }

    private void generate(String bookId) {
        String bookTitle = "Book";
        try {
            SecretStore secrets = new SecretStore(this);
            String key = secrets.getApiKey();
            if (key == null || key.trim().isEmpty()) {
                notifyNow(notification("Narration setup needed", "Open WoW Audio and add your Gemini API key.", 0, 0, false));
                return;
            }

            NarrationUi.BookInput book = NarrationSourceLoader.load(this, bookId);
            bookTitle = book.title;
            NarrationSettings settings = new NarrationSettings(this);
            AudioCache cache = new AudioCache(this);
            GeminiTtsClient client = new GeminiTtsClient();
            int total = book.chapters.size();
            if (total == 0) {
                notifyNow(notification(bookTitle, "No readable chapters found.", 0, 0, false));
                return;
            }

            for (int i = 0; i < total; i++) {
                NarrationUi.ChapterInput chapter = book.chapters.get(i);
                File target = cache.fileFor(book.bookId, i, chapter.text, settings.voice(), settings.style());
                int chapterNumber = i + 1;
                if (cache.isReady(target) && cache.hasFollowData(target)) {
                    notifyNow(notification(bookTitle,
                            "Chapter " + chapterNumber + " of " + total + " already ready", chapterNumber, total, true));
                    continue;
                }
                notifyNow(notification(bookTitle,
                        "Preparing chapter " + chapterNumber + " of " + total + ": " + chapter.title,
                        i, total, true));
                final int absoluteChapter = chapterNumber;
                client.generateToWav(key, chapter.text, settings.voice(), settings.style(), target,
                        new GeminiTtsClient.Progress() {
                            @Override public void onChunk(int completed, int chunks) {
                                notifyNow(notification(book.title,
                                        "Chapter " + absoluteChapter + " of " + total + " • part " + completed + " of " + chunks,
                                        absoluteChapter - 1, total, true));
                            }

                            @Override public void onWait(int seconds, String reason) {
                                notifyNow(notification(book.title,
                                        reason + ". Continuing automatically in " + seconds + " seconds.",
                                        absoluteChapter - 1, total, true));
                            }
                        });
                notifyNow(notification(bookTitle,
                        "Chapter " + chapterNumber + " of " + total + " ready", chapterNumber, total, true));
            }

            notifyNow(notification(bookTitle,
                    "Audiobook ready. Open WoW Audio and press Play.", total, total, false));
        } catch (Exception e) {
            String message = e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? "Narration stopped. Open WoW Audio to try again."
                    : "Narration paused: " + shortMessage(e.getMessage());
            notifyNow(notification(bookTitle, message, 0, 0, false));
        } finally {
            finishJob(bookId);
        }
    }

    private synchronized void finishJob(String bookId) {
        queued.remove(bookId);
        pendingJobs = Math.max(0, pendingJobs - 1);
        if (pendingJobs == 0) {
            stopForeground(false);
            stopSelf();
        }
    }

    private Notification notification(String title, String text, int progress, int max, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, pendingFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (max > 0) builder.setProgress(max, Math.max(0, Math.min(max, progress)), false);
        else if (ongoing) builder.setProgress(0, 0, true);
        return builder.build();
    }

    private void notifyNow(Notification notification) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Audiobook preparation", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progress while WoW Audio prepares imported books for offline listening");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    private static String shortMessage(String value) {
        String oneLine = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 150 ? oneLine.substring(0, 150) + "…" : oneLine;
    }

    @Override public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
