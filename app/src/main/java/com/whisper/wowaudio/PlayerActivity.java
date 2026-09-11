package com.whisper.wowaudio;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.Locale;

/** Full-screen audiobook player with book scrub and live narration controls. */
public final class PlayerActivity extends Activity {
    private static final int NAVY = Color.rgb(9, 34, 82);
    private static final int NAVY_LIGHT = Color.rgb(22, 66, 137);
    private static final int WHITE_SOFT = Color.rgb(225, 234, 250);
    private static final int WHITE_MUTED = Color.rgb(175, 193, 224);

    private String bookId;
    private BookStore.Book book;
    private ImageView coverImage;
    private TextView coverFallback;
    private TextView readingText;
    private LinearLayout coverPanel;
    private LinearLayout textPanel;
    private Button coverTab;
    private Button textTab;
    private TextView title;
    private TextView author;
    private TextView status;
    private TextView part;
    private TextView elapsed;
    private TextView duration;
    private TextView voice;
    private SeekBar seek;
    private Button playPause;
    private Button speedButton;
    private Button volumeButton;
    private Button toneButton;
    private Button styleButton;
    private boolean playing;
    private boolean dragging;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!AudiobookService.ACTION_STATE.equals(intent.getAction())) return;
            String stateBookId = intent.getStringExtra(AudiobookService.EXTRA_BOOK_ID);
            if (stateBookId != null && bookId != null && !bookId.equals(stateBookId)) return;
            applyState(intent);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bookId = getIntent().getStringExtra(AudiobookService.EXTRA_BOOK_ID);
        book = new BookStore(this).get(bookId);
        build();
        bindBook();
        refreshNarrationButtons();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshNarrationButtons();
        ContextCompat.registerReceiver(this, receiver,
                new IntentFilter(AudiobookService.ACTION_STATE),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        send(AudiobookService.ACTION_REQUEST_STATE);
    }

    @Override protected void onPause() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        super.onPause();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(NAVY);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = darkButton("⌄");
        back.setTextSize(26);
        back.setContentDescription("Back to library");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(58), dp(52)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER);
        coverTab = tab("COVER", true);
        textTab = tab("TEXT", false);
        coverTab.setOnClickListener(v -> showCover());
        textTab.setOnClickListener(v -> showText());
        tabs.addView(coverTab, new LinearLayout.LayoutParams(dp(112), dp(48)));
        tabs.addView(textTab, new LinearLayout.LayoutParams(dp(112), dp(48)));
        top.addView(tabs, new LinearLayout.LayoutParams(0, -2, 1f));

        Button settings = darkButton("⚙");
        settings.setTextSize(22);
        settings.setContentDescription("Voice settings");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        top.addView(settings, new LinearLayout.LayoutParams(dp(58), dp(52)));
        root.addView(top);

        coverPanel = new LinearLayout(this);
        coverPanel.setGravity(Gravity.CENTER);
        coverPanel.setPadding(0, dp(26), 0, dp(16));
        coverPanel.setOrientation(LinearLayout.VERTICAL);
        coverImage = new ImageView(this);
        coverImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverImage.setContentDescription("Book cover");
        GradientDrawable coverBg = new GradientDrawable();
        coverBg.setColor(Color.rgb(35, 72, 139));
        coverBg.setCornerRadius(dp(24));
        coverImage.setBackground(coverBg);
        if (Build.VERSION.SDK_INT >= 21) coverImage.setClipToOutline(true);
        coverPanel.addView(coverImage, new LinearLayout.LayoutParams(dp(286), dp(286)));

        coverFallback = label("WoW\nAudio", 34, Color.WHITE, true);
        coverFallback.setGravity(Gravity.CENTER);
        coverFallback.setVisibility(View.GONE);
        GradientDrawable fallbackBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(18, 64, 139), Color.rgb(93, 73, 202)});
        fallbackBg.setCornerRadius(dp(24));
        coverFallback.setBackground(fallbackBg);
        coverPanel.addView(coverFallback, new LinearLayout.LayoutParams(dp(286), dp(286)));
        root.addView(coverPanel);

        textPanel = new LinearLayout(this);
        textPanel.setOrientation(LinearLayout.VERTICAL);
        textPanel.setPadding(dp(10), dp(28), dp(10), dp(20));
        readingText = label("Preparing text…", 20, Color.WHITE, false);
        readingText.setLineSpacing(0, 1.35f);
        readingText.setTextIsSelectable(true);
        textPanel.addView(readingText);
        textPanel.setVisibility(View.GONE);
        root.addView(textPanel);

        title = label("Book", 25, Color.WHITE, true);
        title.setGravity(Gravity.START);
        root.addView(title, marginTop(10));
        author = label("", 15, WHITE_MUTED, false);
        root.addView(author, marginTop(4));
        voice = label("", 13, WHITE_MUTED, false);
        root.addView(voice, marginTop(5));
        status = label("Preparing…", 13, WHITE_SOFT, false);
        root.addView(status, marginTop(8));

        seek = new SeekBar(this);
        seek.setMax(10000);
        seek.setContentDescription("Book position");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) part.setText("Book position " + Math.round(progress / 100f) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                dragging = false;
                Intent i = action(AudiobookService.ACTION_SEEK_BOOK_PROGRESS)
                        .putExtra(AudiobookService.EXTRA_PROGRESS, seekBar.getProgress());
                startService(i);
            }
        });
        root.addView(seek, marginTop(28));

        LinearLayout timeRow = new LinearLayout(this);
        elapsed = label("0:00", 13, WHITE_MUTED, false);
        duration = label("0:00", 13, WHITE_MUTED, false);
        timeRow.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1f));
        duration.setGravity(Gravity.END);
        timeRow.addView(duration, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(timeRow);
        part = label("Part 1 / 1", 13, WHITE_MUTED, false);
        part.setGravity(Gravity.CENTER);
        root.addView(part, marginTop(5));

        LinearLayout narrationTop = new LinearLayout(this);
        narrationTop.setGravity(Gravity.CENTER);
        speedButton = narrationButton("1.0x");
        speedButton.setOnClickListener(v -> {
            VoiceSettings.cyclePlaybackSpeed(this);
            refreshNarrationButtons();
            send(AudiobookService.ACTION_NARRATION_SETTINGS_CHANGED);
        });
        narrationTop.addView(speedButton, new LinearLayout.LayoutParams(0, dp(58), 1f));

        volumeButton = narrationButton("Volume • +3 dB");
        volumeButton.setOnClickListener(v -> {
            VoiceSettings.cycleVolumeBoost(this);
            refreshNarrationButtons();
            send(AudiobookService.ACTION_NARRATION_SETTINGS_CHANGED);
        });
        narrationTop.addView(volumeButton, withLeft(new LinearLayout.LayoutParams(0, dp(58), 1.3f), 6));
        root.addView(narrationTop, marginTop(18));

        LinearLayout narrationBottom = new LinearLayout(this);
        narrationBottom.setGravity(Gravity.CENTER);
        toneButton = narrationButton("Tone • Normal");
        toneButton.setOnClickListener(v -> {
            VoiceSettings.cycleTone(this);
            refreshNarrationButtons();
            send(AudiobookService.ACTION_NARRATION_SETTINGS_CHANGED);
        });
        narrationBottom.addView(toneButton, new LinearLayout.LayoutParams(0, dp(58), 1f));

        styleButton = narrationButton("Style • Auto Mood");
        styleButton.setOnClickListener(v -> {
            VoiceSettings.cycleReadingStyle(this);
            refreshNarrationButtons();
            send(AudiobookService.ACTION_NARRATION_SETTINGS_CHANGED);
        });
        narrationBottom.addView(styleButton, withLeft(new LinearLayout.LayoutParams(0, dp(58), 1.35f), 6));
        root.addView(narrationBottom, marginTop(7));

        TextView narrationHint = label(
                "Speed, Volume နဲ့ Tone ကို နားထောင်နေတုန်း ချက်ချင်းပြောင်းနိုင်ပါတယ်။ Volume ကို Normal / +3 / +6 / +9 / +12 dB ထိတိုးနိုင်ပါတယ်။ Auto Mood / Narrator / Storyteller စတဲ့ Reading Style တွေက Gemini မှာ စာသားရဲ့ခံစားချက်နဲ့လိုက်ပြီး ပိုသဘာဝကျပါတယ်။",
                12, WHITE_MUTED, false);
        narrationHint.setLineSpacing(0, 1.2f);
        root.addView(narrationHint, marginTop(8));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        Button previous = control("|◀");
        previous.setContentDescription("Previous section");
        previous.setOnClickListener(v -> send(AudiobookService.ACTION_PREVIOUS));
        controls.addView(previous, new LinearLayout.LayoutParams(0, dp(66), 1f));

        Button back15 = control("↶ 15s");
        back15.setContentDescription("Back 15 seconds");
        back15.setOnClickListener(v -> send(AudiobookService.ACTION_SEEK_BACK));
        controls.addView(back15, withLeft(new LinearLayout.LayoutParams(0, dp(66), 1f), 6));

        playPause = control("▶");
        playPause.setTextSize(28);
        playPause.setContentDescription("Play or pause");
        playPause.setOnClickListener(v -> send(AudiobookService.ACTION_TOGGLE));
        controls.addView(playPause, withLeft(new LinearLayout.LayoutParams(0, dp(72), 1.1f), 6));

        Button forward15 = control("15s ↷");
        forward15.setContentDescription("Forward 15 seconds");
        forward15.setOnClickListener(v -> send(AudiobookService.ACTION_SEEK_FORWARD));
        controls.addView(forward15, withLeft(new LinearLayout.LayoutParams(0, dp(66), 1f), 6));

        Button next = control("▶|");
        next.setContentDescription("Next section");
        next.setOnClickListener(v -> send(AudiobookService.ACTION_NEXT));
        controls.addView(next, withLeft(new LinearLayout.LayoutParams(0, dp(66), 1f), 6));
        root.addView(controls, marginTop(20));

        Button stop = darkButton("Stop and save position");
        stop.setContentDescription("Stop reading and save position");
        stop.setOnClickListener(v -> {
            send(AudiobookService.ACTION_STOP);
            Toast.makeText(this, "Stopped • position saved", Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(stop, marginTop(16));
    }

    private void bindBook() {
        if (book == null) {
            status.setText("Book not found");
            return;
        }
        title.setText(book.title);
        author.setText(book.author == null || book.author.trim().isEmpty() ? "" : book.author);
        Bitmap bitmap = BookCover.load(book);
        if (bitmap != null) {
            coverImage.setImageBitmap(bitmap);
            coverImage.setVisibility(View.VISIBLE);
            coverFallback.setVisibility(View.GONE);
        } else {
            coverImage.setVisibility(View.GONE);
            coverFallback.setVisibility(View.VISIBLE);
        }
    }

    private void applyState(Intent state) {
        playing = state.getBooleanExtra(AudiobookService.EXTRA_PLAYING, false);
        playPause.setText(playing ? "Ⅱ" : "▶");
        playPause.setContentDescription(playing ? "Pause" : "Play");
        String message = state.getStringExtra(AudiobookService.EXTRA_MESSAGE);
        if (message != null && !message.trim().isEmpty()) status.setText(message);
        String label = state.getStringExtra(AudiobookService.EXTRA_VOICE_LABEL);
        if (label != null && !label.isEmpty()) voice.setText("Voice • " + label);
        String currentText = state.getStringExtra(AudiobookService.EXTRA_TEXT);
        if (currentText != null && !currentText.isEmpty()) readingText.setText(currentText);

        String speedLabel = state.getStringExtra(AudiobookService.EXTRA_SPEED_LABEL);
        String volumeLabel = state.getStringExtra(AudiobookService.EXTRA_VOLUME_LABEL);
        String toneLabel = state.getStringExtra(AudiobookService.EXTRA_TONE_LABEL);
        String styleLabel = state.getStringExtra(AudiobookService.EXTRA_STYLE_LABEL);
        if (speedLabel != null && !speedLabel.isEmpty()) speedButton.setText(speedLabel);
        if (volumeLabel != null && !volumeLabel.isEmpty()) volumeButton.setText("Volume • " + volumeLabel);
        if (toneLabel != null && !toneLabel.isEmpty()) toneButton.setText("Tone • " + toneLabel);
        if (styleLabel != null && !styleLabel.isEmpty()) styleButton.setText("Style • " + styleLabel);

        int index = state.getIntExtra(AudiobookService.EXTRA_SEGMENT_INDEX, 0);
        int count = Math.max(1, state.getIntExtra(AudiobookService.EXTRA_SEGMENT_COUNT, 1));
        int position = Math.max(0, state.getIntExtra(AudiobookService.EXTRA_POSITION_MS, 0));
        int length = Math.max(0, state.getIntExtra(AudiobookService.EXTRA_DURATION_MS, 0));
        int progress = Math.max(0, Math.min(10000,
                state.getIntExtra(AudiobookService.EXTRA_PROGRESS, 0)));
        if (!dragging) seek.setProgress(progress);
        elapsed.setText(formatTime(position));
        duration.setText(length > 0 ? formatTime(length) : "--:--");
        part.setText("Part " + Math.min(index + 1, count) + " / " + count
                + " • Book " + Math.round(progress / 100f) + "%");
    }

    private void refreshNarrationButtons() {
        if (speedButton == null || volumeButton == null || toneButton == null || styleButton == null) return;
        speedButton.setText(VoiceSettings.speedLabel(this));
        speedButton.setContentDescription("Playback speed " + VoiceSettings.speedLabel(this) + ". Tap to change.");
        volumeButton.setText("Volume • " + VoiceSettings.volumeLabel(this));
        volumeButton.setContentDescription("Audio volume boost " + VoiceSettings.volumeLabel(this) + ". Tap to change.");
        toneButton.setText("Tone • " + VoiceSettings.toneLabel(this));
        toneButton.setContentDescription("Voice tone " + VoiceSettings.toneLabel(this) + ". Tap to change.");
        styleButton.setText("Style • " + VoiceSettings.readingStyleLabel(this));
        styleButton.setContentDescription("Reading style " + VoiceSettings.readingStyleLabel(this) + ". Tap to change.");
    }

    private void showCover() {
        coverPanel.setVisibility(View.VISIBLE);
        textPanel.setVisibility(View.GONE);
        styleTab(coverTab, true);
        styleTab(textTab, false);
    }

    private void showText() {
        coverPanel.setVisibility(View.GONE);
        textPanel.setVisibility(View.VISIBLE);
        styleTab(coverTab, false);
        styleTab(textTab, true);
    }

    private void send(String action) {
        try { startService(action(action)); }
        catch (RuntimeException e) { Toast.makeText(this, "Player unavailable", Toast.LENGTH_SHORT).show(); }
    }

    private Intent action(String value) {
        Intent i = new Intent(this, AudiobookService.class).setAction(value);
        if (bookId != null) i.putExtra(AudiobookService.EXTRA_BOOK_ID, bookId);
        return i;
    }

    private Button tab(String label, boolean selected) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setPadding(0, 0, 0, 0);
        styleTab(b, selected);
        return b;
    }

    private void styleTab(Button b, boolean selected) {
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? Color.rgb(55, 91, 154) : Color.rgb(16, 48, 103));
        bg.setCornerRadius(dp(22));
        b.setBackground(bg);
    }

    private Button narrationButton(String label) {
        Button b = darkButton(label);
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(5), 0, dp(5), 0);
        return b;
    }

    private Button control(String label) {
        Button b = darkButton(label);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return b;
    }

    private Button darkButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(NAVY_LIGHT);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.rgb(62, 103, 174));
        b.setBackground(bg);
        return b;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams marginTop(int value) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(value);
        return p;
    }

    private LinearLayout.LayoutParams withLeft(LinearLayout.LayoutParams p, int value) {
        p.leftMargin = dp(value);
        return p;
    }

    private String formatTime(int millis) {
        long total = Math.max(0, millis) / 1000L;
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
