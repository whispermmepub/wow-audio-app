package com.whisper.wowaudio;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

final class FollowAlongUi {
    private final Activity activity;
    private final NarrationUi.BookInput book;
    private final Runnable onBack;
    private final AudioCache cache;
    private final ListeningProgressStore progress;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView chapterTitle;
    private TextView previousText;
    private TextView currentText;
    private TextView nextText;
    private TextView progressLabel;
    private Button playPause;
    private ProgressBar progressBar;
    private int shownSegment = -1;
    private String shownPath = "";

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (activity.isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
            refresh();
            handler.postDelayed(this, 700L);
        }
    };

    FollowAlongUi(Activity activity, NarrationUi.BookInput book, Runnable onBack) {
        this.activity = activity;
        this.book = book;
        this.onBack = onBack;
        this.cache = new AudioCache(activity);
        this.progress = new ListeningProgressStore(activity);
    }

    void show() {
        handler.removeCallbacks(ticker);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = column();
        root.setPadding(dp(22), dp(18), dp(22), dp(36));
        scroll.addView(root);

        TextView back = text("‹  Narration", 15, Color.rgb(86, 78, 62), true);
        back.setPadding(0, dp(8), 0, dp(18));
        back.setOnClickListener(v -> { handler.removeCallbacks(ticker); onBack.run(); });
        root.addView(back);

        ImageView cover = new ImageView(activity);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bm = bitmap(book.cover);
        if (bm != null) cover.setImageBitmap(bm); else cover.setBackgroundColor(Color.rgb(224, 216, 201));
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(dp(150), dp(218));
        coverParams.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(cover, coverParams);

        TextView title = text(book.title, 23, Color.rgb(24, 27, 29), true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(17), 0, dp(4));
        root.addView(title);
        TextView author = text(book.author, 13, Color.rgb(103, 101, 95), false);
        author.setGravity(Gravity.CENTER);
        root.addView(author);

        chapterTitle = text("Ready to listen", 16, Color.rgb(48, 48, 43), true);
        chapterTitle.setGravity(Gravity.CENTER);
        chapterTitle.setPadding(0, dp(18), 0, dp(8));
        root.addView(chapterTitle);

        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        root.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(5)));
        progressLabel = text("0:00", 11, Color.rgb(119, 116, 108), false);
        progressLabel.setGravity(Gravity.CENTER);
        progressLabel.setPadding(0, dp(5), 0, dp(16));
        root.addView(progressLabel);

        LinearLayout follow = card();
        previousText = text("", 13, Color.rgb(137, 133, 124), false);
        previousText.setLineSpacing(0, 1.25f);
        follow.addView(previousText);
        currentText = text("Generate narration, then play to follow the text here.", 19, Color.rgb(25, 27, 28), true);
        currentText.setLineSpacing(0, 1.35f);
        currentText.setPadding(0, dp(13), 0, dp(13));
        follow.addView(currentText);
        nextText = text("", 13, Color.rgb(137, 133, 124), false);
        nextText.setLineSpacing(0, 1.25f);
        follow.addView(nextText);
        root.addView(follow);

        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(18), 0, 0);
        Button previous = secondaryButton("‹ Chapter");
        Button back15 = secondaryButton("−15s");
        playPause = button("▶ Play");
        Button forward15 = secondaryButton("+15s");
        Button next = secondaryButton("Chapter ›");
        controls.addView(previous, controlParams());
        controls.addView(back15, controlParams());
        controls.addView(playPause, controlParams());
        controls.addView(forward15, controlParams());
        controls.addView(next, controlParams());
        root.addView(controls);

        previous.setOnClickListener(v -> command(PlaybackService.ACTION_PREVIOUS));
        back15.setOnClickListener(v -> command(PlaybackService.ACTION_BACK));
        playPause.setOnClickListener(v -> command(PlaybackService.ACTION_TOGGLE));
        forward15.setOnClickListener(v -> command(PlaybackService.ACTION_FORWARD));
        next.setOnClickListener(v -> command(PlaybackService.ACTION_NEXT));

        TextView note = text("Text follows generated narration chunks. Playback continues in the background and from the lock screen.", 11, Color.rgb(119, 116, 108), false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(12), dp(18), dp(12), 0);
        root.addView(note);

        activity.setContentView(scroll);
        handler.post(ticker);
    }

    private void refresh() {
        ListeningProgressStore.Entry entry = progress.load(book.bookId);
        if (entry == null) {
            playPause.setText("▶ Play");
            return;
        }
        int chapter = Math.max(0, Math.min(book.chapters.size() - 1, entry.chapterIndex));
        chapterTitle.setText((chapter + 1) + "  •  " + safeTitle(entry.chapterTitle, book.chapters.get(chapter).title));
        long duration = Math.max(0, entry.durationMs);
        long position = Math.max(0, Math.min(duration <= 0 ? Long.MAX_VALUE : duration, entry.positionMs));
        progressBar.setProgress(duration <= 0 ? 0 : (int) Math.min(1000, position * 1000L / Math.max(1, duration)));
        progressLabel.setText(time(position) + "  /  " + time(duration));

        File audio = new File(entry.audioPath);
        List<AudioTimingStore.Segment> segments = AudioTimingStore.read(audio);
        int segmentIndex = findSegment(segments, position);
        if (segmentIndex >= 0 && (!audio.getAbsolutePath().equals(shownPath) || segmentIndex != shownSegment)) {
            shownPath = audio.getAbsolutePath();
            shownSegment = segmentIndex;
            previousText.setText(segmentIndex > 0 ? segments.get(segmentIndex - 1).text : "");
            currentText.setText(segments.get(segmentIndex).text);
            nextText.setText(segmentIndex + 1 < segments.size() ? segments.get(segmentIndex + 1).text : "");
        } else if (segments.isEmpty()) {
            currentText.setText(book.chapters.get(chapter).text.length() > 1000
                    ? book.chapters.get(chapter).text.substring(0, 1000) + "…"
                    : book.chapters.get(chapter).text);
            previousText.setText(""); nextText.setText("");
        }
        playPause.setText(position > 0 && position < duration ? "❚❚ / ▶" : "▶ Play");
    }

    private int findSegment(List<AudioTimingStore.Segment> segments, long position) {
        if (segments == null || segments.isEmpty()) return -1;
        for (int i = 0; i < segments.size(); i++) {
            AudioTimingStore.Segment s = segments.get(i);
            if (position >= s.startMs && position < s.endMs) return i;
        }
        return position >= segments.get(segments.size() - 1).endMs ? segments.size() - 1 : 0;
    }

    private void command(String action) {
        try { activity.startService(new Intent(activity, PlaybackService.class).setAction(action)); }
        catch (Exception ignored) { }
    }

    private LinearLayout.LayoutParams controlParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
        p.leftMargin = dp(3); p.rightMargin = dp(3);
        return p;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); v.setBackgroundColor(Color.rgb(247, 244, 237)); return v;
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(255, 253, 249)); bg.setCornerRadius(dp(22)); bg.setStroke(dp(1), Color.rgb(226, 219, 206));
        v.setBackground(bg); v.setElevation(dp(1)); return v;
    }

    private Button button(String value) {
        Button b = new Button(activity); b.setText(value); b.setAllCaps(false); b.setTextSize(11); b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(48, 48, 43)); bg.setCornerRadius(dp(17)); b.setBackground(bg); return b;
    }

    private Button secondaryButton(String value) {
        Button b = new Button(activity); b.setText(value); b.setAllCaps(false); b.setTextSize(10); b.setTextColor(Color.rgb(48, 48, 43));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(239, 234, 224)); bg.setCornerRadius(dp(17)); b.setBackground(bg); return b;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(activity); v.setText(value); v.setTextSize(size); v.setTextColor(color); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }

    private Bitmap bitmap(byte[] data) {
        try { return data == null ? null : BitmapFactory.decodeByteArray(data, 0, data.length); } catch (Exception e) { return null; }
    }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static String time(long ms) { long s = Math.max(0, ms / 1000); return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60); }
    private static String safeTitle(String a, String b) { return a == null || a.trim().isEmpty() ? (b == null ? "Chapter" : b) : a; }
}
