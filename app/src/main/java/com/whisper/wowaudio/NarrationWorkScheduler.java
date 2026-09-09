package com.whisper.wowaudio;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import java.util.concurrent.TimeUnit;

final class NarrationWorkScheduler {
    private static final String UNIQUE_WORK = "wow-audio-resilient-narration";

    private NarrationWorkScheduler() { }

    static void schedule(Context context, long delayMs) {
        enqueue(context, delayMs, ExistingWorkPolicy.REPLACE);
    }

    static void scheduleAt(Context context, long whenMs) {
        schedule(context, Math.max(0L, whenMs - System.currentTimeMillis()));
    }

    static void continueAfterCurrent(Context context, long delayMs) {
        enqueue(context, delayMs, ExistingWorkPolicy.APPEND_OR_REPLACE);
    }

    static void continueAt(Context context, long whenMs) {
        continueAfterCurrent(context, Math.max(0L, whenMs - System.currentTimeMillis()));
    }

    private static void enqueue(Context context, long delayMs, ExistingWorkPolicy policy) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        boolean offline = new NarrationSettings(app).useOfflineEngine();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(offline ? NetworkType.NOT_REQUIRED : NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build();
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(NarrationRecoveryWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .addTag(UNIQUE_WORK);
        if (delayMs > 0) builder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS);
        WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE_WORK, policy, builder.build());
    }

    static void cancel(Context context) {
        if (context != null) WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(UNIQUE_WORK);
    }
}
