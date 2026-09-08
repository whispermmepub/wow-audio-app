package com.whisper.wowaudio;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public class WoWAudioApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private boolean resumedOnce;

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityStarted(Activity activity) {
        if (resumedOnce) return;
        resumedOnce = true;
        LibraryGenerationReconciler.reconcile(this);
        NarrationGenerationService.resumePending(this);
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityResumed(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}