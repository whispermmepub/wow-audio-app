package com.whisper.wowaudio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
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

import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int PICK_BOOK = 41;
    private BookStore store;
    private LinearLayout content;
    private TextView voiceStatus;
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(ReadingService.EXTRA_MESSAGE);
            if (message != null && !message.isEmpty()) Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
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
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(22), dp(18), dp(32));
        content.setBackgroundColor(Color.rgb(247, 244, 237));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private void refresh() {
        content.removeAllViews();

        TextView title = heading("WoW Audio", 28);
        content.addView(title);
        content.addView(text("စာအုပ်ထည့်ပါ။ Play နှိပ်ပါ။ မြန်မာလို တိုက်ရိုက်ဖတ်ပေးမယ်။", 16, false), marginTop(6));

        Button add = primary("＋ Add Book");
        add.setOnClickListener(v -> pickBook());
        content.addView(add, marginTop(18));

        voiceStatus = text("Checking Myanmar voice…", 14, true);
        voiceStatus.setContentDescription("Myanmar text to speech status");
        content.addView(voiceStatus, marginTop(14));

        Button ttsSettings = secondary("Text-to-speech settings");
        ttsSettings.setOnClickListener(v -> openTtsSettings());
        content.addView(ttsSettings, marginTop(8));

        List<BookStore.Book> books = store.list();
        if (books.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(heading("No books yet", 20));
            empty.addView(text("EPUB သို့မဟုတ် UTF-8 TXT ဖိုင်ကို Add Book နဲ့ထည့်ပါ။", 15, false), marginTop(6));
            content.addView(empty, marginTop(22));
            return;
        }

        TextView library = heading("Library", 22);
        content.addView(library, marginTop(24));
        for (BookStore.Book book : books) content.addView(bookCard(book), marginTop(12));
    }

    private View bookCard(BookStore.Book book) {
        LinearLayout card = card();
        TextView name = heading(book.title, 20);
        name.setContentDescription("Book: " + book.title);
        card.addView(name);
        if (book.author != null && !book.author.trim().isEmpty()) {
            card.addView(text(book.author, 14, false), marginTop(3));
        }
        card.addView(text(book.originalName, 12, false), marginTop(4));

        boolean resumed = hasProgress(book.id);
        Button play = primary(resumed ? "▶ Resume" : "▶ Play");
        play.setContentDescription((resumed ? "Resume " : "Play ") + book.title);
        play.setOnClickListener(v -> play(book));
        card.addView(play, marginTop(14));

        Button delete = secondary("Delete Book");
        delete.setContentDescription("Delete " + book.title);
        delete.setOnClickListener(v -> confirmDelete(book));
        card.addView(delete, marginTop(8));
        return card;
    }

    private void pickBook() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/epub+zip", "text/plain", "application/octet-stream"});
        startActivityForResult(intent, PICK_BOOK);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_BOOK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importUri(data.getData());
        }
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(action)) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(action)) {
            if (Build.VERSION.SDK_INT >= 33) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            else {
                //noinspection deprecation
                uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
        }
        if (uri != null) importUri(uri);
    }

    private void importUri(Uri uri) {
        if (uri == null) return;
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Adding book")
                .setMessage("Reading the file…")
                .setCancelable(false)
                .create();
        progress.show();
        new Thread(() -> {
            try {
                BookStore.Book book = store.importBook(uri);
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "Added: " + book.title, Toast.LENGTH_SHORT).show();
                    refresh();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("Could not add book")
                            .setMessage(safeMessage(e))
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
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
                .setMessage(book.title + "\n\nThis removes the private copy and saved reading position from WoW Audio.")
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
        return getSharedPreferences("reading_progress", MODE_PRIVATE).getInt(id + ":chunk", 0) > 0 ||
                getSharedPreferences("reading_progress", MODE_PRIVATE).getInt(id + ":offset", 0) > 0;
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
                voiceStatus.setText("Myanmar voice: TTS engine unavailable");
            } else {
                int result = t.isLanguageAvailable(new Locale("my", "MM"));
                if (result < TextToSpeech.LANG_AVAILABLE) result = t.isLanguageAvailable(new Locale("my"));
                voiceStatus.setText(result >= TextToSpeech.LANG_AVAILABLE
                        ? "Myanmar voice: Ready ✓"
                        : "Myanmar voice: Not available in the current TTS engine");
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
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 55);
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.rgb(225, 219, 207));
        card.setBackground(bg);
        return card;
    }

    private Button primary(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(dp(58));
        b.setContentDescription(label);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(48, 48, 43));
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        return b;
    }

    private Button secondary(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.rgb(35, 35, 32));
        b.setMinHeight(dp(54));
        b.setContentDescription(label);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(235, 231, 221));
        bg.setCornerRadius(dp(15));
        b.setBackground(bg);
        return b;
    }

    private TextView heading(String value, float size) {
        TextView t = text(value, size, true);
        if (Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        return t;
    }

    private TextView text(String value, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(Color.rgb(27, 28, 27));
        t.setGravity(Gravity.START);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams marginTop(int dp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(dp);
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
