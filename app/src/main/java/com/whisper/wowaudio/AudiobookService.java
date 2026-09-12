package com.whisper.wowaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Audiobook service: one logical book timeline, cached speech segments, Edge/Gemini/offline
 * voice engines, exact per-segment resume, book scrub, live speed/tone/volume, and controls.
 */
public final class AudiobookService extends Service {
    static final String ACTION_PLAY_BOOK = "com.whisper.wowaudio.v2.PLAY_BOOK";
    static final String ACTION_TOGGLE = "com.whisper.wowaudio.v2.TOGGLE";
    static final String ACTION_SEEK_BACK = "com.whisper.wowaudio.v2.SEEK_BACK";
    static final String ACTION_SEEK_FORWARD = "com.whisper.wowaudio.v2.SEEK_FORWARD";
    static final String ACTION_SEEK_BOOK_PROGRESS = "com.whisper.wowaudio.v2.SEEK_BOOK_PROGRESS";
    static final String ACTION_PREVIOUS = "com.whisper.wowaudio.v2.PREVIOUS";
    static final String ACTION_NEXT = "com.whisper.wowaudio.v2.NEXT";
    static final String ACTION_STOP = "com.whisper.wowaudio.v2.STOP";
    static final String ACTION_REQUEST_STATE = "com.whisper.wowaudio.v2.REQUEST_STATE";
    static final String ACTION_NARRATION_SETTINGS_CHANGED = "com.whisper.wowaudio.v2.NARRATION_SETTINGS_CHANGED";
    static final String ACTION_STATE = "com.whisper.wowaudio.v2.STATE";

    static final String EXTRA_BOOK_ID = "book_id";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_PLAYING = "playing";
    static final String EXTRA_PROGRESS = "progress";
    static final String EXTRA_POSITION_MS = "position_ms";
    static final String EXTRA_DURATION_MS = "duration_ms";
    static final String EXTRA_SEGMENT_INDEX = "segment_index";
    static final String EXTRA_SEGMENT_COUNT = "segment_count";
    static final String EXTRA_TEXT = "current_text";
    static final String EXTRA_VOICE_LABEL = "voice_label";
    static final String EXTRA_SPEED_LABEL = "speed_label";
    static final String EXTRA_TONE_LABEL = "tone_label";
    static final String EXTRA_STYLE_LABEL = "style_label";
    static final String EXTRA_VOLUME_LABEL = "volume_label";

    private static final String CHANNEL = "audiobook_v2";
    private static final int NOTIFICATION_ID = 1201;
    private static final int SEEK_MS = 15_000;

    private final Object pauseLock = new Object();
    private final Object synthesisLock = new Object();
    private final ExecutorService prefetchExecutor = Executors.newSingleThreadExecutor();

    private volatile long sessionToken;
    private volatile boolean paused;
    private volatile MediaPlayer currentPlayer;
    private volatile LoudnessEnhancer currentEnhancer;
    private volatile Thread worker;
    private volatile Future<?> prefetchFuture;
    private volatile BookStore.Book activeBook;
    private volatile List<String> activeSegments = Collections.emptyList();
    private volatile int activeSegment;
    private volatile int activePositionMs;
    private volatile int activeDurationMs;
    private volatile String activeText = "";
    private volatile Profile activeProfile;
    private volatile float activePlaybackSpeed = 1.0f;
    private volatile float activePlaybackPitch = 1.0f;
    private volatile int activeVolumeBoostMb = 300;
    private volatile BurmeseProsody.Profile activeProsody = BurmeseProsody.neutral();
    private volatile long edgeRetryAfterMs;
    private volatile long geminiRetryAfterMs;

    private EdgeMyanmarTtsClient edgeTts;
    private GeminiTtsClient geminiTts;
    private MmsMyanmarTtsEngine offlineTts;
    private F5LocalTtsEngine f5Tts;

    private static final class Profile {
        final String engine;
        final String edgeVoice;
        final String geminiModel;
        final String geminiVoice;
        final String geminiStyle;
        final float speed;

        Profile(String engine, String edgeVoice, String geminiModel,
                String geminiVoice, String geminiStyle, float speed) {
            this.engine = engine;
            this.edgeVoice = edgeVoice;
            this.geminiModel = geminiModel;
            this.geminiVoice = geminiVoice;
            this.geminiStyle = geminiStyle;
            this.speed = speed;
        }

        String label() {
            if (VoiceSettings.ENGINE_GEMINI.equals(engine)) return "Gemini • " + geminiVoice;
            if (VoiceSettings.ENGINE_F5.equals(engine)) return F5MyanmarVoicePack.DISPLAY_NAME_AUNG_GYI + " • Local";
            if (VoiceSettings.ENGINE_OFFLINE.equals(engine)) return "Offline Burmese";
            return EdgeMyanmarTtsClient.VOICE_THIHA.equals(edgeVoice) ? "Thiha" : "Nilar";
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        edgeTts = new EdgeMyanmarTtsClient();
        geminiTts = new GeminiTtsClient();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopReading(true);
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) {
            if (activeBook == null) broadcast("Nothing is playing.", false);
            else if (paused) resumeReading(); else pauseReading();
            return START_NOT_STICKY;
        }
        if (ACTION_SEEK_BACK.equals(action)) {
            seekBy(-SEEK_MS);
            return START_NOT_STICKY;
        }
        if (ACTION_SEEK_FORWARD.equals(action)) {
            seekBy(SEEK_MS);
            return START_NOT_STICKY;
        }
        if (ACTION_SEEK_BOOK_PROGRESS.equals(action)) {
            seekBook(intent.getIntExtra(EXTRA_PROGRESS, 0));
            return START_NOT_STICKY;
        }
        if (ACTION_PREVIOUS.equals(action)) {
            jumpSegment(-1);
            return START_NOT_STICKY;
        }
        if (ACTION_NEXT.equals(action)) {
            jumpSegment(1);
            return START_NOT_STICKY;
        }
        if (ACTION_NARRATION_SETTINGS_CHANGED.equals(action)) {
            applyNarrationSettings();
            return START_NOT_STICKY;
        }
        if (ACTION_REQUEST_STATE.equals(action)) {
            if (activeBook != null) broadcast(paused ? "Paused" : "Reading", !paused);
            else {
                broadcast("Ready", false);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY_BOOK.equals(action)) {
            String id = intent.getStringExtra(EXTRA_BOOK_ID);
            if (id != null && !id.trim().isEmpty()) startBook(id, false);
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        sessionToken++;
        releaseCurrentPlayer();
        Thread t = worker;
        if (t != null) t.interrupt();
        Future<?> future = prefetchFuture;
        if (future != null) future.cancel(true);
        prefetchExecutor.shutdownNow();
        try { if (edgeTts != null) edgeTts.close(); } catch (Throwable ignored) { }
        try { if (geminiTts != null) geminiTts.close(); } catch (Throwable ignored) { }
        synchronized (synthesisLock) {
            if (offlineTts != null) {
                try { offlineTts.close(); } catch (Throwable ignored) { }
                offlineTts = null;
            }
            if (f5Tts != null) {
                try { f5Tts.close(); } catch (Throwable ignored) { }
                f5Tts = null;
            }
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void startBook(String id, boolean startPaused) {
        final long token = ++sessionToken;
        releaseCurrentPlayer();
        paused = startPaused;
        synchronized (pauseLock) { pauseLock.notifyAll(); }

        Thread old = worker;
        if (old != null && old.isAlive()) old.interrupt();
        Future<?> oldPrefetch = prefetchFuture;
        if (oldPrefetch != null) oldPrefetch.cancel(true);

        BookStore.Book target = new BookStore(this).get(id);
        activeBook = target;
        activePositionMs = 0;
        activeDurationMs = 0;
        activeText = "";
        activePlaybackSpeed = VoiceSettings.playbackSpeed(this);
        activePlaybackPitch = VoiceSettings.playbackPitch(this);
        activeVolumeBoostMb = VoiceSettings.volumeBoostMb(this);
        activeProfile = profileFromSettings();
        if (target == null) {
            broadcast("Book not found.", false);
            stopSelf();
            return;
        }

        startForeground(NOTIFICATION_ID,
                notification(startPaused ? "Paused" : "Preparing voice…", false));
        broadcast(startPaused ? "Paused" : "Preparing " + activeProfile.label() + "…", false);

        Thread t = new Thread(() -> readBookLoop(target, activeProfile, token), "wow-audiobook-reader");
        worker = t;
        t.start();
    }

    private void readBookLoop(BookStore.Book target, Profile profile, long token) {
        try {
            String text = new BookStore(this).readText(target);
            List<String> segments = TtsText.chunks(text);
            if (segments.isEmpty()) throw new IllegalArgumentException("No readable Myanmar text was found.");
            activeSegments = segments;

            SharedPreferences p = progressPrefs();
            int index = clamp(p.getInt(key(target.id, "chunk"), 0), 0, segments.size() - 1);
            final int resumeIndex = index;
            final int firstPositionMs = Math.max(0, p.getInt(key(target.id, "offset"), 0));

            while (index < segments.size() && token == sessionToken) {
                waitWhilePaused(token);
                if (token != sessionToken) return;

                activeSegment = index;
                activeText = segments.get(index);
                activeProsody = BurmeseProsody.analyze(activeText, VoiceSettings.readingStyle(this));
                activeDurationMs = 0;
                File audio = ensureAudio(target, index, activeText, profile, true, token);
                if (audio == null || token != sessionToken) return;

                schedulePrefetch(target, segments, index, profile, token);

                int startMs = index == resumeIndex ? firstPositionMs : 0;
                activePositionMs = startMs;
                updateNotification("Reading • " + profile.label() + " • " + (index + 1) + "/" + segments.size(), true);
                broadcast("Reading • " + profile.label(), true);

                boolean completed = playFileBlocking(audio, target.id, index, startMs, token);
                if (token != sessionToken || !completed) return;
                waitBetweenSegments(profile, token);

                index++;
                activeSegment = index;
                activePositionMs = 0;
                activeDurationMs = 0;
                if (index < segments.size()) saveProgress(target.id, index, 0);
            }

            if (token == sessionToken && index >= segments.size()) {
                clearProgress(target.id);
                broadcast("Finished " + target.title, false);
                activeBook = null;
                activeSegments = Collections.emptyList();
                activeSegment = 0;
                activePositionMs = 0;
                activeDurationMs = 0;
                activeText = "";
                stopForeground(true);
                stopSelf();
            }
        } catch (Throwable e) {
            if (token == sessionToken) {
                paused = true;
                BookStore.Book b = activeBook;
                if (b != null) saveProgress(b.id, activeSegment, activePositionMs);
                String message = "Voice failed: " + safeMessage(e);
                broadcast(message, false);
                updateNotification("Voice error • tap Resume", false);
            }
        } finally {
            if (worker == Thread.currentThread()) worker = null;
        }
    }

    private File ensureAudio(BookStore.Book book, int index, String text, Profile profile,
                             boolean allowFallback, long token) throws Exception {
        // Never cross-read a different Edge voice. The requested profile is authoritative for the
        // whole session, and its cache path is voice-specific. If the selected Edge voice cannot
        // be produced, use the offline fallback rather than substituting another Nilar/Thiha cache.
        File requested = AudioCache.existing(book, index, profile.engine, profile.edgeVoice,
                profile.geminiModel, profile.geminiVoice, profile.geminiStyle, profile.speed, text);
        if (requested != null) return requested;

        synchronized (synthesisLock) {
            if (token != sessionToken) return null;
            requested = AudioCache.existing(book, index, profile.engine, profile.edgeVoice,
                    profile.geminiModel, profile.geminiVoice, profile.geminiStyle, profile.speed, text);
            if (requested != null) return requested;

            Throwable primaryFailure = null;

            if (VoiceSettings.ENGINE_F5.equals(profile.engine)) {
                if (!F5MyanmarVoicePack.isInstalled(this)) {
                    throw new IllegalStateException("အောင်ကြီး voice needs the Q4 model ZIP and private reference WAV. Open Settings to import them.");
                }
                File local = AudioCache.f5(book, index, text);
                if (local.isFile() && local.length() > 44) return local;
                if (allowFallback) {
                    broadcast("Generating အောင်ကြီး locally…", false);
                    updateNotification("Local voice • အောင်ကြီး…", false);
                }
                AudioCache.ensureParent(local);
                F5LocalTtsEngine.Audio generated = f5Engine().synthesize(this, text);
                if (generated == null || generated.samples == null || generated.samples.length == 0) {
                    throw new IllegalStateException("အောင်ကြီး local voice produced no audio.");
                }
                WavFile.writeMonoPcm16(local, generated.samples, generated.sampleRate);
                return local;
            }

            if (VoiceSettings.ENGINE_GEMINI.equals(profile.engine)) {
                String apiKey = SecureApiKeyStore.getGeminiKey(this);
                if (!apiKey.isEmpty() && System.currentTimeMillis() >= geminiRetryAfterMs) {
                    File gemini = AudioCache.gemini(book, index, profile.geminiModel,
                            profile.geminiVoice, profile.geminiStyle, text);
                    try {
                        if (allowFallback) {
                            broadcast("Getting Gemini voice…", false);
                            updateNotification("Getting Gemini • " + profile.geminiVoice + "…", false);
                        }
                        AudioCache.ensureParent(gemini);
                        geminiTts.synthesizeToFile(text, apiKey, profile.geminiModel,
                                profile.geminiVoice, profile.geminiStyle, gemini);
                        geminiRetryAfterMs = 0L;
                        return gemini;
                    } catch (Throwable t) {
                        primaryFailure = t;
                        geminiRetryAfterMs = System.currentTimeMillis() + 30_000L;
                    }
                } else if (apiKey.isEmpty()) {
                    primaryFailure = new IllegalStateException("Gemini API key is not set");
                }
                if (!allowFallback) return null;
                broadcast("Gemini unavailable • using WoW Natural fallback", false);
            }

            if (!VoiceSettings.ENGINE_OFFLINE.equals(profile.engine)) {
                File edge = AudioCache.edge(book, index, profile.edgeVoice, profile.speed, text);
                if (edge.isFile() && edge.length() > 1024) return edge;
                if (System.currentTimeMillis() >= edgeRetryAfterMs) {
                    try {
                        if (allowFallback) updateNotification("Getting " + edgeLabel(profile.edgeVoice) + "…", false);
                        AudioCache.ensureParent(edge);
                        edgeTts.synthesizeToFile(text, profile.edgeVoice, profile.speed, edge);
                        edgeRetryAfterMs = 0L;
                        return edge;
                    } catch (Throwable t) {
                        if (primaryFailure == null) primaryFailure = t;
                        edgeRetryAfterMs = System.currentTimeMillis() + 45_000L;
                    }
                }
                if (!allowFallback) return null;
            } else if (!allowFallback) {
                return null;
            }

            File offline = AudioCache.offline(book, index, profile.speed, text);
            if (offline.isFile() && offline.length() > 44) return offline;
            String reason = primaryFailure == null ? "network unavailable" : safeMessage(primaryFailure);
            broadcast("Using offline Burmese backup", false);
            updateNotification("Offline backup • " + reason, false);
            AudioCache.ensureParent(offline);
            MmsMyanmarTtsEngine.Audio generated = offlineEngine().synthesize(text, profile.speed);
            if (generated == null || generated.samples == null || generated.samples.length == 0) {
                throw new IllegalStateException("Offline backup produced no audio.");
            }
            WavFile.writeMonoPcm16(offline, generated.samples, generated.sampleRate);
            return offline;
        }
    }

    private F5LocalTtsEngine f5Engine() throws Exception {
        synchronized (synthesisLock) {
            if (f5Tts == null) f5Tts = new F5LocalTtsEngine(this);
            return f5Tts;
        }
    }

    private MmsMyanmarTtsEngine offlineEngine() {
        synchronized (synthesisLock) {
            if (offlineTts == null) offlineTts = new MmsMyanmarTtsEngine(this);
            return offlineTts;
        }
    }

    private void schedulePrefetch(BookStore.Book book, List<String> segments, int current,
                                  Profile profile, long token) {
        Future<?> previous = prefetchFuture;
        if (previous != null && !previous.isDone()) return;
        final int count = (VoiceSettings.ENGINE_GEMINI.equals(profile.engine) || VoiceSettings.ENGINE_F5.equals(profile.engine)) ? 1 : 3;
        prefetchFuture = prefetchExecutor.submit(() -> {
            for (int i = current + 1; i <= current + count && i < segments.size(); i++) {
                if (token != sessionToken || Thread.currentThread().isInterrupted()) return;
                try {
                    ensureAudio(book, i, segments.get(i), profile, false, token);
                } catch (Throwable ignored) {
                    return;
                }
            }
        });
    }

    private boolean playFileBlocking(File file, String bookId, int segmentIndex,
                                     int startPositionMs, long token) throws Exception {
        MediaPlayer player = new MediaPlayer();
        LoudnessEnhancer enhancer = null;
        currentPlayer = player;
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();

        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setVolume(1.0f, 1.0f);
            player.setOnCompletionListener(mp -> finished.countDown());
            player.setOnErrorListener((mp, what, extra) -> {
                error.compareAndSet(null, "Android audio error " + what + "/" + extra);
                finished.countDown();
                return true;
            });
            player.setDataSource(file.getAbsolutePath());
            player.prepare();
            enhancer = createEnhancer(player);
            int duration = player.getDuration();
            if (duration <= 0) throw new IllegalStateException("Speech audio has zero duration.");
            activeDurationMs = duration;
            int seek = clamp(startPositionMs, 0, Math.max(0, duration - 250));
            if (seek > 0) player.seekTo(seek);
            activePositionMs = seek;

            waitWhilePaused(token);
            if (token != sessionToken) return false;
            player.start();
            applyPlaybackParams(player);
            applyVolumeBoost(enhancer);
            broadcast("Reading • " + profileLabel(), true);

            long lastSave = 0L;
            long lastState = 0L;
            while (token == sessionToken && finished.getCount() > 0) {
                if (paused) {
                    try { if (player.isPlaying()) player.pause(); } catch (IllegalStateException ignored) { }
                    activePositionMs = safePosition(player);
                    saveProgress(bookId, segmentIndex, activePositionMs);
                    broadcast("Paused", false);
                    waitWhilePaused(token);
                    if (token != sessionToken) break;
                    try {
                        if (!player.isPlaying()) player.start();
                        applyPlaybackParams(player);
                        applyVolumeBoost(enhancer);
                    } catch (IllegalStateException e) {
                        error.compareAndSet(null, "Could not resume audio output.");
                        break;
                    }
                    broadcast("Reading • " + profileLabel(), true);
                }

                activePositionMs = safePosition(player);
                long now = System.currentTimeMillis();
                if (now - lastSave >= 850L) {
                    saveProgress(bookId, segmentIndex, activePositionMs);
                    lastSave = now;
                }
                if (now - lastState >= 450L) {
                    broadcast("Reading • " + profileLabel(), true);
                    lastState = now;
                }
                if (finished.await(140, TimeUnit.MILLISECONDS)) break;
            }

            String playbackError = error.get();
            if (playbackError != null) throw new IllegalStateException(playbackError);
            return token == sessionToken && finished.getCount() == 0;
        } finally {
            if (currentPlayer == player) currentPlayer = null;
            if (currentEnhancer == enhancer) currentEnhancer = null;
            releaseEnhancer(enhancer);
            releasePlayer(player);
        }
    }

    private void seekBy(int deltaMs) {
        BookStore.Book book = activeBook;
        List<String> segments = activeSegments;
        if (book == null || segments == null || segments.isEmpty()) {
            broadcast("Nothing is playing.", false);
            return;
        }

        MediaPlayer player = currentPlayer;
        int index = clamp(activeSegment, 0, segments.size() - 1);
        int current = activePositionMs;
        int duration = activeDurationMs;
        if (player != null) {
            try {
                current = player.getCurrentPosition();
                duration = player.getDuration();
            } catch (IllegalStateException ignored) { }
        }
        int target = current + deltaMs;

        if (duration > 0 && target >= 0 && target < duration && player != null) {
            try {
                player.seekTo(target);
                activePositionMs = target;
                saveProgress(book.id, index, target);
                broadcast(deltaMs < 0 ? "Back 15 seconds" : "Forward 15 seconds", !paused);
                return;
            } catch (Exception ignored) { }
        }

        boolean keepPaused = paused;
        if (target < 0 && index > 0) {
            int previousIndex = index - 1;
            File previous = existingForProfile(book, previousIndex, segments.get(previousIndex));
            int previousDuration = durationOf(previous);
            int previousPosition = previousDuration > 0 ? Math.max(0, previousDuration + target) : 0;
            saveProgress(book.id, previousIndex, previousPosition);
            startBook(book.id, keepPaused);
            return;
        }
        if (duration > 0 && target >= duration && index + 1 < segments.size()) {
            int nextPosition = Math.max(0, target - duration);
            saveProgress(book.id, index + 1, nextPosition);
            startBook(book.id, keepPaused);
            return;
        }

        int clamped = duration > 0 ? clamp(target, 0, Math.max(0, duration - 250)) : Math.max(0, target);
        if (player != null && duration > 0) {
            try { player.seekTo(clamped); } catch (Exception ignored) { }
        }
        activePositionMs = clamped;
        saveProgress(book.id, index, clamped);
        broadcast(deltaMs < 0 ? "Back 15 seconds" : "Forward 15 seconds", !paused);
    }

    private void seekBook(int progress) {
        BookStore.Book book = activeBook;
        List<String> segments = activeSegments;
        if (book == null || segments == null || segments.isEmpty()) {
            broadcast("Nothing is playing.", false);
            return;
        }
        int safe = clamp(progress, 0, 10000);
        double scaled = (safe / 10000.0) * segments.size();
        int index = Math.min(segments.size() - 1, (int) Math.floor(scaled));
        double fraction = Math.max(0.0, Math.min(0.999, scaled - index));
        File cached = existingForProfile(book, index, segments.get(index));
        int duration = durationOf(cached);
        int position = duration > 0 ? (int) Math.round(duration * fraction) : 0;
        saveProgress(book.id, index, position);
        startBook(book.id, paused);
    }

    private void jumpSegment(int delta) {
        BookStore.Book book = activeBook;
        List<String> segments = activeSegments;
        if (book == null || segments == null || segments.isEmpty()) {
            broadcast("Nothing is playing.", false);
            return;
        }
        int target = clamp(activeSegment + delta, 0, segments.size() - 1);
        saveProgress(book.id, target, 0);
        startBook(book.id, paused);
    }

    private void applyNarrationSettings() {
        float newSpeed = VoiceSettings.playbackSpeed(this);
        float newPitch = VoiceSettings.playbackPitch(this);
        int newVolumeBoostMb = VoiceSettings.volumeBoostMb(this);
        String newGeminiStyle = VoiceSettings.effectiveGeminiStyle(this);
        String oldGeminiStyle = activeProfile == null ? "" : activeProfile.geminiStyle;
        activePlaybackSpeed = newSpeed;
        activePlaybackPitch = newPitch;
        activeVolumeBoostMb = newVolumeBoostMb;
        activeProsody = BurmeseProsody.analyze(activeText, VoiceSettings.readingStyle(this));

        BookStore.Book book = activeBook;
        if (book == null) {
            broadcast("Narration settings saved", false);
            stopSelf();
            return;
        }

        if (activeProfile != null
                && VoiceSettings.ENGINE_GEMINI.equals(activeProfile.engine)
                && !oldGeminiStyle.equals(newGeminiStyle)) {
            int position = safePosition(currentPlayer);
            saveProgress(book.id, activeSegment, position);
            boolean keepPaused = paused;
            startBook(book.id, keepPaused);
            return;
        }

        MediaPlayer player = currentPlayer;
        if (player != null && !paused) applyPlaybackParams(player);
        applyVolumeBoost(currentEnhancer);
        broadcast("Narration • " + VoiceSettings.speedLabel(this)
                + " • " + VoiceSettings.volumeLabel(this)
                + " • " + VoiceSettings.toneLabel(this)
                + " • " + VoiceSettings.readingStyleLabel(this), !paused);
    }

    private void applyPlaybackParams(MediaPlayer player) {
        if (player == null) return;
        try {
            PlaybackParams params = player.getPlaybackParams();
            BurmeseProsody.Profile prosody = effectiveProsody();
            params.setSpeed(clampFloat(activePlaybackSpeed * prosody.speedMultiplier, 0.60f, 2.0f));
            params.setPitch(clampFloat(activePlaybackPitch * prosody.pitchMultiplier, 0.85f, 1.20f));
            player.setPlaybackParams(params);
        } catch (Throwable ignored) { }
    }

    private LoudnessEnhancer createEnhancer(MediaPlayer player) {
        try {
            LoudnessEnhancer enhancer = new LoudnessEnhancer(player.getAudioSessionId());
            currentEnhancer = enhancer;
            applyVolumeBoost(enhancer);
            return enhancer;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void applyVolumeBoost(LoudnessEnhancer enhancer) {
        if (enhancer == null) return;
        try {
            int target = Math.max(0, Math.min(1200, activeVolumeBoostMb + effectiveProsody().gainMb));
            enhancer.setTargetGain(target);
            enhancer.setEnabled(target > 0);
        } catch (Throwable ignored) { }
    }

    private BurmeseProsody.Profile effectiveProsody() {
        Profile profile = activeProfile;
        if (profile != null && VoiceSettings.ENGINE_GEMINI.equals(profile.engine)) return BurmeseProsody.neutral();
        BurmeseProsody.Profile p = activeProsody;
        return p == null ? BurmeseProsody.neutral() : p;
    }

    private void waitBetweenSegments(Profile profile, long token) {
        if (profile != null && VoiceSettings.ENGINE_GEMINI.equals(profile.engine)) return;
        int waitMs = effectiveProsody().pauseAfterMs;
        long end = System.currentTimeMillis() + waitMs;
        while (token == sessionToken && System.currentTimeMillis() < end) {
            if (Thread.currentThread().isInterrupted()) return;
            try { Thread.sleep(Math.min(30L, Math.max(1L, end - System.currentTimeMillis()))); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void releaseEnhancer(LoudnessEnhancer enhancer) {
        if (enhancer == null) return;
        try { enhancer.setEnabled(false); } catch (Throwable ignored) { }
        try { enhancer.release(); } catch (Throwable ignored) { }
    }

    private File existingForProfile(BookStore.Book book, int index, String text) {
        Profile profile = activeProfile == null ? profileFromSettings() : activeProfile;
        File f = AudioCache.existing(book, index, profile.engine, profile.edgeVoice,
                profile.geminiModel, profile.geminiVoice, profile.geminiStyle, profile.speed, text);
        if (f != null) return f;
        if (VoiceSettings.ENGINE_F5.equals(profile.engine)) return null;
        if (VoiceSettings.ENGINE_GEMINI.equals(profile.engine)) {
            File edge = AudioCache.edge(book, index, profile.edgeVoice, profile.speed, text);
            if (edge.isFile() && edge.length() > 1024) return edge;
        }
        File offline = AudioCache.offline(book, index, profile.speed, text);
        return offline.isFile() && offline.length() > 44 ? offline : null;
    }

    private void pauseReading() {
        paused = true;
        MediaPlayer player = currentPlayer;
        if (player != null) {
            try { if (player.isPlaying()) player.pause(); } catch (IllegalStateException ignored) { }
        }
        BookStore.Book book = activeBook;
        if (book != null) saveProgress(book.id, activeSegment, safePosition(player));
        broadcast("Paused", false);
        updateNotification("Paused • " + progressText(), false);
    }

    private void resumeReading() {
        BookStore.Book book = activeBook;
        if (book == null) return;
        paused = false;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
        broadcast("Resumed", true);
        updateNotification(progressText(), true);
        Thread t = worker;
        if (t == null || !t.isAlive()) startBook(book.id, false);
    }

    private void stopReading(boolean savePosition) {
        BookStore.Book book = activeBook;
        if (savePosition && book != null) saveProgress(book.id, activeSegment, safePosition(currentPlayer));
        sessionToken++;
        paused = false;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
        releaseCurrentPlayer();
        Thread t = worker;
        if (t != null) t.interrupt();
        Future<?> future = prefetchFuture;
        if (future != null) future.cancel(true);
        activeBook = null;
        activeSegments = Collections.emptyList();
        activeSegment = 0;
        activePositionMs = 0;
        activeDurationMs = 0;
        activeText = "";
        broadcast("Stopped", false);
        stopForeground(true);
        stopSelf();
    }

    private void waitWhilePaused(long token) {
        while (paused && token == sessionToken) {
            synchronized (pauseLock) {
                if (!paused || token != sessionToken) return;
                try { pauseLock.wait(250); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private Profile profileFromSettings() {
        return new Profile(
                VoiceSettings.engine(this),
                VoiceSettings.edgeVoice(this),
                VoiceSettings.geminiModel(this),
                VoiceSettings.geminiVoice(this),
                VoiceSettings.effectiveGeminiStyle(this),
                1.0f);
    }

    private String profileLabel() {
        Profile p = activeProfile;
        return p == null ? VoiceSettings.engineLabel(this) : p.label();
    }

    private static String edgeLabel(String voice) {
        return EdgeMyanmarTtsClient.VOICE_THIHA.equals(voice) ? "Thiha" : "Nilar";
    }

    private void releaseCurrentPlayer() {
        LoudnessEnhancer enhancer = currentEnhancer;
        currentEnhancer = null;
        releaseEnhancer(enhancer);
        MediaPlayer player = currentPlayer;
        currentPlayer = null;
        if (player != null) releasePlayer(player);
    }

    private static void releasePlayer(MediaPlayer player) {
        if (player == null) return;
        try { player.stop(); } catch (Exception ignored) { }
        try { player.reset(); } catch (Exception ignored) { }
        try { player.release(); } catch (Exception ignored) { }
    }

    private int safePosition(MediaPlayer player) {
        if (player == null) return activePositionMs;
        try { return Math.max(0, player.getCurrentPosition()); }
        catch (Exception ignored) { return activePositionMs; }
    }

    private int durationOf(File file) {
        if (file == null || !file.isFile()) return 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return raw == null ? 0 : Math.max(0, Integer.parseInt(raw));
        } catch (Exception ignored) {
            return 0;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    private void saveProgress(String id, int chunk, int offset) {
        progressPrefs().edit().putInt(key(id, "chunk"), Math.max(0, chunk))
                .putInt(key(id, "offset"), Math.max(0, offset)).apply();
    }

    private void clearProgress(String id) {
        progressPrefs().edit().remove(key(id, "chunk")).remove(key(id, "offset")).apply();
    }

    private SharedPreferences progressPrefs() {
        return getSharedPreferences("reading_progress", MODE_PRIVATE);
    }

    private static String key(String id, String suffix) {
        return id + ":" + suffix;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "WoW Audio playback",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Audiobook playback controls");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void updateNotification(String message, boolean playing) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(message, playing));
    }

    private Notification notification(String message, boolean playing) {
        Intent open = new Intent(this, PlayerActivity.class);
        BookStore.Book book = activeBook;
        if (book != null) open.putExtra(EXTRA_BOOK_ID, book.id);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 30, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle = PendingIntent.getService(this, 31,
                new Intent(this, AudiobookService.class).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent back = PendingIntent.getService(this, 32,
                new Intent(this, AudiobookService.class).setAction(ACTION_SEEK_BACK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent forward = PendingIntent.getService(this, 33,
                new Intent(this, AudiobookService.class).setAction(ACTION_SEEK_FORWARD),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(book == null ? "WoW Audio" : book.title)
                .setContentText(message)
                .setContentIntent(contentIntent)
                .setOngoing(playing)
                .addAction(new Notification.Action.Builder(null, "-15s", back).build())
                .addAction(new Notification.Action.Builder(null, playing ? "Pause" : "Play", toggle).build())
                .addAction(new Notification.Action.Builder(null, "+15s", forward).build());
        if (Build.VERSION.SDK_INT >= 21) b.setCategory(Notification.CATEGORY_TRANSPORT);
        return b.build();
    }

    private void broadcast(String message, boolean playing) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        BookStore.Book book = activeBook;
        if (book != null) i.putExtra(EXTRA_BOOK_ID, book.id);
        i.putExtra(EXTRA_MESSAGE, message);
        i.putExtra(EXTRA_PLAYING, playing);
        i.putExtra(EXTRA_SEGMENT_INDEX, activeSegment);
        i.putExtra(EXTRA_SEGMENT_COUNT, activeSegments == null ? 0 : activeSegments.size());
        i.putExtra(EXTRA_POSITION_MS, activePositionMs);
        i.putExtra(EXTRA_DURATION_MS, activeDurationMs);
        i.putExtra(EXTRA_TEXT, activeText);
        i.putExtra(EXTRA_VOICE_LABEL, profileLabel());
        i.putExtra(EXTRA_SPEED_LABEL, VoiceSettings.speedLabel(this));
        i.putExtra(EXTRA_TONE_LABEL, VoiceSettings.toneLabel(this));
        i.putExtra(EXTRA_STYLE_LABEL, VoiceSettings.readingStyleLabel(this));
        i.putExtra(EXTRA_VOLUME_LABEL, VoiceSettings.volumeLabel(this));

        int progress = 0;
        List<String> segments = activeSegments;
        if (segments != null && !segments.isEmpty()) {
            double fraction = activeSegment / (double) segments.size();
            if (activeDurationMs > 0) fraction += (activePositionMs / (double) activeDurationMs) / segments.size();
            progress = clamp((int) Math.round(fraction * 10000.0), 0, 10000);
        }
        i.putExtra(EXTRA_PROGRESS, progress);
        sendBroadcast(i);
    }

    private String progressText() {
        int count = activeSegments == null ? 0 : activeSegments.size();
        return count <= 0 ? "Ready" : "Part " + Math.min(activeSegment + 1, count) + "/" + count;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safeMessage(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return m == null || m.trim().isEmpty()
                ? (t == null ? "Unknown error" : t.getClass().getSimpleName()) : m;
    }
}
