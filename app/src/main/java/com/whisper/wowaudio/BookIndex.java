package com.whisper.wowaudio;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class BookIndex {
    private final File file;
    private final Context context;

    BookIndex(Context context) {
        this.context = context.getApplicationContext();
        file = new File(this.context.getFilesDir(), "library-index.json");
    }

    synchronized Map<String, Entry> load() {
        Map<String, Entry> result = new HashMap<>();
        if (!file.isFile()) return result;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = in.read(bytes);
            if (read <= 0) return result;
            JSONArray a = new JSONArray(new String(bytes, 0, read, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry(o.optString("file"), o.optString("hash"), o.optString("title"), o.optString("author"), o.optLong("modified"));
                if (!e.fileName.isEmpty()) result.put(e.fileName, e);
            }
        } catch (Exception ignored) { }
        return result;
    }

    synchronized void put(Entry entry) {
        Map<String, Entry> entries = load();
        entries.put(entry.fileName, entry);
        JSONArray a = new JSONArray();
        boolean committed = false;
        try {
            for (Entry e : entries.values()) {
                JSONObject o = new JSONObject();
                o.put("file", e.fileName); o.put("hash", e.hash); o.put("title", e.title); o.put("author", e.author); o.put("modified", e.modified);
                a.put(o);
            }
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (file.exists() && !file.delete()) throw new Exception("Unable to replace index");
            if (!temp.renameTo(file)) throw new Exception("Unable to commit index");
            committed = true;
        } catch (Exception ignored) { }

        if (committed && new NarrationSettings(context).narrationAvailable()) {
            try { NarrationGenerationService.enqueue(context, entry.fileName); }
            catch (Exception ignored) { }
        }
    }

    static final class Entry {
        final String fileName, hash, title, author; final long modified;
        Entry(String fileName, String hash, String title, String author, long modified) {
            this.fileName = safe(fileName); this.hash = safe(hash); this.title = safe(title); this.author = safe(author); this.modified = modified;
        }
        private static String safe(String s) { return s == null ? "" : s; }
    }
}
