package com.whisper.wowaudio;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NarrationGenerationService extends Service {
    static final String ACTION_DRAIN = "com.whisper.wowaudio.GENERATE_PENDING";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private GenerationQueueStore queue;
    private boolean draining;
    private volatile boolean stopping;
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
            long retryAt = store.earliestRetryAt();
            if (retryAt > now) NarrationWorkScheduler.scheduleAt(context, retryAt);
        }
    }

    private static void startDrain(Context context) {
        // A visible/user-initiated foreground drain is the fastest path. Cancel any
        // queued recovery work first; if Android rejects the FGS start, immediately
        // restore WorkManager as the durable fallback.
        NarrationWorkScheduler.cancel(context);
        Intent intent = new Intent(context, NarrationGenerationService.class).setAction(ACTION_DRAIN);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception ignored) {
            NarrationWorkScheduler.schedule(context, 0);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        queue = new GenerationQueueStore(this);
        NarrationNotificationHelper.ensureChannel(this);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WoWAudio:Generation");
        wakeLock.setReferenceCounted(false);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        stopping = false;
        startForeground(
                NarrationNotificationHelper.NOTIFICATION_ID,
                NarrationNotificationHelper.build(this, "WoW Audio", "Checking audiobook preparation queue…", 0, 0, true));
        kickDrain();
        return START_STICKY;
    }

    private synchronized void kickDrain() {
        if (draining || stopping) return;
        draining = true;
        executor.execute(() -> {
            try { drainQueue(); }
            finally {
                releaseWakeLock();
                boolean runAgain;
                synchronized (NarrationGenerationService.this) {
                    draining = false;
                    runAgain = !stopping && queue != null && !queue.due(System.currentTimeMillis()).isEmpty();
                }
                if (runAgain) kickDrain();
                else finishServiceIfIdle();
            }
        });
    }

    private void drainQueue() {
        while (!stopping && !Thread.currentThread().isInterrupted()) {
            renewWakeLock();
            NarrationGenerationEngine.Result result;
            try {
                result = NarrationGenerationEngine.runOne(this, new NarrationGenerationEngine.Listener() {
                    @Override public void onStatus(String title, String text, int progress, int max, boolean ongoing) {
                        NarrationNotificationHelper.notify(NarrationGenerationService.this, title, text, progress, max, ongoing);
                    }

                    @Override public boolean isCancelled() {
                        return stopping || Thread.currentThread().isInterrupted();
                    }
                });
            } finally {
                releaseWakeLock();
            }

            if (result.state == NarrationGenerationEngine.State.MORE) continue;
            if (result.state == NarrationGenerationEngine.State.WAITING && result.retryAt > 0) {
                NarrationWorkScheduler.scheduleAt(this, result.retryAt);
            }
            return;
        }
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

    @Override public void onTimeout(int startId, int fgsType) {
        // Android 15+ limits dataSync foreground-service time. Queue progress already
        // lives on disk, so hand recovery to WorkManager instead of risking a crash.
        stopping = true;
        NarrationWorkScheduler.schedule(this, 60_000L);
        NarrationNotificationHelper.notify(this, "WoW Audio",
                "Android paused a very long preparation session. Progress is saved and will resume automatically.",
                0, 0, false);
        releaseWakeLock();
        executor.shutdownNow();
        stopForeground(true);
        stopSelf(startId);
    }

    @Override public void onDestroy() {
        stopping = true;
        releaseWakeLock();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
}
