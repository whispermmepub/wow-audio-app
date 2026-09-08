package com.whisper.wowaudio;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class GenerationQueueStore {
    static final String STATE_PENDING = "pending";
    static final String STATE_WAITING = "waiting";
    static final String STATE_SETUP = "setup";
    static final String STATE_AUTH = "auth";
    static final String STATE_BLOCKED = "blocked";
    static final String STATE_DONE = "done";

    private static final String PREFS = "wow_audio_generation_queue_v2";
    private static final String PREFIX = "job_";
    private final SharedPreferences prefs;

    GenerationQueueStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized Job enqueue(String bookId, boolean resetProgress) {
        if (empty(bookId)) return null;
        Job old = get(bookId);
        Job job = old == null ? new Job(bookId) : old.copy();
        job.state = STATE_PENDING;
        job.retryAt = 0;
        job.lastError = "";
        job.updatedAt = System.currentTimeMillis();
        if (resetProgress) {
            job.nextChapter = 0;
            job.failures = 0;
            job.totalChapters = 0;
            job.revision = Math.max(1L, (old == null ? 0L : old.revision) + 1L);
        } else if (job.revision <= 0) {
            job.revision = 1L;
        }
        put(job);
        return job;
    }

    synchronized Job get(String bookId) {
        if (empty(bookId)) return null;
        String direct = prefs.getString(key(bookId), null);
        Job parsed = parse(direct);
        if (parsed != null && bookId.equals(parsed.bookId)) return parsed;

        // Compatibility fallback for any earlier queue-key shape.
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PREFIX) || !(entry.getValue() instanceof String)) continue;
            Job job = parse((String) entry.getValue());
            if (job != null && bookId.equals(job.bookId)) return job;
        }
        return null;
    }

    synchronized List<Job> due(long now) {
        List<Job> result = new ArrayList<>();
        for (Job job : all()) {
            if (STATE_PENDING.equals(job.state) || (STATE_WAITING.equals(job.state) && job.retryAt <= now)) {
                result.add(job);
            }
        }
        result.sort(Comparator.comparingLong((Job j) -> j.updatedAt));
        return result;
    }

    synchronized List<Job> all() {
        List<Job> result = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PREFIX) || !(entry.getValue() instanceof String)) continue;
            Job job = parse((String) entry.getValue());
            if (job != null && !containsBook(result, job.bookId)) result.add(job);
        }
        return result;
    }

    synchronized long earliestRetryAt() {
        long earliest = 0;
        for (Job job : all()) {
            if (!STATE_WAITING.equals(job.state) || job.retryAt <= 0) continue;
            if (earliest == 0 || job.retryAt < earliest) earliest = job.retryAt;
        }
        return earliest;
    }

    synchronized boolean isCurrentRevision(String bookId, long revision) {
        Job current = get(bookId);
        return current != null && current.revision == revision;
    }

    synchronized void markChapterComplete(String bookId, long revision, int nextChapter, int totalChapters) {
        Job job = requireCurrent(bookId, revision);
        if (job == null) return;
        job.state = STATE_PENDING;
        job.nextChapter = Math.max(0, nextChapter);
        job.totalChapters = Math.max(0, totalChapters);
        job.failures = 0;
        job.retryAt = 0;
        job.lastError = "";
        job.updatedAt = System.currentTimeMillis();
        put(job);
    }

    synchronized void markWaiting(String bookId, long revision, int chapter, int totalChapters, long retryAt, String error) {
        Job job = requireCurrent(bookId, revision);
        if (job == null) return;
        job.state = STATE_WAITING;
        job.nextChapter = Math.max(0, chapter);
        job.totalChapters = Math.max(0, totalChapters);
        job.failures = Math.max(0, job.failures) + 1;
        job.retryAt = Math.max(System.currentTimeMillis() + 10_000L, retryAt);
        job.lastError = safe(error);
        job.updatedAt = System.currentTimeMillis();
        put(job);
    }

    synchronized void markSetupRequired(String bookId, long revision, int chapter, int totalChapters) {
        markState(bookId, revision, STATE_SETUP, chapter, totalChapters, 0, "Gemini API key required");
    }

    synchronized void markAuthRequired(String bookId, long revision, int chapter, int totalChapters, String error) {
        markState(bookId, revision, STATE_AUTH, chapter, totalChapters, 0, error);
    }

    synchronized void markBlocked(String bookId, long revision, int chapter, int totalChapters, String error) {
        markState(bookId, revision, STATE_BLOCKED, chapter, totalChapters, 0, error);
    }

    synchronized void markDone(String bookId, long revision, int totalChapters) {
        Job job = requireCurrent(bookId, revision);
        if (job == null) return;
        job.state = STATE_DONE;
        job.nextChapter = Math.max(0, totalChapters);
        job.totalChapters = Math.max(0, totalChapters);
        job.failures = 0;
        job.retryAt = 0;
        job.lastError = "";
        job.updatedAt = System.currentTimeMillis();
        put(job);
    }

    synchronized void remove(String bookId) {
        if (empty(bookId)) return;
        prefs.edit().remove(key(bookId)).apply();
        // Also remove any compatibility-key entry for this same book.
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;
            Job job = parse((String) entry.getValue());
            if (job != null && bookId.equals(job.bookId)) editor.remove(entry.getKey());
        }
        editor.apply();
    }

    private void markState(String bookId, long revision, String state, int chapter, int totalChapters, long retryAt, String error) {
        Job job = requireCurrent(bookId, revision);
        if (job == null) return;
        job.state = state;
        job.nextChapter = Math.max(0, chapter);
        job.totalChapters = Math.max(0, totalChapters);
        job.retryAt = Math.max(0, retryAt);
        job.lastError = safe(error);
        job.updatedAt = System.currentTimeMillis();
        put(job);
    }

    private Job requireCurrent(String bookId, long revision) {
        Job job = get(bookId);
        if (job == null || job.revision != revision) return null;
        return job.copy();
    }

    private void put(Job job) {
        try {
            JSONObject o = new JSONObject();
            o.put("bookId", job.bookId);
            o.put("state", job.state);
            o.put("nextChapter", job.nextChapter);
            o.put("totalChapters", job.totalChapters);
            o.put("failures", job.failures);
            o.put("retryAt", job.retryAt);
            o.put("lastError", job.lastError);
            o.put("updatedAt", job.updatedAt);
            o.put("revision", Math.max(1L, job.revision));
            prefs.edit().putString(key(job.bookId), o.toString()).commit();
        } catch (Exception ignored) { }
    }

    private static Job parse(String json) {
        if (empty(json)) return null;
        try {
            JSONObject o = new JSONObject(json);
            String id = o.optString("bookId", "");
            if (empty(id)) return null;
            Job job = new Job(id);
            job.state = o.optString("state", STATE_PENDING);
            job.nextChapter = Math.max(0, o.optInt("nextChapter", 0));
            job.totalChapters = Math.max(0, o.optInt("totalChapters", 0));
            job.failures = Math.max(0, o.optInt("failures", 0));
            job.retryAt = Math.max(0, o.optLong("retryAt", 0));
            job.lastError = o.optString("lastError", "");
            job.updatedAt = o.optLong("updatedAt", 0);
            job.revision = Math.max(1L, o.optLong("revision", 1L));
            return job;
        } catch (Exception ignored) { return null; }
    }

    private static boolean containsBook(List<Job> jobs, String bookId) {
        for (Job job : jobs) if (job.bookId.equals(bookId)) return true;
        return false;
    }

    private static String key(String bookId) {
        String encoded = Base64.encodeToString(safe(bookId).getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return PREFIX + encoded;
    }

    static final class Job {
        final String bookId;
        String state = STATE_PENDING;
        int nextChapter;
        int totalChapters;
        int failures;
        long retryAt;
        String lastError = "";
        long updatedAt = System.currentTimeMillis();
        long revision = 1L;

        Job(String bookId) { this.bookId = safe(bookId); }

        Job copy() {
            Job copy = new Job(bookId);
            copy.state = state;
            copy.nextChapter = nextChapter;
            copy.totalChapters = totalChapters;
            copy.failures = failures;
            copy.retryAt = retryAt;
            copy.lastError = lastError;
            copy.updatedAt = updatedAt;
            copy.revision = revision;
            return copy;
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
}