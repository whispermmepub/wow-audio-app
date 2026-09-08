package com.whisper.wowaudio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class GenerationResumeReceiver extends BroadcastReceiver {
    static final String ACTION_RETRY = "com.whisper.wowaudio.GENERATION_RETRY";

    @Override public void onReceive(Context context, Intent intent) {
        // Broadcast receivers can run while the app is fully backgrounded, where
        // modern Android may reject a foreground-service start. WorkManager is the
        // durable recovery path; user-visible imports still use the fast FGS path.
        NarrationWorkScheduler.schedule(context, 0);
    }
}
