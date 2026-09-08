package com.whisper.wowaudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

final class ListeningProgressStore {
    private static final String PREFS = "wow_audio_listening_progress";
    private static final String LAST = "last";
    private final SharedPreferences prefs;

    ListeningProgressStore(Context context) { prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    synchronized void save(Entry entry) {
        if (entry == null || empty(entry.bookId)) return;
        try {
            JSONObject o = new JSONObject();
            o.put("bookId", entry.bookId);
            o.put("bookTitle", entry.bookTitle);
            o.put("author", entry.author);
            o.put("chapterTitle", entry.chapterTitle);
            o.put("chapter", entry.chapterIndex);
            o.put("position", Math.max(0, entry.positionMs));
            o.put("duration", Math.max(0, entry.durationMs));
            o.put("audioPath", entry.audioPath);
            o.put("updated", entry.updatedAt <= 0 ? System.currentTimeMillis() : entry.updatedAt);
            String json = o.toString();
            prefs.edit().putString(key(entry.bookId), json).putString(LAST, json).apply();
        } catch (Exception ignored) { }
    }

    Entry load(String bookId) { return parse(prefs.getString(key(bookId), "")); }
    Entry last() { return parse(prefs.getString(LAST, "")); }

    void clear(String bookId) {
        if (empty(bookId)) return;
        Entry last = last();
        SharedPreferences.Editor e = prefs.edit().remove(key(bookId));
        if (last != null && bookId.equals(last.bookId)) e.remove(LAST);
        e.apply();
    }

    private static Entry parse(String json) {
        if (empty(json)) return null;
        try {
            JSONObject o = new JSONObject(json);
            String id = o.optString("bookId", "");
            if (id.isEmpty()) return null;
            return new Entry(
                    id,
                    o.optString("bookTitle", ""),
                    o.optString("author", ""),
                    o.optString("chapterTitle", ""),
                    Math.max(0, o.optInt("chapter", 0)),
                    Math.max(0, o.optLong("position", 0)),
                    Math.max(0, o.optLong("duration", 0)),
                    o.optString("audioPath", ""),
                    o.optLong("updated", 0));
        } catch (Exception ignored) { return null; }
    }

    private static String key(String bookId) {
        return "book_" + Integer.toHexString((bookId == null ? "" : bookId).hashCode());
    }

    static int percent(Entry e) {
        if (e == null || e.durationMs <= 0) return 0;
        return Math.max(0, Math.min(100, Math.round(e.positionMs * 100f / e.durationMs)));
    }

    static final class Entry {
        final String bookId, bookTitle, author, chapterTitle, audioPath;
        final int chapterIndex;
        final long positionMs, durationMs, updatedAt;
        Entry(String bookId, String bookTitle, String author, String chapterTitle, int chapterIndex,
              long positionMs, long durationMs, String audioPath, long updatedAt) {
            this.bookId = safe(bookId);
            this.bookTitle = safe(bookTitle);
            this.author = safe(author);
            this.chapterTitle = safe(chapterTitle);
            this.chapterIndex = Math.max(0, chapterIndex);
            this.positionMs = Math.max(0, positionMs);
            this.durationMs = Math.max(0, durationMs);
            this.audioPath = safe(audioPath);
            this.updatedAt = updatedAt;
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
}
