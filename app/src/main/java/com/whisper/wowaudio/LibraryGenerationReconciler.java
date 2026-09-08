package com.whisper.wowaudio;

import android.content.Context;

import java.io.File;
import java.util.Locale;

final class LibraryGenerationReconciler {
    private LibraryGenerationReconciler() { }

    static void reconcile(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (!new SecretStore(app).hasApiKey()) return;

        File library = new File(app.getFilesDir(), "library");
        File[] files = library.listFiles();
        if (files == null || files.length == 0) return;

        GenerationQueueStore store = new GenerationQueueStore(app);
        boolean added = false;
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.US).endsWith(".epub")) continue;
            if (store.get(file.getName()) == null) {
                // Legacy v1.1 and interrupted pre-v1.2 books have no durable job.
                // Start from chapter 0; the service skips any chapters already cached.
                store.enqueue(file.getName(), false);
                added = true;
            }
        }
        if (added) NarrationGenerationService.resumePending(app);
    }
}