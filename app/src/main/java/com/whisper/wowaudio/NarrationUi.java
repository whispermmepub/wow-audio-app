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
    private final GeminiTtsClient client = new GeminiTtsClient();
    private final AtomicBoolean generating = new AtomicBoolean(false);
    private EditText apiKey;
    private EditText style;
    private Spinner voice;
    private Spinner stylePreset;
    private Spinner speed;
    private Spinner sleep;
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
        root.setPadding(dp(22), dp(20), dp(22), dp(42));
        scroll.addView(root);

        TextView back = text("‹  Book", 15, Color.rgb(86, 78, 62), true);
        back.setPadding(0, dp(8), 0, dp(18));
        back.setOnClickListener(v -> onBack.run());
        root.addView(back);

        LinearLayout hero = new LinearLayout(activity);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        ImageView cover = new ImageView(activity);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap coverBitmap = bitmap(book.cover);
        if (coverBitmap != null) cover.setImageBitmap(coverBitmap); else cover.setBackgroundColor(Color.rgb(224, 216, 201));
        hero.addView(cover, new LinearLayout.LayoutParams(dp(78), dp(114)));
        LinearLayout heroText = columnTransparent();
        heroText.setPadding(dp(16), 0, 0, 0);
        heroText.addView(text(book.title, 22, Color.rgb(24, 27, 29), true));
        TextView heroAuthor = text(book.author, 13, Color.rgb(103, 101, 95), false);
        heroAuthor.setPadding(0, dp(5), 0, dp(8));
        heroText.addView(heroAuthor);
        heroText.addView(text("Gemini 3.1 Flash TTS • private BYOK", 11, Color.rgb(119, 116, 108), false));
        hero.addView(heroText, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(hero);

        LinearLayout setup = card();
        LinearLayout.LayoutParams setupParams = new LinearLayout.LayoutParams(-1, -2); setupParams.topMargin = dp(18); setup.setLayoutParams(setupParams);
        setup.addView(text("Narration setup", 17, Color.rgb(35, 36, 36), true));
        TextView privacy = text("Your Gemini key stays encrypted on this device and is excluded from app backup.", 11, Color.rgb(112, 109, 103), false);
        privacy.setPadding(0, dp(4), 0, dp(12));
        setup.addView(privacy);

        apiKey = new EditText(activity);
        apiKey.setSingleLine(true);
        apiKey.setTextSize(14);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setHint(secrets.hasApiKey() ? "API key saved securely — leave blank to keep" : "Paste Gemini API key");
        setup.addView(apiKey, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout keyActions = new LinearLayout(activity);
        keyActions.setOrientation(LinearLayout.HORIZONTAL);
        Button test = secondaryButton("Test + preview voice");
        Button forget = secondaryButton("Forget API key");
        keyActions.addView(test, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams forgetParams = new LinearLayout.LayoutParams(0, -2, 1); forgetParams.leftMargin = dp(7);
        keyActions.addView(forget, forgetParams);
        LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(-1, -2); keyParams.topMargin = dp(7);
        setup.addView(keyActions, keyParams);
        forget.setEnabled(secrets.hasApiKey());
        forget.setOnClickListener(v -> forgetApiKey());
        test.setOnClickListener(v -> testAndPreview());

        TextView vlabel = text("Voice", 14, Color.rgb(35, 36, 36), true);
        vlabel.setPadding(0, dp(14), 0, dp(4));
        setup.addView(vlabel);
        voice = new Spinner(activity);
        voice.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, VOICES));
        voice.setSelection(findVoice(settings.voice()));
        setup.addView(voice, new LinearLayout.LayoutParams(-1, -2));

        TextView presetLabel = text("Style preset", 14, Color.rgb(35, 36, 36), true);
        presetLabel.setPadding(0, dp(14), 0, dp(4));
        setup.addView(presetLabel);
        stylePreset = new Spinner(activity);
        stylePreset.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, STYLE_LABELS));
        stylePreset.setSelection(findStylePreset(settings.style()));
        setup.addView(stylePreset, new LinearLayout.LayoutParams(-1, -2));

        Button usePreset = secondaryButton("Use selected style");
        LinearLayout.LayoutParams upp = new LinearLayout.LayoutParams(-1, -2); upp.topMargin = dp(6);
        setup.addView(usePreset, upp);

        TextView slabel = text("Narration direction", 14, Color.rgb(35, 36, 36), true);
        slabel.setPadding(0, dp(14), 0, dp(4));
        setup.addView(slabel);
        style = new EditText(activity);
        style.setText(settings.style());
        style.setTextSize(13);
        style.setMinLines(3);
        style.setGravity(Gravity.TOP | Gravity.START);
        setup.addView(style, new LinearLayout.LayoutParams(-1, -2));
        usePreset.setOnClickListener(v -> useSelectedPreset());

        Button save = button("Save narration settings");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = dp(14);
        setup.addView(save, sp);
        save.setOnClickListener(v -> saveSettings(true));
        root.addView(setup);

        LinearLayout playback = card();
        LinearLayout.LayoutParams playCard = new LinearLayout.LayoutParams(-1, -2); playCard.topMargin = dp(12); playback.setLayoutParams(playCard);
        playback.addView(text("Listening", 17, Color.rgb(35, 36, 36), true));

        ListeningProgressStore.Entry resume = resumableEntry();
        if (resume != null) {
            int pct = ListeningProgressStore.percent(resume);
            Button resumeButton = button("▶  Resume • Chapter " + (resume.chapterIndex + 1) + " • " + pct + "%");
            LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(-1, -2); rbp.topMargin = dp(10);
            playback.addView(resumeButton, rbp);
            resumeButton.setOnClickListener(v -> playQueue(resume.chapterIndex, resume.positionMs));
        }

        TextView speedLabel = text("Speed", 13, Color.rgb(92, 90, 85), false);
        speedLabel.setPadding(0, dp(10), 0, dp(3));
        playback.addView(speedLabel);
        speed = new Spinner(activity);
        speed.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, SPEED_LABELS));
        speed.setSelection(findSpeed(settings.speed()));
        playback.addView(speed, new LinearLayout.LayoutParams(-1, -2));

        TextView sleepLabel = text("Sleep timer", 13, Color.rgb(92, 90, 85), false);
        sleepLabel.setPadding(0, dp(10), 0, dp(3));
        playback.addView(sleepLabel);
        sleep = new Spinner(activity);
        sleep.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, SLEEP_LABELS));
        sleep.setSelection(findSleep(settings.sleepMinutes()));
        playback.addView(sleep, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout listeningActions = new LinearLayout(activity);
        Button applyPlayback = secondaryButton("Apply speed / timer");
        Button follow = button("Follow Text");
        listeningActions.addView(applyPlayback, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams followParams = new LinearLayout.LayoutParams(0, -2, 1); followParams.leftMargin = dp(7);
        listeningActions.addView(follow, followParams);
        LinearLayout.LayoutParams lap = new LinearLayout.LayoutParams(-1, -2); lap.topMargin = dp(12);
        playback.addView(listeningActions, lap);
        applyPlayback.setOnClickListener(v -> {
            savePlaybackSettings();
            sendPlaybackSettings();
            Toast.makeText(activity, "Playback settings applied", Toast.LENGTH_SHORT).show();
        });
        follow.setOnClickListener(v -> new FollowAlongUi(activity, book, this::show).show());

        Button stopPlayback = secondaryButton("Stop playback");
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2); stp.topMargin = dp(7);
        playback.addView(stopPlayback, stp);
        stopPlayback.setOnClickListener(v -> command(PlaybackService.ACTION_STOP));
        root.addView(playback);

        status = text(cacheStatus(), 12, Color.rgb(112, 109, 103), false);
        status.setPadding(0, dp(18), 0, dp(10));
        root.addView(status);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button all = button("Generate whole book");
        actions.addView(all, new LinearLayout.LayoutParams(0, -2, 1));
        Button play = button("Play offline");
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, -2, 1); pp.leftMargin = dp(8);
        actions.addView(play, pp);
        root.addView(actions);
        all.setOnClickListener(v -> generateAll());
        play.setOnClickListener(v -> playQueue(0, 0));

        Button clear = secondaryButton("Clear offline audio cache");
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.topMargin = dp(8);
        root.addView(clear, cp);
        clear.setOnClickListener(v -> {
            cache.clear();
            progressStore.clear(book.bookId);
            Toast.makeText(activity, "Offline audio cleared", Toast.LENGTH_SHORT).show();
            show();
        });

        TextView ch = text("Chapters", 18, Color.rgb(24, 27, 29), true);
        ch.setPadding(0, dp(26), 0, dp(7));
        root.addView(ch);
        for (int i = 0; i < book.chapters.size(); i++) root.addView(chapterCard(i));
        activity.setContentView(scroll);
    }

    private View chapterCard(int index) {
        ChapterInput chapter = book.chapters.get(index);
        LinearLayout row = card();
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.topMargin = dp(9); row.setLayoutParams(rp);
        row.addView(text((index + 1) + "  " + chapter.title, 15, Color.rgb(35, 36, 36), true));
        File audio = audioFile(index);
        boolean ready = cache.isReady(audio);
        boolean followReady = cache.hasFollowData(audio);
        String label = ready ? (followReady ? "Offline audio + Follow Text ready" : "Offline audio ready") : "Not generated";
        TextView state = text(label, 12, ready ? Color.rgb(69, 106, 74) : Color.rgb(126, 122, 113), false);
        state.setPadding(0, dp(5), 0, dp(8));
        row.addView(state);
        LinearLayout actions = new LinearLayout(activity);
        Button primary = button(ready ? "▶  Play" : "Generate");
        actions.addView(primary, new LinearLayout.LayoutParams(0, -2, 1));
        if (ready) {
            Button follow = secondaryButton("Follow");
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, -2, 1); fp.leftMargin = dp(7);
            actions.addView(follow, fp);
            follow.setOnClickListener(v -> {
                playQueue(index, 0);
                new FollowAlongUi(activity, book, this::show).show();
            });
            primary.setOnClickListener(v -> playQueue(index, 0));
        } else primary.setOnClickListener(v -> generateChapter(index, true));
        row.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void testAndPreview() {
        if (!saveSettings(false)) return;
        String key = secrets.getApiKey();
        if (key.isEmpty()) { Toast.makeText(activity, "Add your Gemini API key first", Toast.LENGTH_LONG).show(); return; }
        if (!generating.compareAndSet(false, true)) { Toast.makeText(activity, "Narration generation is already running", Toast.LENGTH_SHORT).show(); return; }
        status.setText("Testing Gemini and generating voice preview…");
        new Thread(() -> {
            File preview = new File(activity.getCacheDir(), "wow-audio-voice-preview.wav");
            try {
                client.generateToWav(key,
                        "မင်္ဂလာပါ။ ဒီအသံက WoW Audio အတွက် စမ်းသပ်နားထောင်နိုင်တဲ့ အသံနမူနာ ဖြစ်ပါတယ်။ Welcome to WoW Audio.",
                        settings.voice(), settings.style(), preview, null);
                activity.runOnUiThread(() -> {
                    generating.set(false);
                    status.setText("API connection works • preview playing");
                    playLocalPreview(preview);
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    generating.set(false);
                    status.setText("API test failed");
                    Toast.makeText(activity, friendlyError(e), Toast.LENGTH_LONG).show();
                });
            }
        }, "wow-audio-tts-preview").start();
    }

    private void playLocalPreview(File file) {
        try {
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(file.getAbsolutePath());
            player.setOnCompletionListener(mp -> { try { mp.release(); } catch (Exception ignored) { } });
            player.prepare();
            player.start();
        } catch (Exception e) {
            Toast.makeText(activity, "Preview generated, but playback could not start", Toast.LENGTH_LONG).show();
        }
    }

    private void forgetApiKey() {
        try {
            secrets.setApiKey("");
            Toast.makeText(activity, "Saved API key removed", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(activity, "Edit the narration direction for your custom style", Toast.LENGTH_SHORT).show();
        }
    }

    private void generateChapter(int index, boolean autoPlay) {
        if (!saveSettings(false)) return;
        String key = secrets.getApiKey();
        if (key.isEmpty()) { Toast.makeText(activity, "Add your Gemini API key first", Toast.LENGTH_LONG).show(); return; }
        if (!generating.compareAndSet(false, true)) { Toast.makeText(activity, "Narration generation is already running", Toast.LENGTH_SHORT).show(); return; }
        ChapterInput chapter = book.chapters.get(index);
        File target = audioFile(index);
        status.setText("Generating chapter " + (index + 1) + "…");
        new Thread(() -> {
            try {
                if (!cache.isReady(target) || !cache.hasFollowData(target)) client.generateToWav(key, chapter.text, settings.voice(), settings.style(), target,
                        (done, total) -> activity.runOnUiThread(() -> status.setText("Chapter " + (index + 1) + " • part " + done + "/" + total)));
                activity.runOnUiThread(() -> {
                    generating.set(false);
                    Toast.makeText(activity, "Chapter saved offline", Toast.LENGTH_SHORT).show();
                    if (autoPlay) playQueue(index, 0); else show();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    generating.set(false);
                    status.setText("Generation failed");
                    Toast.makeText(activity, friendlyError(e), Toast.LENGTH_LONG).show();
                });
            }
        }, "wow-audio-tts-chapter").start();
    }

    private void generateAll() {
        if (!saveSettings(false)) return;
        String key = secrets.getApiKey();
        if (key.isEmpty()) { Toast.makeText(activity, "Add your Gemini API key first", Toast.LENGTH_LONG).show(); return; }
        if (book.chapters.isEmpty()) return;
        if (!generating.compareAndSet(false, true)) { Toast.makeText(activity, "Narration generation is already running", Toast.LENGTH_SHORT).show(); return; }
        new Thread(() -> {
            try {
                for (int i = 0; i < book.chapters.size(); i++) {
                    final int chapterIndex = i;
                    ChapterInput chapter = book.chapters.get(i);
                    File target = audioFile(i);
                    activity.runOnUiThread(() -> status.setText("Generating chapter " + (chapterIndex + 1) + "/" + book.chapters.size()));
                    if (!cache.isReady(target) || !cache.hasFollowData(target)) client.generateToWav(key, chapter.text, settings.voice(), settings.style(), target,
                            (done, total) -> activity.runOnUiThread(() -> status.setText("Chapter " + (chapterIndex + 1) + "/" + book.chapters.size() + " • part " + done + "/" + total)));
                }
                activity.runOnUiThread(() -> {
                    generating.set(false);
                    Toast.makeText(activity, "Whole book is ready offline", Toast.LENGTH_LONG).show();
                    show();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    generating.set(false);
                    status.setText("Generation stopped");
                    Toast.makeText(activity, friendlyError(e), Toast.LENGTH_LONG).show();
                });
            }
        }, "wow-audio-tts-book").start();
    }

    private void playQueue(int startChapter, long startPosition) {
        savePlaybackSettings();
        int start = Math.max(0, Math.min(book.chapters.size() - 1, startChapter));
        ArrayList<String> paths = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        for (int i = start; i < book.chapters.size(); i++) {
            File f = audioFile(i);
            if (!cache.isReady(f)) break;
            paths.add(f.getAbsolutePath());
            titles.add(book.chapters.get(i).title);
        }
        if (paths.isEmpty()) { Toast.makeText(activity, "Generate this chapter first", Toast.LENGTH_SHORT).show(); return; }
        requestNotifications();
        Intent i = new Intent(activity, PlaybackService.class).setAction(PlaybackService.ACTION_PLAY_QUEUE);
        i.putStringArrayListExtra(PlaybackService.EXTRA_PATHS, paths);
        i.putStringArrayListExtra(PlaybackService.EXTRA_TITLES, titles);
        i.putExtra(PlaybackService.EXTRA_BOOK_ID, book.bookId);
        i.putExtra(PlaybackService.EXTRA_BOOK_TITLE, book.title);
        i.putExtra(PlaybackService.EXTRA_AUTHOR, book.author);
        i.putExtra(PlaybackService.EXTRA_START_CHAPTER, start);
        i.putExtra(PlaybackService.EXTRA_START_POSITION, Math.max(0, startPosition));
        i.putExtra(PlaybackService.EXTRA_SPEED, settings.speed());
        i.putExtra(PlaybackService.EXTRA_MINUTES, settings.sleepMinutes());
        if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(i); else activity.startService(i);
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

    private boolean saveSettings(boolean toast) {
        try {
            String typed = apiKey == null ? "" : apiKey.getText().toString().trim();
            if (!typed.isEmpty()) secrets.setApiKey(typed);
            String selected = voice == null || voice.getSelectedItem() == null ? NarrationSettings.DEFAULT_VOICE : voice.getSelectedItem().toString();
            String direction = style == null ? NarrationSettings.DEFAULT_STYLE : style.getText().toString();
            settings.save(selected, direction);
            if (apiKey != null) { apiKey.setText(""); apiKey.setHint(secrets.hasApiKey() ? "API key saved securely — leave blank to keep" : "Paste Gemini API key"); }
            if (toast) Toast.makeText(activity, "Narration settings saved", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            Toast.makeText(activity, "Unable to save API key securely", Toast.LENGTH_LONG).show();
            return false;
        }
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
            ChapterInput c = book.chapters.get(index);
            return cache.fileFor(book.bookId, index, c.text, settings.voice(), settings.style());
        } catch (Exception e) { return null; }
    }

    private String cacheStatus() {
        long bytes = cache.totalBytes();
        int ready = 0, followReady = 0;
        for (int i = 0; i < book.chapters.size(); i++) {
            File audio = audioFile(i);
            if (cache.isReady(audio)) ready++;
            if (cache.hasFollowData(audio)) followReady++;
        }
        return ready + "/" + book.chapters.size() + " chapters offline • " + followReady + " Follow Text ready • " + humanBytes(bytes);
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4102);
        }
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
        int best = 0; float delta = Float.MAX_VALUE;
        for (int i = 0; i < SPEED_VALUES.length; i++) {
            float d = Math.abs(SPEED_VALUES[i] - selected);
            if (d < delta) { delta = d; best = i; }
        }
        return best;
    }

    private int findSleep(int minutes) {
        for (int i = 0; i < SLEEP_VALUES.length; i++) if (SLEEP_VALUES[i] == minutes) return i;
        return 0;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); v.setBackgroundColor(Color.rgb(247, 244, 237)); return v;
    }

    private LinearLayout columnTransparent() {
        LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v;
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(17), dp(17), dp(17), dp(17));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(255, 253, 249)); bg.setCornerRadius(dp(22)); bg.setStroke(dp(1), Color.rgb(226, 219, 206));
        v.setBackground(bg); v.setElevation(dp(1)); return v;
    }

    private Button button(String value) {
        Button b = new Button(activity); b.setText(value); b.setAllCaps(false); b.setTextSize(13); b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(48, 48, 43)); bg.setCornerRadius(dp(18)); b.setBackground(bg); return b;
    }

    private Button secondaryButton(String value) {
        Button b = new Button(activity); b.setText(value); b.setAllCaps(false); b.setTextSize(12); b.setTextColor(Color.rgb(48, 48, 43));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(239, 234, 224)); bg.setCornerRadius(dp(18)); b.setBackground(bg); return b;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(activity); v.setText(value); v.setTextSize(size); v.setTextColor(color); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }

    private Bitmap bitmap(byte[] data) { try { return data == null ? null : BitmapFactory.decodeByteArray(data, 0, data.length); } catch (Exception e) { return null; } }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static String humanBytes(long bytes) { if (bytes < 1024 * 1024) return Math.max(0, bytes / 1024) + " KB"; return String.format(Locale.US, "%.1f MB", bytes / 1048576.0); }

    private static String friendlyError(Exception e) {
        String raw = e == null || e.getMessage() == null ? "" : e.getMessage();
        String value = raw.toLowerCase(Locale.US);
        if (value.contains("401") || value.contains("403") || value.contains("api key") && (value.contains("invalid") || value.contains("denied")))
            return "Gemini API key is invalid or not allowed for this request. Check the key and try Test + preview again.";
        if (value.contains("429") || value.contains("quota") || value.contains("resource_exhausted"))
            return "Gemini quota or rate limit was reached. Wait a little or check the Gemini project quota, then retry.";
        if (value.contains("timeout") || value.contains("timed out") || value.contains("unable to resolve") || value.contains("network") || value.contains("connection"))
            return "Network connection to Gemini failed. Check internet access and try again.";
        return raw.trim().isEmpty() ? "Narration generation failed" : raw;
    }

    static final class BookInput {
        final String bookId, title, author;
        final byte[] cover;
        final List<ChapterInput> chapters;
        BookInput(String bookId, String title, String author, byte[] cover, List<ChapterInput> chapters) {
            this.bookId = bookId; this.title = title; this.author = author; this.cover = cover; this.chapters = chapters;
        }
    }

    static final class ChapterInput {
        final String title, text;
        ChapterInput(String title, String text) { this.title = title; this.text = text; }
    }
}
