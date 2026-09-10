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
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
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
import java.util.Locale;
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
    private TextView voiceStatus;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(ReadingService.EXTRA_MESSAGE);
            if (message != null && !message.isEmpty()) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
            refresh();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new BookStore(this);
        buildShell();
        requestNotificationPermission();
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
        probeMyanmarVoice();
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
        content.removeAllViews();
        content.addView(hero());

        Button add = primary("＋  Add Book");
        add.setContentDescription("Add EPUB or text book");
        add.setOnClickListener(v -> pickBook());
        content.addView(add, marginTop(16));

        LinearLayout voiceCard = card();
        voiceCard.addView(heading("Myanmar Voice", 18, INK));
        voiceStatus = text("Checking Myanmar voice…", 14, MUTED, false);
        voiceStatus.setContentDescription("Myanmar text to speech status");
        voiceCard.addView(voiceStatus, marginTop(6));
        Button ttsSettings = secondary("Voice settings");
        ttsSettings.setOnClickListener(v -> openTtsSettings());
        voiceCard.addView(ttsSettings, marginTop(10));
        content.addView(voiceCard, marginTop(14));

        List<BookStore.Book> books = store.list();
        if (books.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(heading("စာအုပ် မရှိသေးပါ", 20, INK));
            empty.addView(text(
                    "EPUB သို့မဟုတ် UTF-8 TXT ဖိုင်ကို Add Book နဲ့ထည့်ပါ။ Telegram/File Manager ကနေ Open with သို့မဟုတ် Share နဲ့လည်း တန်းထည့်နိုင်ပါတယ်။",
                    15, MUTED, false), marginTop(8));
            content.addView(empty, marginTop(20));
            return;
        }

        TextView library = heading("Library", 22, INK);
        library.setContentDescription("Library. " + books.size() + " books.");
        content.addView(library, marginTop(24));
        for (BookStore.Book book : books) content.addView(bookCard(book), marginTop(12));
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
        eyebrow.setLetterSpacing(0.08f);
        hero.addView(eyebrow);
        hero.addView(heading("WoW Audio", 30, Color.WHITE), marginTop(8));
        hero.addView(text("ဖိုင်ထည့် • Play နှိပ် • မြန်မာလို နားထောင်", 17,
                Color.rgb(223, 235, 255), false), marginTop(6));
        hero.addView(text("Telegram / File Manager • Open with • Share", 14,
                Color.rgb(185, 214, 255), false), marginTop(9));
        return hero;
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
        delete.setContentDescription("Delete " + book.title);
        delete.setOnClickListener(v -> confirmDelete(book));
        card.addView(delete, marginTop(9));
        return card;
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
        Intent service = new Intent(this, ReadingService.class)
                .setAction(ReadingService.ACTION_PLAY_BOOK)
                .putExtra(ReadingService.EXTRA_BOOK_ID, book.id);
        ContextCompat.startForegroundService(this, service);
        Toast.makeText(this, "Starting " + book.title, Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(BookStore.Book book) {
        new AlertDialog.Builder(this)
                .setTitle("Delete book?")
                .setMessage(book.title
                        + "\n\nWoW Audio ထဲက private copy နဲ့ saved reading position ကို ဖျက်ပါမယ်။ မူရင်းဖိုင်ကို မဖျက်ပါ။")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    Intent stop = new Intent(this, ReadingService.class).setAction(ReadingService.ACTION_STOP);
                    startService(stop);
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

    private void probeMyanmarVoice() {
        if (voiceStatus == null) return;
        voiceStatus.setText("Checking Myanmar voice…");
        final TextToSpeech[] probe = new TextToSpeech[1];
        probe[0] = new TextToSpeech(getApplicationContext(), status -> {
            TextToSpeech t = probe[0];
            if (voiceStatus == null || isFinishing()) {
                if (t != null) t.shutdown();
                return;
            }
            if (status != TextToSpeech.SUCCESS || t == null) {
                voiceStatus.setText("Myanmar voice: engine unavailable");
            } else {
                int result = t.isLanguageAvailable(new Locale("my", "MM"));
                if (result < TextToSpeech.LANG_AVAILABLE) result = t.isLanguageAvailable(new Locale("my"));
                voiceStatus.setText(result >= TextToSpeech.LANG_AVAILABLE
                        ? "Myanmar voice: Ready ✓"
                        : "Myanmar voice: Not available in the current engine");
            }
            if (t != null) t.shutdown();
        });
    }

    private void openTtsSettings() {
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 55);
        }
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
        b.setContentDescription(label);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{NAVY_800, PURPLE});
        bg.setCornerRadius(dp(17));
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

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
