package com.whisper.wowaudio;

import android.content.Context;

import java.io.File;
import java.util.Locale;

final class LibraryGenerationReconciler {
    private LibraryGenerationReconciler() { }

    static void reconcile(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (!new NarrationSettings(app).narrationAvailable()) return;

        File library = new File(app.getFilesDir(), "library");
        File[] files = library.listFiles();
        if (files == null || files.length == 0) return;

        GenerationQueueStore store = new GenerationQueueStore(app);
        boolean added = false;
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.US).endsWith(".epub")) continue;
            if (store.get(file.getName()) == null) {
                // Upgrade and interrupted books may have no durable job.
                // Start at chapter 0; the engine skips chapters already cached for the active engine.
                store.enqueue(file.getName(), false);
                added = true;
            }
        }
        if (added) NarrationWorkScheduler.schedule(app, 0);
    }
}
