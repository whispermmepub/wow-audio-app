package com.whisper.wowaudio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int PICK_BOOK = 41;

    private static final int NAVY_950 = Color.rgb(7, 27, 69);
    private static final int NAVY_800 = Color.rgb(14, 52, 117);
    private static final int NAVY_700 = Color.rgb(24, 42, 108);
    private static final int PURPLE = Color.rgb(98, 82, 215);
    private static final int CYAN = Color.rgb(98, 231, 255);
    private static final int PAGE = Color.rgb(246, 248, 253);
    private static final int INK = Color.rgb(20, 33, 61);
    private static final int MUTED = Color.rgb(91, 105, 132);

    private BookStore store;
    private LinearLayout content;
    private TextView playerStatus;
    private Button toggleControl;
    private boolean lastPlaying;
    private String lastStatus = "Ready";

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(ReadingService.EXTRA_MESSAGE);
            lastPlaying = intent.getBooleanExtra(ReadingService.EXTRA_PLAYING, false);
            if (message != null && !message.trim().isEmpty()) lastStatus = message;
            updatePlayerStatus();
            if (message != null && (message.startsWith("Myanmar voice failed")
                    || message.startsWith("Finished")
                    || message.startsWith("Natural voice unavailable"))) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new BookStore(this);
        buildShell();
        refresh();
        handleLaunchIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        ContextCompat.registerReceiver(
                this,
                stateReceiver,
                new IntentFilter(ReadingService.ACTION_STATE),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        refresh();
    }

    @Override protected void onPause() {
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) { }
        super.onPause();
    }

    private void buildShell() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(36));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private void refresh() {
        if (content == null) return;
        content.removeAllViews();
        content.addView(hero());

        Button add = primary("＋  Add Book");
        add.setContentDescription("Add EPUB or text book");
        add.setOnClickListener(v -> pickBook());
        content.addView(add, marginTop(16));

        content.addView(voiceCard(), marginTop(14));
        content.addView(playerCard(), marginTop(14));

        List<BookStore.Book> books = store.list();
        if (books.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(heading("စာအုပ် မရှိသေးပါ", 20, INK));
            empty.addView(text(
                    "EPUB သို့မဟုတ် UTF-8 TXT ဖိုင်ကို Add Book နဲ့ထည့်ပါ။ Telegram/File Manager ကနေ Open with သို့မဟုတ် Share နဲ့လည်း တန်းထည့်နိုင်ပါတယ်။",
                    15, MUTED, false), marginTop(8));
            content.addView(empty, marginTop(20));
            content.addView(attribution(), marginTop(22));
            return;
        }

        TextView library = heading("Library", 22, INK);
        library.setContentDescription("Library. " + books.size() + " books.");
        content.addView(library, marginTop(24));
        for (BookStore.Book book : books) content.addView(bookCard(book), marginTop(12));
        content.addView(attribution(), marginTop(22));
    }

    private View hero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{NAVY_950, NAVY_800, NAVY_700});
        bg.setCornerRadius(dp(24));
        hero.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) hero.setElevation(dp(5));

        TextView eyebrow = text("WoW • ACCESSIBLE READING", 12, CYAN, true);
        if (Build.VERSION.SDK_INT >= 21) eyebrow.setLetterSpacing(0.08f);
        hero.addView(eyebrow);
        hero.addView(heading("WoW Audio", 30, Color.WHITE), marginTop(8));
        hero.addView(text("ဖိုင်ထည့် • Play နှိပ် • မြန်မာလို နားထောင်", 17,
                Color.rgb(223, 235, 255), false), marginTop(6));
        hero.addView(text("Natural Myanmar • Nilar / Thiha • Open with / Share", 14,
                Color.rgb(185, 214, 255), false), marginTop(9));
        return hero;
    }

    private View voiceCard() {
        LinearLayout card = card();
        card.addView(heading("Myanmar Voice", 18, INK));
        String selected = selectedVoice();
        String label = EdgeMyanmarTtsClient.VOICE_THIHA.equals(selected) ? "Thiha" : "Nilar";
        TextView status = text("Natural online • " + label, 14, NAVY_800, true);
        status.setContentDescription("Selected Myanmar voice: " + label + ". Natural online voice.");
        card.addView(status, marginTop(6));
        card.addView(text(
                "Account/API key/server မလိုပါ။ အသံအသစ်ရယူချိန် Internet လိုပြီး နားထောင်ပြီးသားအပိုင်းတွေကို ဖုန်းထဲ cache သိမ်းထားပါတယ်။ Online မရရင် offline backup အသံကိုသုံးပါမယ်။",
                14, MUTED, false), marginTop(8));

        LinearLayout row = horizontalRow();
        Button nilar = voiceButton("Nilar", !EdgeMyanmarTtsClient.VOICE_THIHA.equals(selected));
        nilar.setContentDescription("Use Nilar natural Myanmar voice");
        nilar.setOnClickListener(v -> selectVoice(EdgeMyanmarTtsClient.VOICE_NILAR));
        row.addView(nilar, weightedButtonParams(0));

        Button thiha = voiceButton("Thiha", EdgeMyanmarTtsClient.VOICE_THIHA.equals(selected));
        thiha.setContentDescription("Use Thiha natural Myanmar voice");
        thiha.setOnClickListener(v -> selectVoice(EdgeMyanmarTtsClient.VOICE_THIHA));
        row.addView(thiha, weightedButtonParams(10));
        card.addView(row, marginTop(12));
        return card;
    }

    private View playerCard() {
        LinearLayout card = card();
        card.addView(heading("Player", 18, INK));
        playerStatus = text(lastStatus, 14, MUTED, false);
        playerStatus.setContentDescription("Player status: " + lastStatus);
        card.addView(playerStatus, marginTop(6));

        LinearLayout row = horizontalRow();
        Button back = compactControl("↶ 15s");
        back.setContentDescription("Go back 15 seconds");
        back.setOnClickListener(v -> sendPlayerAction(ReadingService.ACTION_SEEK_BACK));
        row.addView(back, weightedButtonParams(0));

        toggleControl = compactControl(lastPlaying ? "Pause" : "Play / Resume");
        toggleControl.setContentDescription("Pause or resume current book");
        toggleControl.setOnClickListener(v -> sendPlayerAction(ReadingService.ACTION_TOGGLE));
        row.addView(toggleControl, weightedButtonParams(8));

        Button forward = compactControl("15s ↷");
        forward.setContentDescription("Go forward 15 seconds");
        forward.setOnClickListener(v -> sendPlayerAction(ReadingService.ACTION_SEEK_FORWARD));
        row.addView(forward, weightedButtonParams(8));
        card.addView(row, marginTop(12));

        Button stop = secondary("Stop");
        stop.setContentDescription("Stop reading and save position");
        stop.setOnClickListener(v -> sendPlayerAction(ReadingService.ACTION_STOP));
        card.addView(stop, marginTop(9));
        return card;
    }

    private View bookCard(BookStore.Book book) {
        LinearLayout card = card();
        TextView name = heading(book.title, 20, INK);
        name.setContentDescription("Book: " + book.title);
        card.addView(name);
        if (book.author != null && !book.author.trim().isEmpty()) {
            card.addView(text(book.author, 14, MUTED, false), marginTop(4));
        }
        card.addView(text(book.originalName, 12, Color.rgb(124, 137, 160), false), marginTop(5));

        boolean resumed = hasProgress(book.id);
        Button play = primary(resumed ? "▶  Resume" : "▶  Play");
        play.setContentDescription((resumed ? "Resume " : "Play ") + book.title);
        play.setOnClickListener(v -> play(book));
        card.addView(play, marginTop(15));

        Button delete = deleteButton("Delete Book");
        delete.setContentDescription("Delete " + book.title + " from WoW Audio");
        delete.setOnClickListener(v -> confirmDelete(book));
        card.addView(delete, marginTop(9));
        return card;
    }

    private TextView attribution() {
        TextView t = text(
                "Free • Non-commercial • Natural online voice with offline Burmese fallback",
                12, Color.rgb(120, 132, 154), false);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription(
                "WoW Audio is free and non-commercial. Natural online Myanmar voice with offline Burmese fallback.");
        return t;
    }

    private void selectVoice(String voice) {
        getSharedPreferences(ReadingService.PREFS_VOICE, MODE_PRIVATE)
                .edit().putString(ReadingService.KEY_VOICE, voice).apply();
        String label = EdgeMyanmarTtsClient.VOICE_THIHA.equals(voice) ? "Thiha" : "Nilar";
        Toast.makeText(this, label + " selected • press Play or Resume", Toast.LENGTH_SHORT).show();
        refresh();
    }

    private String selectedVoice() {
        String voice = getSharedPreferences(ReadingService.PREFS_VOICE, MODE_PRIVATE)
                .getString(ReadingService.KEY_VOICE, ReadingService.DEFAULT_VOICE);
        return EdgeMyanmarTtsClient.VOICE_THIHA.equals(voice)
                ? EdgeMyanmarTtsClient.VOICE_THIHA : EdgeMyanmarTtsClient.VOICE_NILAR;
    }

    private void sendPlayerAction(String action) {
        try {
            startService(new Intent(this, ReadingService.class).setAction(action));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Player unavailable: " + safeMessage(e), Toast.LENGTH_SHORT).show();
        }
    }

    private void updatePlayerStatus() {
        if (playerStatus != null) {
            playerStatus.setText(lastStatus);
            playerStatus.setContentDescription("Player status: " + lastStatus);
        }
        if (toggleControl != null) toggleControl.setText(lastPlaying ? "Pause" : "Play / Resume");
    }

    private void pickBook() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/epub+zip",
                "application/octet-stream",
                "application/zip",
                "application/x-zip-compressed",
                "text/plain"});
        startActivityForResult(intent, PICK_BOOK);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_BOOK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importUris(Collections.singletonList(data.getData()));
        }
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action)
                && !Intent.ACTION_SEND.equals(action)
                && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        List<Uri> uris = collectUris(intent);
        if (!uris.isEmpty()) importUris(uris);
    }

    private List<Uri> collectUris(Intent intent) {
        Set<Uri> unique = new LinkedHashSet<>();
        if (intent.getData() != null) unique.add(intent.getData());

        Uri single;
        if (Build.VERSION.SDK_INT >= 33) single = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        else {
            //noinspection deprecation
            single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (single != null) unique.add(single);

        ArrayList<Uri> many;
        if (Build.VERSION.SDK_INT >= 33) many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
        else {
            //noinspection deprecation
            many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        }
        if (many != null) for (Uri uri : many) if (uri != null) unique.add(uri);

        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) unique.add(uri);
            }
        }
        return new ArrayList<>(unique);
    }

    private void importUris(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        final int total = uris.size();
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle(total == 1 ? "Adding book" : "Adding books")
                .setMessage(total == 1
                        ? "စာအုပ်ကို Library ထဲ ထည့်နေပါတယ်…"
                        : total + " files ကို Library ထဲ ထည့်နေပါတယ်…")
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            int added = 0;
            String lastTitle = null;
            String firstError = null;
            for (Uri uri : uris) {
                try {
                    BookStore.Book book = store.importBook(uri);
                    added++;
                    lastTitle = book.title;
                } catch (Exception e) {
                    if (firstError == null) firstError = safeMessage(e);
                }
            }

            final int addedCount = added;
            final String title = lastTitle;
            final String error = firstError;
            runOnUiThread(() -> {
                progress.dismiss();
                refresh();
                if (addedCount == total) {
                    Toast.makeText(this,
                            total == 1 ? "Added: " + title : "Added " + addedCount + " books",
                            Toast.LENGTH_SHORT).show();
                } else if (addedCount > 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Some files were skipped")
                            .setMessage("Added " + addedCount + " of " + total + ".\n\n"
                                    + (error == null ? "Unsupported file." : error))
                            .setPositiveButton("OK", null)
                            .show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Could not add book")
                            .setMessage(error == null
                                    ? "Only EPUB and UTF-8 TXT files are supported."
                                    : error)
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }, "book-import").start();
    }

    private void play(BookStore.Book book) {
        requestNotificationPermission();
        Intent service = new Intent(this, ReadingService.class)
                .setAction(ReadingService.ACTION_PLAY_BOOK)
                .putExtra(ReadingService.EXTRA_BOOK_ID, book.id);
        try {
            ContextCompat.startForegroundService(this, service);
            lastStatus = "Starting " + book.title;
            lastPlaying = false;
            updatePlayerStatus();
        } catch (RuntimeException e) {
            new AlertDialog.Builder(this)
                    .setTitle("Could not start reading")
                    .setMessage(safeMessage(e))
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void confirmDelete(BookStore.Book book) {
        new AlertDialog.Builder(this)
                .setTitle("Delete book?")
                .setMessage(book.title
                        + "\n\nWoW Audio ထဲက private copy၊ cached audio နဲ့ saved reading position ကို ဖျက်ပါမယ်။ မူရင်း EPUB/TXT ကို မဖျက်ပါ။")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    try {
                        startService(new Intent(this, ReadingService.class).setAction(ReadingService.ACTION_STOP));
                    } catch (RuntimeException ignored) { }
                    boolean deleted = store.delete(book.id);
                    getSharedPreferences("reading_progress", MODE_PRIVATE).edit()
                            .remove(book.id + ":chunk").remove(book.id + ":offset").apply();
                    Toast.makeText(this, deleted ? "Deleted" : "Could not delete book", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }

    private boolean hasProgress(String id) {
        return getSharedPreferences("reading_progress", MODE_PRIVATE).getInt(id + ":chunk", 0) > 0
                || getSharedPreferences("reading_progress", MODE_PRIVATE).getInt(id + ":offset", 0) > 0;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 55);
        }
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(17), dp(17), dp(17));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Color.rgb(220, 228, 243));
        card.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(2));
        return card;
    }

    private Button primary(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(dp(60));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{NAVY_800, PURPLE});
        bg.setCornerRadius(dp(17));
        b.setBackground(bg);
        return b;
    }

    private Button voiceButton(String label, boolean selected) {
        Button b = new Button(this);
        b.setText(label + (selected ? " ✓" : ""));
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinHeight(dp(58));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        if (selected) {
            b.setTextColor(Color.WHITE);
            bg.setColor(NAVY_800);
        } else {
            b.setTextColor(NAVY_800);
            bg.setColor(Color.rgb(238, 243, 252));
            bg.setStroke(dp(1), Color.rgb(197, 211, 237));
        }
        b.setBackground(bg);
        return b;
    }

    private Button compactControl(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(NAVY_800);
        b.setMinHeight(dp(60));
        b.setPadding(dp(4), 0, dp(4), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(238, 243, 252));
        bg.setCornerRadius(dp(15));
        bg.setStroke(dp(1), Color.rgb(197, 211, 237));
        b.setBackground(bg);
        return b;
    }

    private Button secondary(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(NAVY_800);
        b.setMinHeight(dp(54));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(238, 243, 252));
        bg.setCornerRadius(dp(15));
        bg.setStroke(dp(1), Color.rgb(197, 211, 237));
        b.setBackground(bg);
        return b;
    }

    private Button deleteButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.rgb(154, 44, 65));
        b.setMinHeight(dp(54));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(255, 246, 248));
        bg.setCornerRadius(dp(15));
        bg.setStroke(dp(1), Color.rgb(239, 201, 210));
        b.setBackground(bg);
        return b;
    }

    private TextView heading(String value, float size, int color) {
        TextView t = text(value, size, color, true);
        if (Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        return t;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams marginTop(int value) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(value);
        return p;
    }

    private LinearLayout.LayoutParams weightedButtonParams(int leftMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.leftMargin = dp(leftMarginDp);
        return p;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Throwable t) {
        String m = t == null ? null : t.getMessage();
        return m == null || m.trim().isEmpty()
                ? (t == null ? "Unknown error" : t.getClass().getSimpleName()) : m;
    }
}
