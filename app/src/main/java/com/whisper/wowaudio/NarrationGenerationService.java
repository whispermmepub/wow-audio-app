package com.whisper.wowaudio;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NarrationGenerationService extends Service {
    static final String ACTION_DRAIN = "com.whisper.wowaudio.GENERATE_PENDING";
    private static final String CHANNEL = "wow_audio_generation";
    private static final int NOTIFICATION_ID = 4201;
    private static final int RETRY_REQUEST = 4202;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private GenerationQueueStore queue;
    private boolean draining;
    private PowerManager.WakeLock wakeLock;

    static void enqueue(Context context, String bookId) {
        if (context == null || empty(bookId)) return;
        new GenerationQueueStore(context).enqueue(bookId, true);
        startDrain(context);
    }

    static void enqueueAllLibrary(Context context) {
        if (context == null) return;
        File library = new File(context.getFilesDir(), "library");
        File[] files = library.listFiles();
        GenerationQueueStore store = new GenerationQueueStore(context);
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".epub")) {
                    store.enqueue(file.getName(), true);
                }
            }
        }
        startDrain(context);
    }

    static void resumePending(Context context) {
        if (context == null) return;
        GenerationQueueStore store = new GenerationQueueStore(context);
        long now = System.currentTimeMillis();
        if (!store.due(now).isEmpty()) startDrain(context);
        else {
            long retry = store.earliestRetryAt();
            if (retry > now) scheduleRetry(context, retry);
        }
    }

    private static void startDrain(Context context) {
        Intent intent = new Intent(context, NarrationGenerationService.class).setAction(ACTION_DRAIN);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        queue = new GenerationQueueStore(this);
        createChannel();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WoWAudio:Generation");
        wakeLock.setReferenceCounted(false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("WoW Audio", "Checking audiobook preparation queue…", 0, 0, true));
        kickDrain();
        return START_STICKY;
    }

    private synchronized void kickDrain() {
        if (draining) return;
        draining = true;
        executor.execute(() -> {
            try { drainQueue(); }
            finally {
                releaseWakeLock();
                boolean runAgain;
                synchronized (NarrationGenerationService.this) {
                    draining = false;
                    runAgain = queue != null && !queue.due(System.currentTimeMillis()).isEmpty();
                }
                if (runAgain) kickDrain();
                else finishServiceIfIdle();
            }
        });
    }

    private void drainQueue() {
        while (!Thread.currentThread().isInterrupted()) {
            List<GenerationQueueStore.Job> due = queue.due(System.currentTimeMillis());
            if (due.isEmpty()) {
                long retryAt = queue.earliestRetryAt();
                if (retryAt > System.currentTimeMillis()) scheduleRetry(this, retryAt);
                return;
            }
            processBook(due.get(0));
        }
    }

    private void processBook(GenerationQueueStore.Job job) {
        String title = "Book";
        try {
            if (!queue.isCurrentRevision(job.bookId, job.revision)) return;
            File source = new File(new File(getFilesDir(), "library"), job.bookId);
            if (!source.isFile()) {
                queue.remove(job.bookId);
                return;
            }

            SecretStore secrets = new SecretStore(this);
            String key = secrets.getApiKey();
            if (empty(key)) {
                queue.markSetupRequired(job.bookId, job.revision, job.nextChapter, job.totalChapters);
                notifyNow(notification("Narration setup needed", "Open WoW Audio once and add your Gemini API key.", 0, 0, false));
                return;
            }

            NarrationUi.BookInput book = NarrationSourceLoader.load(this, job.bookId);
            title = book.title;
            int total = book.chapters.size();
            if (total == 0) {
                queue.markBlocked(job.bookId, job.revision, 0, 0, "No readable chapters found");
                notifyNow(notification(title, "No readable chapters found.", 0, 0, false));
                return;
            }
            if (job.nextChapter >= total) {
                queue.markDone(job.bookId, job.revision, total);
                notifyNow(notification(title,
                        "Audiobook ready. All " + total + " chapters are available offline. Press Play on Home.",
                        total, total, false));
                return;
            }

            NarrationSettings settings = new NarrationSettings(this);
            String voice = settings.voice();
            String style = settings.style();
            AudioCache cache = new AudioCache(this);
            GeminiTtsClient client = new GeminiTtsClient();
            int start = Math.max(0, Math.min(job.nextChapter, total - 1));

            for (int i = start; i < total; i++) {
                if (Thread.currentThread().isInterrupted()) return;
                if (!queue.isCurrentRevision(book.bookId, job.revision)) return;
                renewWakeLock();
                NarrationUi.ChapterInput chapter = book.chapters.get(i);
                File target = cache.fileFor(book.bookId, i, chapter.text, voice, style);
                int chapterNumber = i + 1;

                if (cache.isReady(target) && cache.hasFollowData(target)) {
                    queue.markChapterComplete(book.bookId, job.revision, chapterNumber, total);
                    notifyNow(notification(title,
                            "Chapter " + chapterNumber + " of " + total + " already ready",
                            chapterNumber, total, true));
                    continue;
                }

                notifyNow(notification(title,
                        "Preparing chapter " + chapterNumber + " of " + total + ": " + chapter.title,
                        i, total, true));

                try {
                    final int absoluteChapter = chapterNumber;
                    client.generateToWav(key, chapter.text, voice, style, target,
                            new GeminiTtsClient.Progress() {
                                @Override public void onChunk(int completed, int chunks) {
                                    notifyNow(notification(book.title,
                                            "Chapter " + absoluteChapter + " of " + total + " • part " + completed + " of " + chunks,
                                            absoluteChapter - 1, total, true));
                                }

                                @Override public void onWait(int seconds, String reason) {
                                    notifyNow(notification(book.title,
                                            reason + ". Retrying automatically in " + seconds + " seconds.",
                                            absoluteChapter - 1, total, true));
                                }
                            });
                    if (!queue.isCurrentRevision(book.bookId, job.revision)) return;
                    queue.markChapterComplete(book.bookId, job.revision, chapterNumber, total);
                    notifyNow(notification(title,
                            "Chapter " + chapterNumber + " of " + total + " ready",
                            chapterNumber, total, true));
                } catch (GeminiTtsClient.TtsException e) {
                    if (queue.isCurrentRevision(book.bookId, job.revision)) {
                        handleTtsFailure(job, title, i, total, e);
                    }
                    return;
                } catch (OutOfMemoryError e) {
                    if (!queue.isCurrentRevision(book.bookId, job.revision)) return;
                    long retryAt = System.currentTimeMillis() + 10L * 60_000L;
                    queue.markWaiting(job.bookId, job.revision, i, total, retryAt, "Device memory pressure");
                    scheduleRetry(this, retryAt);
                    notifyNow(notification(title,
                            "Generation paused because the device was low on memory. It will continue automatically.",
                            i, total, false));
                    return;
                } catch (Exception e) {
                    if (!queue.isCurrentRevision(book.bookId, job.revision)) return;
                    GenerationQueueStore.Job current = queue.get(job.bookId);
                    int failures = current == null ? 0 : current.failures;
                    long retryAt = System.currentTimeMillis() + persistentRetryMillis(failures, false, 120);
                    queue.markWaiting(job.bookId, job.revision, i, total, retryAt, safeMessage(e));
                    scheduleRetry(this, retryAt);
                    notifyNow(notification(title,
                            "Temporary generation problem. Chapter " + chapterNumber + " will retry automatically.",
                            i, total, false));
                    return;
                } finally {
                    releaseWakeLock();
                }
            }

            if (!queue.isCurrentRevision(book.bookId, job.revision)) return;
            queue.markDone(book.bookId, job.revision, total);
            notifyNow(notification(title,
                    "Audiobook ready. All " + total + " chapters are available offline. Press Play on Home.",
                    total, total, false));
        } catch (Exception e) {
            if (!queue.isCurrentRevision(job.bookId, job.revision)) return;
            long retryAt = System.currentTimeMillis() + 2L * 60_000L;
            queue.markWaiting(job.bookId, job.revision, Math.max(0, job.nextChapter), job.totalChapters, retryAt, safeMessage(e));
            scheduleRetry(this, retryAt);
            notifyNow(notification(title,
                    "Preparation paused temporarily and will continue automatically.",
                    Math.max(0, job.nextChapter), Math.max(0, job.totalChapters), false));
        }
    }

    private void handleTtsFailure(GenerationQueueStore.Job job, String title, int chapterIndex, int total, GeminiTtsClient.TtsException e) {
        if (!queue.isCurrentRevision(job.bookId, job.revision)) return;
        if (e.authenticationFailure) {
            queue.markAuthRequired(job.bookId, job.revision, chapterIndex, total, safeMessage(e));
            notifyNow(notification(title,
                    "Gemini API key needs attention. Open WoW Audio and use Test + preview voice once.",
                    chapterIndex, total, false));
            return;
        }
        if (!e.retryable) {
            queue.markBlocked(job.bookId, job.revision, chapterIndex, total, safeMessage(e));
            notifyNow(notification(title,
                    "Narration is blocked by the current Gemini response. Open More options for details.",
                    chapterIndex, total, false));
            return;
        }

        GenerationQueueStore.Job current = queue.get(job.bookId);
        int failures = current == null ? 0 : current.failures;
        long waitMs = persistentRetryMillis(failures, e.rateLimited, e.suggestedRetrySeconds);
        long retryAt = System.currentTimeMillis() + waitMs;
        queue.markWaiting(job.bookId, job.revision, chapterIndex, total, retryAt, safeMessage(e));
        scheduleRetry(this, retryAt);
        long minutes = Math.max(1, Math.round(waitMs / 60000.0));
        String reason = e.rateLimited ? "Gemini quota/rate limit" : "Temporary network or Gemini error";
        notifyNow(notification(title,
                reason + ". Chapter " + (chapterIndex + 1) + " will continue automatically in about " + minutes + " minutes.",
                chapterIndex, total, false));
    }

    private static long persistentRetryMillis(int previousFailures, boolean rateLimited, int suggestedSeconds) {
        int failure = Math.max(0, previousFailures);
        if (rateLimited) {
            long[] waits = {5, 15, 30, 60, 120, 240};
            long minutes = waits[Math.min(waits.length - 1, failure)];
            long suggestedMs = Math.max(0, suggestedSeconds) * 1000L;
            return Math.max(minutes * 60_000L, suggestedMs);
        }
        long[] waits = {1, 2, 5, 10, 20, 30};
        long minutes = waits[Math.min(waits.length - 1, failure)];
        return Math.max(minutes * 60_000L, Math.max(0, suggestedSeconds) * 1000L);
    }

    private void renewWakeLock() {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock.acquire(30L * 60_000L);
        } catch (Exception ignored) { }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) return;
        try { if (wakeLock.isHeld()) wakeLock.release(); }
        catch (Exception ignored) { }
    }

    private synchronized void finishServiceIfIdle() {
        if (draining) return;
        stopForeground(false);
        stopSelf();
    }

    private static void scheduleRetry(Context context, long whenMs) {
        if (context == null || whenMs <= System.currentTimeMillis()) {
            if (context != null) startDrain(context);
            return;
        }
        AlarmManager alarms = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent pending = retryPendingIntent(context);
        if (Build.VERSION.SDK_INT >= 23) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pending);
        else alarms.set(AlarmManager.RTC_WAKEUP, whenMs, pending);
    }

    private static PendingIntent retryPendingIntent(Context context) {
        Intent retry = new Intent(context, GenerationResumeReceiver.class).setAction(GenerationResumeReceiver.ACTION_RETRY);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, RETRY_REQUEST, retry, flags);
    }

    private Notification notification(String title, String text, int progress, int max, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
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

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (empty(message)) message = error == null ? "Unknown generation error" : error.getClass().getSimpleName();
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 180 ? oneLine.substring(0, 180) + "…" : oneLine;
    }

    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }

    @Override public void onDestroy() {
        releaseWakeLock();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}