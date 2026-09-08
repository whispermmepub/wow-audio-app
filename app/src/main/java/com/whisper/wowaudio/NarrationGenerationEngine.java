package com.whisper.wowaudio;

import android.content.Context;

import java.io.File;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

final class NarrationGenerationEngine {
    private static final ReentrantLock ENGINE_LOCK = new ReentrantLock();

    interface Listener {
        void onStatus(String title, String text, int progress, int max, boolean ongoing);
        default boolean isCancelled() { return false; }
    }

    enum State { MORE, WAITING, IDLE, AUTH_REQUIRED }

    static final class Result {
        final State state;
        final long retryAt;
        private Result(State state, long retryAt) { this.state = state; this.retryAt = retryAt; }
        static Result more() { return new Result(State.MORE, 0); }
        static Result waiting(long retryAt) { return new Result(State.WAITING, retryAt); }
        static Result idle() { return new Result(State.IDLE, 0); }
        static Result auth() { return new Result(State.AUTH_REQUIRED, 0); }
    }

    private NarrationGenerationEngine() { }

    static Result runOne(Context context, Listener listener) {
        if (!ENGINE_LOCK.tryLock()) {
            return Result.waiting(System.currentTimeMillis() + 30_000L);
        }
        try {
            return runOneLocked(context, listener);
        } finally {
            ENGINE_LOCK.unlock();
        }
    }

    private static Result runOneLocked(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        GenerationQueueStore queue = new GenerationQueueStore(app);
        long now = System.currentTimeMillis();
        List<GenerationQueueStore.Job> due = queue.due(now);
        if (due.isEmpty()) {
            long retry = queue.earliestRetryAt();
            return retry > now ? Result.waiting(retry) : Result.idle();
        }

        GenerationQueueStore.Job job = due.get(0);
        if (listener != null && listener.isCancelled()) return Result.more();
        if (!queue.isCurrentRevision(job.bookId, job.revision)) return Result.more();

        String title = "Book";
        try {
            File source = new File(new File(app.getFilesDir(), "library"), job.bookId);
            if (!source.isFile()) {
                queue.remove(job.bookId);
                return Result.more();
            }

            String key = new SecretStore(app).getApiKey();
            if (empty(key)) {
                queue.markSetupRequired(job.bookId, job.revision, job.nextChapter, job.totalChapters);
                status(listener, "Narration setup needed", "Open WoW Audio once and add your Gemini API key.", 0, 0, false);
                return Result.auth();
            }

            NarrationUi.BookInput book = NarrationSourceLoader.load(app, job.bookId);
            title = book.title;
            int total = book.chapters.size();
            if (total == 0) {
                queue.markBlocked(job.bookId, job.revision, 0, 0, "No readable chapters found");
                status(listener, title, "No readable chapters found.", 0, 0, false);
                return Result.more();
            }

            NarrationSettings settings = new NarrationSettings(app);
            String voice = settings.voice();
            String style = settings.style();
            AudioCache cache = new AudioCache(app);

            int chapterIndex = Math.max(0, job.nextChapter);
            while (chapterIndex < total) {
                if (listener != null && listener.isCancelled()) return Result.more();
                if (!queue.isCurrentRevision(book.bookId, job.revision)) return Result.more();
                NarrationUi.ChapterInput chapter = book.chapters.get(chapterIndex);
                File target = cache.fileFor(book.bookId, chapterIndex, chapter.text, voice, style);
                if (!cache.isReady(target) || !cache.hasFollowData(target)) break;
                int complete = chapterIndex + 1;
                queue.markChapterComplete(book.bookId, job.revision, complete, total);
                status(listener, title, "Chapter " + complete + " of " + total + " already ready", complete, total, true);
                chapterIndex++;
            }

            if (chapterIndex >= total) {
                queue.markDone(book.bookId, job.revision, total);
                status(listener, title, "Audiobook ready. All " + total + " chapters are available offline. Press Play on Home.", total, total, false);
                return Result.more();
            }

            if (!GenerationEnvironment.hasNetwork(app)) {
                long retryAt = now + 2L * 60_000L;
                queue.markWaiting(book.bookId, job.revision, chapterIndex, total, retryAt, "Internet connection unavailable");
                status(listener, title, "Internet is offline. Chapter " + (chapterIndex + 1) + " will continue automatically.", chapterIndex, total, false);
                return Result.waiting(retryAt);
            }

            if (!GenerationEnvironment.hasStorageHeadroom(app)) {
                long retryAt = now + 30L * 60_000L;
                String available = GenerationEnvironment.availableStorageText(app);
                queue.markWaiting(book.bookId, job.revision, chapterIndex, total, retryAt, "Low storage: " + available + " free");
                status(listener, title, "Storage is low (" + available + " free). Free some space; preparation will retry automatically.", chapterIndex, total, false);
                return Result.waiting(retryAt);
            }

            final int current = chapterIndex;
            final int chapterNumber = chapterIndex + 1;
            NarrationUi.ChapterInput chapter = book.chapters.get(chapterIndex);
            File target = cache.fileFor(book.bookId, chapterIndex, chapter.text, voice, style);
            status(listener, title, "Preparing chapter " + chapterNumber + " of " + total + ": " + chapter.title, chapterIndex, total, true);

            try {
                new GeminiTtsClient().generateToWav(key, chapter.text, voice, style, target,
                        new GeminiTtsClient.Progress() {
                            @Override public void onChunk(int completed, int chunks) {
                                if (listener != null && listener.isCancelled()) throw new GenerationCancelledException();
                                status(listener, book.title,
                                        "Chapter " + chapterNumber + " of " + total + " • part " + completed + " of " + chunks,
                                        chapterNumber - 1, total, true);
                            }

                            @Override public void onWait(int seconds, String reason) {
                                status(listener, book.title,
                                        reason + ". Retrying automatically in " + seconds + " seconds.",
                                        chapterNumber - 1, total, true);
                            }
                        });
                if (!queue.isCurrentRevision(book.bookId, job.revision)) return Result.more();
                queue.markChapterComplete(book.bookId, job.revision, chapterNumber, total);
                status(listener, title, "Chapter " + chapterNumber + " of " + total + " ready", chapterNumber, total, true);
                return Result.more();
            } catch (GenerationCancelledException e) {
                return Result.more();
            } catch (GeminiTtsClient.TtsException e) {
                if (!queue.isCurrentRevision(book.bookId, job.revision)) return Result.more();
                if (e.authenticationFailure) {
                    queue.markAuthRequired(book.bookId, job.revision, current, total, safeMessage(e));
                    status(listener, title, "Gemini API key needs attention. Open WoW Audio and use Test + preview voice once.", current, total, false);
                    return Result.auth();
                }
                if (!e.retryable) {
                    queue.markBlocked(book.bookId, job.revision, current, total, safeMessage(e));
                    status(listener, title, "This chapter was blocked by the current Gemini response. Open More options for details.", current, total, false);
                    return Result.more();
                }
                GenerationQueueStore.Job currentJob = queue.get(book.bookId);
                int failures = currentJob == null ? 0 : currentJob.failures;
                long waitMs = persistentRetryMillis(failures, e.rateLimited, e.suggestedRetrySeconds);
                long retryAt = System.currentTimeMillis() + waitMs;
                queue.markWaiting(book.bookId, job.revision, current, total, retryAt, safeMessage(e));
                long minutes = Math.max(1, Math.round(waitMs / 60000.0));
                String reason = e.rateLimited ? "Gemini quota/rate limit" : "Temporary network or Gemini error";
                status(listener, title, reason + ". Chapter " + chapterNumber + " will continue automatically in about " + minutes + " minutes.", current, total, false);
                return Result.waiting(retryAt);
            } catch (OutOfMemoryError e) {
                long retryAt = System.currentTimeMillis() + 10L * 60_000L;
                queue.markWaiting(book.bookId, job.revision, current, total, retryAt, "Device memory pressure");
                status(listener, title, "Generation paused because the device was low on memory. It will continue automatically.", current, total, false);
                return Result.waiting(retryAt);
            } catch (Exception e) {
                if (!queue.isCurrentRevision(book.bookId, job.revision)) return Result.more();
                GenerationQueueStore.Job currentJob = queue.get(book.bookId);
                int failures = currentJob == null ? 0 : currentJob.failures;
                long waitMs = persistentRetryMillis(failures, false, 120);
                long retryAt = System.currentTimeMillis() + waitMs;
                queue.markWaiting(book.bookId, job.revision, current, total, retryAt, safeMessage(e));
                status(listener, title, "Temporary generation problem. Chapter " + chapterNumber + " will retry automatically.", current, total, false);
                return Result.waiting(retryAt);
            }
        } catch (Exception e) {
            if (!queue.isCurrentRevision(job.bookId, job.revision)) return Result.more();
            long retryAt = System.currentTimeMillis() + 2L * 60_000L;
            queue.markWaiting(job.bookId, job.revision, Math.max(0, job.nextChapter), job.totalChapters, retryAt, safeMessage(e));
            status(listener, title, "Preparation paused temporarily and will continue automatically.", Math.max(0, job.nextChapter), Math.max(0, job.totalChapters), false);
            return Result.waiting(retryAt);
        }
    }

    static long persistentRetryMillis(int previousFailures, boolean rateLimited, int suggestedSeconds) {
        int failure = Math.max(0, previousFailures);
        if (rateLimited) {
            long[] waits = {5, 15, 30, 60, 120, 240};
            long minutes = waits[Math.min(waits.length - 1, failure)];
            return Math.max(minutes * 60_000L, Math.max(0, suggestedSeconds) * 1000L);
        }
        long[] waits = {1, 2, 5, 10, 20, 30};
        long minutes = waits[Math.min(waits.length - 1, failure)];
        return Math.max(minutes * 60_000L, Math.max(0, suggestedSeconds) * 1000L);
    }

    private static void status(Listener listener, String title, String text, int progress, int max, boolean ongoing) {
        if (listener != null) listener.onStatus(title, text, progress, max, ongoing);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (empty(message)) message = error == null ? "Unknown generation error" : error.getClass().getSimpleName();
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 180 ? oneLine.substring(0, 180) + "…" : oneLine;
    }

    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }

    private static final class GenerationCancelledException extends RuntimeException { }
}