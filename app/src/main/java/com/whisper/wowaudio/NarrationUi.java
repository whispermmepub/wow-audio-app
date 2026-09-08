package com.whisper.wowaudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class NarrationUi {
    private static final String[] VOICES = {
            "Achernar", "Kore", "Aoede", "Gacrux", "Sulafat", "Iapetus", "Schedar", "Charon",
            "Zephyr", "Puck", "Leda", "Orus", "Callirrhoe", "Autonoe", "Despina", "Erinome",
            "Algieba", "Rasalgethi", "Umbriel", "Algenib", "Alnilam", "Achird", "Vindemiatrix"
    };

    private final Activity activity;
    private final BookInput book;
    private final Runnable onBack;
    private final SecretStore secrets;
    private final NarrationSettings settings;
    private final AudioCache cache;
    private final GeminiTtsClient client = new GeminiTtsClient();
    private final AtomicBoolean generating = new AtomicBoolean(false);
    private EditText apiKey;
    private EditText style;
    private Spinner voice;
    private TextView status;

    NarrationUi(Activity activity, BookInput book, Runnable onBack) {
        this.activity = activity;
        this.book = book;
        this.onBack = onBack;
        secrets = new SecretStore(activity);
        settings = new NarrationSettings(activity);
        cache = new AudioCache(activity);
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
        root.addView(text("Narration", 29, Color.rgb(24, 27, 29), true));
        TextView model = text("Gemini 3.1 Flash TTS • bring your own API key", 12, Color.rgb(112, 109, 103), false);
        model.setPadding(0, dp(4), 0, dp(22));
        root.addView(model);

        LinearLayout setup = card();
        setup.addView(text("Private API key", 14, Color.rgb(35, 36, 36), true));
        apiKey = new EditText(activity);
        apiKey.setSingleLine(true);
        apiKey.setTextSize(14);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setHint(secrets.hasApiKey() ? "Saved securely — leave blank to keep" : "Paste Gemini API key");
        setup.addView(apiKey, new LinearLayout.LayoutParams(-1, -2));

        TextView vlabel = text("Voice", 14, Color.rgb(35, 36, 36), true);
        vlabel.setPadding(0, dp(14), 0, dp(4));
        setup.addView(vlabel);
        voice = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, VOICES);
        voice.setAdapter(adapter);
        voice.setSelection(findVoice(settings.voice()));
        setup.addView(voice, new LinearLayout.LayoutParams(-1, -2));

        TextView slabel = text("Narration direction", 14, Color.rgb(35, 36, 36), true);
        slabel.setPadding(0, dp(14), 0, dp(4));
        setup.addView(slabel);
        style = new EditText(activity);
        style.setText(settings.style());
        style.setTextSize(13);
        style.setMinLines(3);
        style.setGravity(Gravity.TOP | Gravity.START);
        setup.addView(style, new LinearLayout.LayoutParams(-1, -2));

        Button save = button("Save narration settings");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = dp(14);
        setup.addView(save, sp);
        save.setOnClickListener(v -> saveSettings(true));
        root.addView(setup);

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
        play.setOnClickListener(v -> playQueue(0));

        Button clear = secondaryButton("Clear offline audio cache");
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.topMargin = dp(8);
        root.addView(clear, cp);
        clear.setOnClickListener(v -> { cache.clear(); Toast.makeText(activity, "Offline audio cleared", Toast.LENGTH_SHORT).show(); show(); });

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
        TextView state = text(ready ? "Offline audio ready" : "Not generated", 12, ready ? Color.rgb(69, 106, 74) : Color.rgb(126, 122, 113), false);
        state.setPadding(0, dp(5), 0, dp(8));
        row.addView(state);
        Button action = button(ready ? "▶  Play" : "Generate chapter");
        row.addView(action, new LinearLayout.LayoutParams(-1, -2));
        if (ready) action.setOnClickListener(v -> playQueue(index));
        else action.setOnClickListener(v -> generateChapter(index, true));
        return row;
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
                if (!cache.isReady(target)) client.generateToWav(key, chapter.text, settings.voice(), settings.style(), target,
                        (done, total) -> activity.runOnUiThread(() -> status.setText("Chapter " + (index + 1) + " • part " + done + "/" + total)));
                activity.runOnUiThread(() -> {
                    generating.set(false);
                    Toast.makeText(activity, "Chapter saved offline", Toast.LENGTH_SHORT).show();
                    if (autoPlay) playQueue(index); else show();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    generating.set(false);
                    status.setText("Generation failed");
                    Toast.makeText(activity, cleanError(e), Toast.LENGTH_LONG).show();
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
                    if (!cache.isReady(target)) client.generateToWav(key, chapter.text, settings.voice(), settings.style(), target, null);
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
                    Toast.makeText(activity, cleanError(e), Toast.LENGTH_LONG).show();
                });
            }
        }, "wow-audio-tts-book").start();
    }

    private void playQueue(int startChapter) {
        ArrayList<String> paths = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        for (int i = startChapter; i < book.chapters.size(); i++) {
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
        i.putExtra(PlaybackService.EXTRA_AUTHOR, book.author);
        if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(i); else activity.startService(i);
    }

    private boolean saveSettings(boolean toast) {
        try {
            String typed = apiKey == null ? "" : apiKey.getText().toString().trim();
            if (!typed.isEmpty()) secrets.setApiKey(typed);
            String selected = voice == null || voice.getSelectedItem() == null ? NarrationSettings.DEFAULT_VOICE : voice.getSelectedItem().toString();
            String direction = style == null ? NarrationSettings.DEFAULT_STYLE : style.getText().toString();
            settings.save(selected, direction);
            if (apiKey != null) { apiKey.setText(""); apiKey.setHint(secrets.hasApiKey() ? "Saved securely — leave blank to keep" : "Paste Gemini API key"); }
            if (toast) Toast.makeText(activity, "Narration settings saved", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            Toast.makeText(activity, "Unable to save API key securely", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private File audioFile(int index) {
        try {
            ChapterInput c = book.chapters.get(index);
            return cache.fileFor(book.bookId, index, c.text, settings.voice(), settings.style());
        } catch (Exception e) { return null; }
    }

    private String cacheStatus() {
        long bytes = cache.totalBytes();
        int ready = 0;
        for (int i = 0; i < book.chapters.size(); i++) if (cache.isReady(audioFile(i))) ready++;
        return ready + "/" + book.chapters.size() + " chapters offline • " + humanBytes(bytes);
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4102);
        }
    }

    private int findVoice(String selected) {
        for (int i = 0; i < VOICES.length; i++) if (VOICES[i].equalsIgnoreCase(selected)) return i;
        return 0;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); v.setBackgroundColor(Color.rgb(247, 244, 237)); return v;
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
        Button b = new Button(activity); b.setText(value); b.setAllCaps(false); b.setTextSize(13); b.setTextColor(Color.rgb(48, 48, 43));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(239, 234, 224)); bg.setCornerRadius(dp(18)); b.setBackground(bg); return b;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(activity); v.setText(value); v.setTextSize(size); v.setTextColor(color); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static String humanBytes(long bytes) { if (bytes < 1024 * 1024) return Math.max(0, bytes / 1024) + " KB"; return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0); }
    private static String cleanError(Exception e) { String m = e == null ? null : e.getMessage(); return m == null || m.trim().isEmpty() ? "Narration generation failed" : m; }

    static final class BookInput {
        final String bookId, title, author;
        final List<ChapterInput> chapters;
        BookInput(String bookId, String title, String author, List<ChapterInput> chapters) {
            this.bookId = bookId; this.title = title; this.author = author; this.chapters = chapters;
        }
    }

    static final class ChapterInput {
        final String title, text;
        ChapterInput(String title, String text) { this.title = title; this.text = text; }
    }
}
