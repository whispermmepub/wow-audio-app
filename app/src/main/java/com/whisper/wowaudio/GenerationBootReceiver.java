package com.whisper.wowaudio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class GenerationBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // Android 15+ does not allow a dataSync foreground service to be launched
            // directly from BOOT_COMPLETED. Seed any legacy jobs, then let WorkManager
            // recover the durable queue under system-managed constraints.
            LibraryGenerationReconciler.reconcile(context);
            NarrationWorkScheduler.schedule(context, 0);
        }
    }
}
