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
        if (context == null) return;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build();
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(NarrationRecoveryWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .addTag(UNIQUE_WORK);
        if (delayMs > 0) builder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS);
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, builder.build());
    }

    static void scheduleAt(Context context, long whenMs) {
        long delay = Math.max(0L, whenMs - System.currentTimeMillis());
        schedule(context, delay);
    }

    static void cancel(Context context) {
        if (context != null) WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(UNIQUE_WORK);
    }
}