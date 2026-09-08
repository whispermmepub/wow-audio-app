package com.whisper.wowaudio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class GenerationResumeReceiver extends BroadcastReceiver {
    static final String ACTION_RETRY = "com.whisper.wowaudio.GENERATION_RETRY";

    @Override public void onReceive(Context context, Intent intent) {
        NarrationGenerationService.resumePending(context);
    }
}