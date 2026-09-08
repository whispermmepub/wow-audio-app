package com.whisper.wowaudio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class GenerationBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            NarrationGenerationService.resumePending(context);
        }
    }
}