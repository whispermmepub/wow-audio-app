package com.whisper.wowaudio;

import android.content.Context;

import java.io.File;
import java.util.Locale;

final class LibraryGenerationReconciler {
    private LibraryGenerationReconciler() { }

    static void reconcile(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        NarrationSettings settings = new NarrationSettings(app);
        if (!settings.narrationAvailable()) return;

        File library = new File(app.getFilesDir(), "library");
        File[] files = library.listFiles();
        if (files == null || files.length == 0) return;

        GenerationQueueStore store = new GenerationQueueStore(app);
        boolean added = false;
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.US).endsWith(".epub")) continue;
            GenerationQueueStore.Job job = store.get(file.getName());
            if (job == null) {
                // Upgrade and interrupted books may have no durable job.
                // Start at chapter 0; the engine skips chapters already cached for the active engine.
                store.enqueue(file.getName(), false);
                added = true;
            } else if (GenerationQueueStore.STATE_SETUP.equals(job.state)
                    || GenerationQueueStore.STATE_AUTH.equals(job.state)
                    || GenerationQueueStore.STATE_BLOCKED.equals(job.state)) {
                // A newly installed offline engine or newly corrected voice/API setup
                // can make a previously blocked book runnable without user intervention.
                store.enqueue(file.getName(), false);
                added = true;
            }
        }
        if (added) NarrationWorkScheduler.schedule(app, 0);
    }
}
