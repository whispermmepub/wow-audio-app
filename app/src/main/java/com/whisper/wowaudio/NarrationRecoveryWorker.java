package com.whisper.wowaudio;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class NarrationRecoveryWorker extends Worker {
    public NarrationRecoveryWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull @Override public Result doWork() {
        NarrationGenerationEngine.Result result = NarrationGenerationEngine.runOne(
                getApplicationContext(),
                new NarrationGenerationEngine.Listener() {
                    @Override public void onStatus(String title, String text, int progress, int max, boolean ongoing) {
                        NarrationNotificationHelper.notify(getApplicationContext(), title, text, progress, max, ongoing);
                    }

                    @Override public boolean isCancelled() {
                        return isStopped();
                    }
                });

        if (isStopped()) return Result.retry();
        if (result.state == NarrationGenerationEngine.State.MORE) {
            NarrationWorkScheduler.continueAfterCurrent(getApplicationContext(), 1_000L);
        } else if (result.state == NarrationGenerationEngine.State.WAITING && result.retryAt > 0) {
            NarrationWorkScheduler.continueAt(getApplicationContext(), result.retryAt);
        }
        return Result.success();
    }
}
