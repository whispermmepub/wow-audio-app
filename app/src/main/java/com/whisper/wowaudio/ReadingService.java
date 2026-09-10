package com.whisper.wowaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ReadingService extends Service {
    static final String ACTION_PLAY_BOOK = "com.whisper.wowaudio.PLAY_BOOK";
    static final String ACTION_TOGGLE = "com.whisper.wowaudio.TOGGLE";
    static final String ACTION_STOP = "com.whisper.wowaudio.STOP";
    static final String ACTION_STATE = "com.whisper.wowaudio.STATE";
    static final String EXTRA_BOOK_ID = "book_id";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_PLAYING = "playing";

    static final String SM_ENGINE_PACKAGE = "org.saomaicenter.myanmartts";

    private static final String CHANNEL = "reading";
    private static final int NOTIFICATION_ID = 1001;

    private TextToSpeech tts;
    private BookStore.Book book;
    private List<String> chunks = Collections.emptyList();
    private int chunkIndex;
    private int charOffset;
    private int spokenBaseOffset;
    private boolean paused;
    private boolean ttsReady;
    private boolean usingSmEngine;
    private long generation;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopReading(true);
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) {
            if (book == null) return START_NOT_STICKY;
            if (paused || (tts != null && !tts.isSpeaking())) resumeReading(); else pauseReading();
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY_BOOK.equals(action)) {
            String id = intent.getStringExtra(EXTRA_BOOK_ID);
            if (id != null) startBook(id);
        }
        return START_NOT_STICKY;
    }

    private void startBook(String id) {
        generation++;
        long token = generation;
        stopTtsOnly();
        book = new BookStore(this).get(id);
        if (book == null) {
            broadcast("Book not found.", false);
            stopSelf();
            return;
        }
        startForeground(NOTIFICATION_ID, notification("Starting Myanmar voice…", false));
        new Thread(() -> {
            try {
                String text = new BookStore(this).readText(book);
                List<String> parsed = TtsText.chunks(text);
                if (parsed.isEmpty()) throw new IllegalArgumentException("No readable text.");
                SharedPreferences p = prefs();
                int savedChunk = clamp(p.getInt(key("chunk"), 0), 0, parsed.size() - 1);
                int savedOffset = Math.max(0, p.getInt(key("offset"), 0));
                runOnServiceThread(() -> {
                    if (token != generation || book == null || !book.id.equals(id)) return;
                    chunks = parsed;
                    chunkIndex = savedChunk;
                    charOffset = Math.min(savedOffset, chunks.get(chunkIndex).length());
                    initPreferredTts(token);
                });
            } catch (Exception e) {
                broadcast("Cannot read this book: " + safeMessage(e), false);
                stopReading(false);
            }
        }, "book-loader").start();
    }

    private void initPreferredTts(long token) {
        ttsReady = false;
        usingSmEngine = false;
        if (isPackageInstalled(SM_ENGINE_PACKAGE)) {
            initTts(token, true);
        } else {
            initTts(token, false);
        }
    }

    private void initTts(long token, boolean requestSmEngine) {
        stopTtsOnly();
        usingSmEngine = requestSmEngine;

        TextToSpeech.OnInitListener listener = status -> {
            if (token != generation || tts == null) return;
            if (status != TextToSpeech.SUCCESS) {
                if (usingSmEngine) {
                    // A broken/incomplete SM installation must not kill WoW Audio. Try another
                    // installed engine that genuinely exposes my-MM, then show a useful error.
                    usingSmEngine = false;
                    runOnServiceThread(() -> initTts(token, false));
                } else {
                    unavailable("Myanmar text-to-speech could not start. Install or repair SM Myanmar TTS.");
                }
                return;
            }
            configureMyanmarVoice(token);
        };

        if (requestSmEngine) {
            tts = new TextToSpeech(getApplicationContext(), listener, SM_ENGINE_PACKAGE);
        } else {
            tts = new TextToSpeech(getApplicationContext(), listener);
        }
    }

    private void configureMyanmarVoice(long token) {
        if (token != generation || tts == null) return;

        Locale myanmar = new Locale("my", "MM");
        int available = tts.isLanguageAvailable(myanmar);
        if (available < TextToSpeech.LANG_AVAILABLE) available = tts.isLanguageAvailable(new Locale("my"));

        if (available < TextToSpeech.LANG_AVAILABLE) {
            if (usingSmEngine) {
                // Some third-party engines report language availability imperfectly. Try the
                // language selection once before considering the engine unusable.
                int direct = tts.setLanguage(myanmar);
                if (direct < TextToSpeech.LANG_AVAILABLE) direct = tts.setLanguage(new Locale("my"));
                if (direct < TextToSpeech.LANG_AVAILABLE) {
                    usingSmEngine = false;
                    runOnServiceThread(() -> initTts(token, false));
                    return;
                }
            } else {
                unavailable("No Myanmar voice is available. Install SM Myanmar TTS and try Play again.");
                return;
            }
        } else {
            int result = tts.setLanguage(myanmar);
            if (result < TextToSpeech.LANG_AVAILABLE) result = tts.setLanguage(new Locale("my"));
            if (result < TextToSpeech.LANG_AVAILABLE) {
                if (usingSmEngine) {
                    usingSmEngine = false;
                    runOnServiceThread(() -> initTts(token, false));
                } else {
                    unavailable("Myanmar voice could not be selected.");
                }
                return;
            }
        }

        tts.setSpeechRate(1.0f);
        tts.setPitch(1.0f);
        tts.setOnUtteranceProgressListener(listener(token));
        ttsReady = true;
        paused = false;
        broadcast(usingSmEngine ? "SM Myanmar TTS ready" : "Myanmar voice ready", false);
        speakCurrent(token);
    }

    private UtteranceProgressListener listener(long token) {
        return new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
                if (token != generation) return;
                broadcast(progressText(), true);
                updateNotification(progressText(), true);
            }

            @Override public void onDone(String utteranceId) {
                if (token != generation || paused || book == null) return;
                chunkIndex++;
                charOffset = 0;
                if (chunkIndex >= chunks.size()) {
                    String finishedTitle = book.title;
                    clearProgress();
                    broadcast("Finished " + finishedTitle, false);
                    stopReading(false);
                    return;
                }
                saveProgress();
                speakCurrent(token);
            }

            @Override public void onError(String utteranceId) {
                if (token != generation) return;
                paused = true;
                saveProgress();
                String message = usingSmEngine
                        ? "SM Myanmar TTS stopped. Tap Resume to retry. If it repeats, repair or reinstall the full SM Myanmar TTS app."
                        : "Speech stopped. Tap Resume to try again.";
                broadcast(message, false);
                updateNotification("Speech stopped • tap Resume", false);
            }

            @Override public void onError(String utteranceId, int errorCode) {
                onError(utteranceId);
            }

            @Override public void onRangeStart(String utteranceId, int start, int end, int frame) {
                if (token != generation) return;
                charOffset = Math.max(0, spokenBaseOffset + start);
                saveProgress();
            }
        };
    }

    private void speakCurrent(long token) {
        if (token != generation || !ttsReady || tts == null || book == null) return;
        if (chunkIndex < 0 || chunkIndex >= chunks.size()) return;
        String full = chunks.get(chunkIndex);
        if (charOffset >= full.length()) charOffset = 0;
        spokenBaseOffset = charOffset;
        String piece = full.substring(charOffset).trim();
        if (piece.isEmpty()) {
            chunkIndex++;
            charOffset = 0;
            if (chunkIndex >= chunks.size()) {
                String finishedTitle = book.title;
                clearProgress();
                broadcast("Finished " + finishedTitle, false);
                stopReading(false);
            } else {
                saveProgress();
                speakCurrent(token);
            }
            return;
        }
        paused = false;
        String utterance = book.id + ":" + chunkIndex + ":" + token;
        int result = tts.speak(piece, TextToSpeech.QUEUE_FLUSH, null, utterance);
        if (result == TextToSpeech.ERROR) {
            paused = true;
            saveProgress();
            String message = usingSmEngine
                    ? "SM Myanmar TTS rejected this text. Tap Resume to retry."
                    : "The TTS engine rejected this text. Tap Resume to retry.";
            broadcast(message, false);
            updateNotification("Speech error • tap Resume", false);
        }
    }

    private void pauseReading() {
        if (tts != null) tts.stop();
        paused = true;
        saveProgress();
        broadcast("Paused", false);
        updateNotification("Paused", false);
    }

    private void resumeReading() {
        if (!ttsReady || tts == null) {
            if (book != null) startBook(book.id);
            return;
        }
        paused = false;
        speakCurrent(generation);
    }

    private void unavailable(String message) {
        paused = true;
        ttsReady = false;
        broadcast(message, false);
        updateNotification(message, false);
    }

    private void stopReading(boolean savePosition) {
        if (savePosition) saveProgress();
        generation++;
        stopTtsOnly();
        book = null;
        chunks = Collections.emptyList();
        stopForeground(true);
        stopSelf();
    }

    private void stopTtsOnly() {
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) { }
            try { tts.shutdown(); } catch (Exception ignored) { }
        }
        tts = null;
        ttsReady = false;
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void saveProgress() {
        if (book == null || chunks.isEmpty()) return;
        prefs().edit()
                .putInt(key("chunk"), Math.max(0, Math.min(chunkIndex, chunks.size() - 1)))
                .putInt(key("offset"), Math.max(0, charOffset))
                .apply();
    }

    private void clearProgress() {
        if (book == null) return;
        prefs().edit().remove(key("chunk")).remove(key("offset")).apply();
    }

    private String progressText() {
        if (book == null || chunks.isEmpty()) return "Reading";
        String engine = usingSmEngine ? "SM Myanmar TTS" : "Myanmar TTS";
        return engine + " • " + book.title + " • "
                + (Math.min(chunkIndex + 1, chunks.size())) + " of " + chunks.size();
    }

    private Notification notification(String text, boolean playing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent toggle = new Intent(this, ReadingService.class).setAction(ACTION_TOGGLE);
        PendingIntent togglePi = PendingIntent.getService(this, 2, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ReadingService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 3, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(book == null ? "WoW Audio" : book.title)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(playing)
                .addAction(new Notification.Action.Builder(
                        playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Resume", togglePi).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi).build())
                .build();
    }

    private void updateNotification(String text, boolean playing) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, notification(text, playing));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Book reading", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Controls direct Myanmar text-to-speech book reading");
            nm.createNotificationChannel(channel);
        }
    }

    private void broadcast(String message, boolean playing) {
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra(EXTRA_MESSAGE, message);
        state.putExtra(EXTRA_PLAYING, playing);
        sendBroadcast(state);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("reading_progress", MODE_PRIVATE);
    }

    private String key(String suffix) {
        return (book == null ? "none" : book.id) + ":" + suffix;
    }

    private void runOnServiceThread(Runnable r) {
        new android.os.Handler(getMainLooper()).post(r);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    @Override public void onDestroy() {
        stopTtsOnly();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
