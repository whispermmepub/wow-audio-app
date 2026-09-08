package com.whisper.wowaudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class NarrationUi {
    private static final String[] ENGINE_LABELS = {
            "Automatic — offline Burmese first",
            "Offline Burmese — eSpeak NG",
            "Gemini natural voice"
    };
    private static final String[] ENGINE_VALUES = {
            NarrationSettings.ENGINE_AUTO,
            NarrationSettings.ENGINE_OFFLINE,
            NarrationSettings.ENGINE_GEMINI
    };
    private static final String[] VOICES = {
            "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe",
            "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
            "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
    };
    private static final String[] STYLE_LABELS = {"Natural", "Warm", "Calm", "Storyteller", "Dramatic", "Soft", "Bedtime", "Custom"};
    private static final String[] STYLE_PROMPTS = {
            NarrationSettings.DEFAULT_STYLE,
            "Narrate warmly and naturally like a caring professional audiobook reader. Preserve the prepared Burmese or English text exactly. Use inviting tone, clear pronunciation, gentle emphasis, and natural pauses.",
            "Narrate in a calm, steady, composed audiobook voice. Preserve the prepared text exactly. Use clear pronunciation, relaxed pacing, smooth phrasing, and unhurried natural pauses.",
            "Narrate as an engaging professional storyteller. Preserve the prepared text exactly. Give characters and narrative turns tasteful expression while keeping pronunciation clear and pacing natural.",
            "Narrate with cinematic dramatic energy appropriate to the text, without overacting. Preserve the prepared text exactly. Use purposeful emphasis, tension, release, and clear natural pauses.",
            "Narrate softly and gently with a close, intimate audiobook tone. Preserve the prepared text exactly. Keep pronunciation clear, volume impression soft, pacing smooth, and pauses natural.",
            "Narrate in a quiet, soothing bedtime-story style. Preserve the prepared text exactly. Use a gentle low-energy delivery, slightly slower pacing, soft expression, and restful pauses.",
            ""
    };
    private static final String[] SPEED_LABELS = {"0.8×", "1.0×", "1.25×", "1.5×", "1.75×", "2.0×"};
    private static final float[] SPEED_VALUES = {0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    private static final String[] SLEEP_LABELS = {"Off", "15 min", "30 min", "45 min", "60 min", "90 min"};
    private static final int[] SLEEP_VALUES = {0, 15, 30, 45, 60, 90};

    private final Activity activity;
    private final BookInput book;
    private final Runnable onBack;
    private final SecretStore secrets;
    private final NarrationSettings settings;
    private final AudioCache cache;
    private final ListeningProgressStore progressStore;
    private final AtomicBoolean previewing = new AtomicBoolean(false);

    private Spinner engine;
    private Spinner voice;
    private Spinner stylePreset;
    private Spinner speed;
    private Spinner sleep;
    private EditText apiKey;
    private EditText style;
    private TextView status;

    NarrationUi(Activity activity, BookInput book, Runnable onBack) {
        this.activity = activity;
        this.book = book;
        this.onBack = onBack;
        secrets = new SecretStore(activity);
        settings = new NarrationSettings(activity);
        cache = new AudioCache(activity);
        progressStore = new ListeningProgressStore(activity);
    }

    void show() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(18), dp(20), dp(42));
        scroll.addView(root);
        if (Build.VERSION.SDK_INT >= 28) root.setAccessibilityPaneTitle("Narration settings");

        Button back = secondaryButton("Back to book");
        back.setOnClickListener(v -> onBack.run());
        root.addView(back, fullButton());

        LinearLayout hero = new LinearLayout(activity);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(0, dp(18), 0, dp(8));
        ImageView cover = new ImageView(activity);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        byte[] coverBytes = book.cover != null ? book.cover : BookCoverLoader.load(activity, book.bookId);
        Bitmap coverBitmap = bitmap(coverBytes);
        if (coverBitmap != null) cover.setImageBitmap(coverBitmap); else cover.setBackgroundColor(Color.rgb(224, 216, 201));
        hero.addView(cover, new LinearLayout.LayoutParams(dp(72), dp(104)));
        LinearLayout heroText = transparentColumn();
        heroText.setPadding(dp(15), 0, 0, 0);
        TextView bookTitle = heading(book.title, 22);
        heroText.addView(bookTitle);
        TextView author = text(book.author, 13, Color.rgb(103, 101, 95), false);
        author.setPadding(0, dp(4), 0, dp(5));
        heroText.addView(author);
        heroText.addView(text("Current voice: " + settings.engineLabel(), 12, Color.rgb(82, 94, 77), true));
        hero.addView(heroText, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(hero);

        LinearLayout engineCard = card();
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
        ep.topMargin = dp(10);
        engineCard.setLayoutParams(ep);
        engineCard.addView(heading("Voice engine", 19));
        TextView explanation = text("Automatic is recommended. If eSpeak NG is installed, Burmese narration is generated on this phone without internet or an API key. Otherwise WoW Audio uses Gemini when a key is available.", 13, Color.rgb(86, 84, 79), false);
        explanation.setLineSpacing(0, 1.25f);
        explanation.setPadding(0, dp(5), 0, dp(10));
        engineCard.addView(explanation);

        engine = new Spinner(activity);
        engine.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, ENGINE_LABELS));
        engine.setSelection(findEngine(settings.engineMode()));
        engine.setContentDescription("Narration engine. " + ENGINE_LABELS[findEngine(settings.engineMode())]);
        engineCard.addView(engine, new LinearLayout.LayoutParams(-1, -2));

        if (!OfflineBurmeseTtsClient.isEspeakInstalled(activity)) {
            Button install = primaryButton("Install Offline Burmese Voice");
            install.setContentDescription("Install free offline Burmese voice using the official eSpeak NG Android app.");
            LinearLayout.LayoutParams ip = fullButton();
            ip.topMargin = dp(10);
            engineCard.addView(install, ip);
            install.setOnClickListener(v -> activity.startActivity(new Intent(activity, OfflineVoiceSetupActivity.class)));
        } else {
            TextView ready = text("✓ Offline Burmese voice installed", 13, Color.rgb(61, 99, 66), true);
            ready.setPadding(0, dp(9), 0, 0);
            engineCard.addView(ready);
        }
        root.addView(engineCard);

        LinearLayout geminiCard = card();
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.topMargin = dp(10);
        geminiCard.setLayoutParams(gp);
        geminiCard.addView(heading("Gemini natural voice — optional", 18));
        TextView privacy = text("Only needed for Gemini. Your API key is encrypted on this device and excluded from Android backup.", 12, Color.rgb(103, 101, 95), false);
        privacy.setPadding(0, dp(5), 0, dp(8));
        geminiCard.addView(privacy);

        apiKey = new EditText(activity);
        apiKey.setSingleLine(true);
        apiKey.setTextSize(14);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setHint(secrets.hasApiKey() ? "Gemini API key saved — leave blank to keep" : "Paste Gemini API key, optional");
        apiKey.setContentDescription(secrets.hasApiKey() ? "Gemini API key. A key is already saved securely. Leave blank to keep it." : "Gemini API key, optional.");
        geminiCard.addView(apiKey, new LinearLayout.LayoutParams(-1, -2));

        TextView voiceLabel = text("Gemini voice", 13, Color.rgb(56, 56, 52), true);
        voiceLabel.setPadding(0, dp(10), 0, dp(3));
        geminiCard.addView(voiceLabel);
        voice = new Spinner(activity);
        voice.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, VOICES));
        voice.setSelection(findVoice(settings.voice()));
        geminiCard.addView(voice, new LinearLayout.LayoutParams(-1, -2));

        TextView presetLabel = text("Gemini style", 13, Color.rgb(56, 56, 52), true);
        presetLabel.setPadding(0, dp(10), 0, dp(3));
        geminiCard.addView(presetLabel);
        stylePreset = new Spinner(activity);
        stylePreset.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, STYLE_LABELS));
        stylePreset.setSelection(findStylePreset(settings.style()));
        geminiCard.addView(stylePreset, new LinearLayout.LayoutParams(-1, -2));

        Button applyPreset = secondaryButton("Use selected style");
        LinearLayout.LayoutParams app = fullButton();
        app.topMargin = dp(6);
        geminiCard.addView(applyPreset, app);

        style = new EditText(activity);
        style.setText(settings.style());
        style.setTextSize(13);
        style.setMinLines(3);
        style.setGravity(Gravity.TOP | Gravity.START);
        style.setContentDescription("Custom Gemini narration direction");
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = dp(6);
        geminiCard.addView(style, stp);
        applyPreset.setOnClickListener(v -> useSelectedPreset());

        LinearLayout geminiActions = new LinearLayout(activity);
        geminiActions.setOrientation(LinearLayout.HORIZONTAL);
        Button preview = secondaryButton("Preview selected voice");
        Button forget = secondaryButton("Forget Gemini key");
        geminiActions.addView(preview, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, -2, 1);
        fp.leftMargin = dp(7);
        geminiActions.addView(forget, fp);
        LinearLayout.LayoutParams ga = new LinearLayout.LayoutParams(-1, -2);
        ga.topMargin = dp(8);
        geminiCard.addView(geminiActions, ga);
        preview.setOnClickListener(v -> previewSelectedEngine());
        forget.setEnabled(secrets.hasApiKey());
        forget.setOnClickListener(v -> forgetApiKey());
        root.addView(geminiCard);

        Button save = primaryButton("Save voice settings");
        LinearLayout.LayoutParams sv = fullButton();
        sv.topMargin = dp(12);
        root.addView(save, sv);
        save.setOnClickListener(v -> {
            if (saveSettings(true) && settings.narrationAvailable()) {
                NarrationGenerationService.enqueue(activity, book.bookId);
                status.setText("Saved. This book will prepare automatically with " + settings.engineLabel() + ".");
            }
        });

        status = text(cacheStatus(), 13, Color.rgb(84, 82, 77), false);
        status.setLineSpacing(0, 1.2f);
        status.setPadding(0, dp(16), 0, dp(8));
        root.addView(status);

        LinearLayout actionCard = card();
        actionCard.addView(heading("Book preparation", 18));
        Button prepare = primaryButton("Prepare or retry all chapters");
        prepare.setContentDescription("Prepare or retry all chapters automatically in the background.");
        LinearLayout.LayoutParams pp = fullButton();
        pp.topMargin = dp(9);
        actionCard.addView(prepare, pp);
        prepare.setOnClickListener(v -> prepareBook());

        Button play = primaryButton("Play available audio");
        LinearLayout.LayoutParams pl = fullButton();
        pl.topMargin = dp(8);
        actionCard.addView(play, pl);
        play.setOnClickListener(v -> playQueue(0, 0));

        Button follow = secondaryButton("Follow Text player");
        LinearLayout.LayoutParams fl = fullButton();
        fl.topMargin = dp(8);
        actionCard.addView(follow, fl);
        follow.setOnClickListener(v -> new FollowAlongUi(activity, book, this::show).show());

        Button clear = secondaryButton("Clear downloaded narration for this app");
        LinearLayout.LayoutParams cl = fullButton();
        cl.topMargin = dp(8);
        actionCard.addView(clear, cl);
        clear.setOnClickListener(v -> {
            cache.clear();
            progressStore.clear(book.bookId);
            NarrationGenerationService.enqueue(activity, book.bookId);
            Toast.makeText(activity, "Downloaded narration cleared. Preparation restarted.", Toast.LENGTH_LONG).show();
            show();
        });
        root.addView(actionCard);

        LinearLayout playback = card();
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(-1, -2);
        pb.topMargin = dp(10);
        playback.setLayoutParams(pb);
        playback.addView(heading("Listening controls", 18));

        ListeningProgressStore.Entry resume = resumableEntry();
        if (resume != null) {
            Button resumeButton = primaryButton("Resume chapter " + (resume.chapterIndex + 1) + " • " + ListeningProgressStore.percent(resume) + "%");
            LinearLayout.LayoutParams rb = fullButton();
            rb.topMargin = dp(8);
            playback.addView(resumeButton, rb);
            resumeButton.setOnClickListener(v -> playQueue(resume.chapterIndex, resume.positionMs));
        }

        TextView speedLabel = text("Playback speed", 13, Color.rgb(72, 71, 67), true);
        speedLabel.setPadding(0, dp(9), 0, dp(3));
        playback.addView(speedLabel);
        speed = new Spinner(activity);
        speed.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, SPEED_LABELS));
        speed.setSelection(findSpeed(settings.speed()));
        playback.addView(speed, new LinearLayout.LayoutParams(-1, -2));

        TextView sleepLabel = text("Sleep timer", 13, Color.rgb(72, 71, 67), true);
        sleepLabel.setPadding(0, dp(9), 0, dp(3));
        playback.addView(sleepLabel);
        sleep = new Spinner(activity);
        sleep.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, SLEEP_LABELS));
        sleep.setSelection(findSleep(settings.sleepMinutes()));
        playback.addView(sleep, new LinearLayout.LayoutParams(-1, -2));

        Button applyPlayback = secondaryButton("Apply speed and sleep timer");
        LinearLayout.LayoutParams ap = fullButton();
        ap.topMargin = dp(8);
        playback.addView(applyPlayback, ap);
        applyPlayback.setOnClickListener(v -> {
            savePlaybackSettings();
            sendPlaybackSettings();
            Toast.makeText(activity, "Playback settings applied", Toast.LENGTH_SHORT).show();
        });

        Button stop = secondaryButton("Stop playback");
        LinearLayout.LayoutParams stopParams = fullButton();
        stopParams.topMargin = dp(7);
        playback.addView(stop, stopParams);
        stop.setOnClickListener(v -> command(PlaybackService.ACTION_STOP));
        root.addView(playback);

        TextView chapterHeading = heading("Chapters", 18);
        chapterHeading.setPadding(0, dp(24), 0, dp(4));
        root.addView(chapterHeading);
        for (int i = 0; i < book.chapters.size(); i++) root.addView(chapterCard(i));

        activity.setContentView(scroll);
    }

    private View chapterCard(int index) {
        ChapterInput chapter = book.chapters.get(index);
        LinearLayout row = card();
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(7);
        row.setLayoutParams(rp);
        row.addView(text((index + 1) + ". " + chapter.title, 15, Color.rgb(42, 42, 39), true));
        File audio = audioFile(index);
        boolean ready = cache.isReady(audio);
        TextView state = text(ready ? "Ready offline" : "Preparing automatically", 12,
                ready ? Color.rgb(61, 99, 66) : Color.rgb(103, 101, 95), false);
        state.setPadding(0, dp(4), 0, 0);
        row.addView(state);
        if (ready) {
            Button play = secondaryButton("Play chapter " + (index + 1));
            LinearLayout.LayoutParams p = fullButton();
            p.topMargin = dp(6);
            row.addView(play, p);
            play.setOnClickListener(v -> playQueue(index, 0));
        }
        return row;
    }

    private void prepareBook() {
        if (!saveSettings(false)) return;
        if (!settings.narrationAvailable()) {
            Toast.makeText(activity, "Set up the offline Burmese voice or Gemini once", Toast.LENGTH_LONG).show();
            activity.startActivity(new Intent(activity, OfflineVoiceSetupActivity.class));
            return;
        }
        NarrationGenerationService.enqueue(activity, book.bookId);
        String message = "Preparing all chapters automatically with " + settings.engineLabel();
        status.setText(message);
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private void previewSelectedEngine() {
        if (!saveSettings(false)) return;
        if (!previewing.compareAndSet(false, true)) return;
        status.setText("Preparing voice preview…");
        new Thread(() -> {
            File preview = new File(activity.getCacheDir(), "wow-audio-selected-voice-preview.wav");
            try {
                String sample = "မင်္ဂလာပါ။ ဒီအသံက WoW Audio မြန်မာစာဖတ်အသံ စမ်းသပ်ချက် ဖြစ်ပါတယ်။";
                if (settings.useOfflineEngine()) {
                    OfflineBurmeseTtsClient.generateToWav(activity, sample, preview, null);
                } else {
                    String key = secrets.getApiKey();
                    if (key.isEmpty()) throw new Exception("Add your Gemini API key first, or install the offline Burmese voice.");
                    new GeminiTtsClient().generateToWav(key, sample, settings.voice(), settings.style(), preview, null);
                }
                activity.runOnUiThread(() -> {
                    previewing.set(false);
                    status.setText("Preview playing • " + settings.engineLabel());
                    playLocalPreview(preview);
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    previewing.set(false);
                    status.setText("Preview failed");
                    Toast.makeText(activity, friendlyError(e), Toast.LENGTH_LONG).show();
                });
            }
        }, "wow-audio-voice-preview").start();
    }

    private void playLocalPreview(File file) {
        try {
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(file.getAbsolutePath());
            player.setOnCompletionListener(mp -> {
                try { mp.release(); } catch (Exception ignored) { }
                if (file.exists()) file.delete();
                File sidecar = AudioTimingStore.sidecar(file);
                if (sidecar.exists()) sidecar.delete();
            });
            player.prepare();
            player.start();
        } catch (Exception e) {
            Toast.makeText(activity, "Preview was generated but playback could not start", Toast.LENGTH_LONG).show();
        }
    }

    private void forgetApiKey() {
        try {
            secrets.setApiKey("");
            if (NarrationSettings.ENGINE_GEMINI.equals(settings.engineMode())) settings.saveEngineMode(NarrationSettings.ENGINE_AUTO);
            Toast.makeText(activity, "Saved Gemini API key removed", Toast.LENGTH_SHORT).show();
            show();
        } catch (Exception e) {
            Toast.makeText(activity, "Unable to remove saved API key", Toast.LENGTH_LONG).show();
        }
    }

    private void useSelectedPreset() {
        int index = stylePreset.getSelectedItemPosition();
        if (index >= 0 && index < STYLE_PROMPTS.length && !STYLE_PROMPTS[index].isEmpty()) style.setText(STYLE_PROMPTS[index]);
        if (index == STYLE_PROMPTS.length - 1) {
            style.requestFocus();
            Toast.makeText(activity, "Edit the narration direction for your custom Gemini style", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean saveSettings(boolean toast) {
        try {
            int engineIndex = engine == null ? findEngine(settings.engineMode()) : Math.max(0, Math.min(ENGINE_VALUES.length - 1, engine.getSelectedItemPosition()));
            settings.saveEngineMode(ENGINE_VALUES[engineIndex]);

            String typed = apiKey == null ? "" : apiKey.getText().toString().trim();
            if (!typed.isEmpty()) secrets.setApiKey(typed);
            String selectedVoice = voice == null || voice.getSelectedItem() == null ? NarrationSettings.DEFAULT_VOICE : voice.getSelectedItem().toString();
            String direction = style == null ? NarrationSettings.DEFAULT_STYLE : style.getText().toString();
            settings.save(selectedVoice, direction);

            if (apiKey != null) {
                apiKey.setText("");
                apiKey.setHint(secrets.hasApiKey() ? "Gemini API key saved — leave blank to keep" : "Paste Gemini API key, optional");
            }
            if (NarrationSettings.ENGINE_OFFLINE.equals(settings.engineMode()) && !OfflineBurmeseTtsClient.isEspeakInstalled(activity)) {
                if (toast) Toast.makeText(activity, "Install the offline Burmese voice to use Offline mode", Toast.LENGTH_LONG).show();
            } else if (toast) {
                Toast.makeText(activity, "Voice settings saved • " + settings.engineLabel(), Toast.LENGTH_SHORT).show();
            }
            return true;
        } catch (Exception e) {
            Toast.makeText(activity, "Unable to save voice settings securely", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void playQueue(int startChapter, long startPosition) {
        savePlaybackSettings();
        if (book.chapters.isEmpty()) return;
        int start = Math.max(0, Math.min(book.chapters.size() - 1, startChapter));
        ArrayList<String> paths = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        for (int i = start; i < book.chapters.size(); i++) {
            File file = audioFile(i);
            if (!cache.isReady(file)) break;
            paths.add(file.getAbsolutePath());
            titles.add(book.chapters.get(i).title);
        }
        if (paths.isEmpty()) {
            Toast.makeText(activity, "Audio is still being prepared", Toast.LENGTH_SHORT).show();
            return;
        }
        requestNotifications();
        Intent intent = new Intent(activity, PlaybackService.class).setAction(PlaybackService.ACTION_PLAY_QUEUE);
        intent.putStringArrayListExtra(PlaybackService.EXTRA_PATHS, paths);
        intent.putStringArrayListExtra(PlaybackService.EXTRA_TITLES, titles);
        intent.putExtra(PlaybackService.EXTRA_BOOK_ID, book.bookId);
        intent.putExtra(PlaybackService.EXTRA_BOOK_TITLE, book.title);
        intent.putExtra(PlaybackService.EXTRA_AUTHOR, book.author);
        intent.putExtra(PlaybackService.EXTRA_START_CHAPTER, start);
        intent.putExtra(PlaybackService.EXTRA_START_POSITION, Math.max(0, startPosition));
        intent.putExtra(PlaybackService.EXTRA_SPEED, settings.speed());
        intent.putExtra(PlaybackService.EXTRA_MINUTES, settings.sleepMinutes());
        if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent); else activity.startService(intent);
    }

    private ListeningProgressStore.Entry resumableEntry() {
        ListeningProgressStore.Entry entry = progressStore.load(book.bookId);
        if (entry == null || entry.chapterIndex < 0 || entry.chapterIndex >= book.chapters.size()) return null;
        File current = audioFile(entry.chapterIndex);
        if (!cache.isReady(current) || entry.audioPath == null || !current.getAbsolutePath().equals(entry.audioPath)) return null;
        if (entry.durationMs > 0 && entry.positionMs >= entry.durationMs - 1000) {
            int next = entry.chapterIndex + 1;
            if (next >= book.chapters.size() || !cache.isReady(audioFile(next))) return null;
            return new ListeningProgressStore.Entry(book.bookId, book.title, book.author, book.chapters.get(next).title,
                    next, 0, 0, audioFile(next).getAbsolutePath(), System.currentTimeMillis());
        }
        return entry;
    }

    private void savePlaybackSettings() {
        int speedIndex = speed == null ? findSpeed(settings.speed()) : Math.max(0, Math.min(SPEED_VALUES.length - 1, speed.getSelectedItemPosition()));
        int sleepIndex = sleep == null ? findSleep(settings.sleepMinutes()) : Math.max(0, Math.min(SLEEP_VALUES.length - 1, sleep.getSelectedItemPosition()));
        settings.savePlayback(SPEED_VALUES[speedIndex], SLEEP_VALUES[sleepIndex]);
    }

    private void sendPlaybackSettings() {
        Intent speedIntent = new Intent(activity, PlaybackService.class).setAction(PlaybackService.ACTION_SET_SPEED);
        speedIntent.putExtra(PlaybackService.EXTRA_SPEED, settings.speed());
        try { activity.startService(speedIntent); } catch (Exception ignored) { }
        Intent timerIntent = new Intent(activity, PlaybackService.class).setAction(PlaybackService.ACTION_SLEEP_TIMER);
        timerIntent.putExtra(PlaybackService.EXTRA_MINUTES, settings.sleepMinutes());
        try { activity.startService(timerIntent); } catch (Exception ignored) { }
    }

    private void command(String action) {
        try { activity.startService(new Intent(activity, PlaybackService.class).setAction(action)); } catch (Exception ignored) { }
    }

    private File audioFile(int index) {
        try {
            ChapterInput chapter = book.chapters.get(index);
            return cache.fileFor(book.bookId, index, chapter.text, settings.voice(), settings.style());
        } catch (Exception e) {
            return null;
        }
    }

    private String cacheStatus() {
        int ready = 0;
        int follow = 0;
        for (int i = 0; i < book.chapters.size(); i++) {
            File audio = audioFile(i);
            if (cache.isReady(audio)) ready++;
            if (cache.hasFollowData(audio)) follow++;
        }
        return settings.engineLabel() + " • " + ready + " of " + book.chapters.size() + " chapters ready • " + follow + " Follow Text ready • " + humanBytes(cache.totalBytes());
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4102);
        }
    }

    private int findEngine(String mode) {
        for (int i = 0; i < ENGINE_VALUES.length; i++) if (ENGINE_VALUES[i].equals(mode)) return i;
        return 0;
    }

    private int findVoice(String selected) {
        for (int i = 0; i < VOICES.length; i++) if (VOICES[i].equalsIgnoreCase(selected)) return i;
        for (int i = 0; i < VOICES.length; i++) if (VOICES[i].equalsIgnoreCase(NarrationSettings.DEFAULT_VOICE)) return i;
        return 0;
    }

    private int findStylePreset(String selected) {
        if (selected != null) for (int i = 0; i < STYLE_PROMPTS.length - 1; i++) if (selected.trim().equals(STYLE_PROMPTS[i])) return i;
        return STYLE_LABELS.length - 1;
    }

    private int findSpeed(float selected) {
        int best = 0;
        float delta = Float.MAX_VALUE;
        for (int i = 0; i < SPEED_VALUES.length; i++) {
            float d = Math.abs(SPEED_VALUES[i] - selected);
            if (d < delta) {
                delta = d;
                best = i;
            }
        }
        return best;
    }

    private int findSleep(int minutes) {
        for (int i = 0; i < SLEEP_VALUES.length; i++) if (SLEEP_VALUES[i] == minutes) return i;
        return 0;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.rgb(247, 244, 237));
        return layout;
    }

    private LinearLayout transparentColumn() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(255, 253, 249));
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.rgb(226, 219, 206));
        layout.setBackground(background);
        return layout;
    }

    private Button primaryButton(String value) {
        Button button = new Button(activity);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setMinHeight(dp(56));
        button.setContentDescription(value);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(48, 48, 43));
        background.setCornerRadius(dp(16));
        button.setBackground(background);
        return button;
    }

    private Button secondaryButton(String value) {
        Button button = new Button(activity);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(Color.rgb(48, 48, 43));
        button.setMinHeight(dp(52));
        button.setContentDescription(value);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(239, 234, 224));
        background.setCornerRadius(dp(15));
        button.setBackground(background);
        return button;
    }

    private LinearLayout.LayoutParams fullButton() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private TextView heading(String value, float size) {
        TextView view = text(value, size, Color.rgb(24, 27, 29), true);
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        return view;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Bitmap bitmap(byte[] data) {
        try { return data == null ? null : BitmapFactory.decodeByteArray(data, 0, data.length); }
        catch (Exception e) { return null; }
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(0, bytes / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static String friendlyError(Exception e) {
        String raw = e == null || e.getMessage() == null ? "" : e.getMessage();
        String value = raw.toLowerCase(Locale.US);
        if (value.contains("401") || value.contains("403") || value.contains("invalid") && value.contains("key"))
            return "Gemini API key is invalid or not allowed. You can also use the free offline Burmese voice.";
        if (value.contains("429") || value.contains("quota") || value.contains("rate limit"))
            return "Gemini quota or rate limit was reached. Offline Burmese voice does not use Gemini quota.";
        if (value.contains("espeak") || value.contains("offline burmese"))
            return raw.isEmpty() ? "Offline Burmese voice needs attention" : raw;
        return raw.trim().isEmpty() ? "Voice preview failed" : raw;
    }

    static final class BookInput {
        final String bookId;
        final String title;
        final String author;
        final byte[] cover;
        final List<ChapterInput> chapters;

        BookInput(String bookId, String title, String author, byte[] cover, List<ChapterInput> chapters) {
            this.bookId = bookId;
            this.title = title;
            this.author = author;
            this.cover = cover;
            this.chapters = chapters == null ? new ArrayList<>() : chapters;
        }

        BookInput(String bookId, String title, String author, List<ChapterInput> chapters) {
            this(bookId, title, author, null, chapters);
        }
    }

    static final class ChapterInput {
        final String title;
        final String text;

        ChapterInput(String title, String text) {
            this.title = title == null ? "Chapter" : title;
            this.text = text == null ? "" : text;
        }
    }
}
