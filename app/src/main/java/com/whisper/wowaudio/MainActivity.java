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
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.List;

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

        TextView builtIn = statusChip("●  Built-in Myanmar Voice   •   Offline");
        builtIn.setContentDescription("Built in Myanmar voice. Offline. No extra text to speech app or internet required.");
        content.addView(builtIn, marginTop(12));

        List<BookStore.Book> books = store.list();
        if (books.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(heading("စာအုပ် မရှိသေးပါ", 20, INK));
            empty.addView(text("EPUB သို့မဟုတ် UTF-8 TXT ဖိုင်ကို Add Book နှိပ်ပြီး ထည့်ပါ။ ပြီးရင် Play တစ်ချက်နှိပ်ရုံပါ။", 15, MUTED, false), marginTop(8));
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
        hero.addView(text("ဖိုင်ထည့် • Play နှိပ် • မြန်မာလို တိုက်ရိုက်ဖတ်", 17, Color.rgb(223, 235, 255), false), marginTop(6));
        hero.addView(text("API key မလို • အင်တာနက်မလို • TTS app အပိုမလို", 14, Color.rgb(185, 214, 255), false), marginTop(9));
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
        play.setContentDescription((resumed ? "Resume " : "Play ") + book.title + " with built in Myanmar voice");
        play.setOnClickListener(v -> play(book));
        card.addView(play, marginTop(15));

        Button delete = deleteButton("Delete Book");
        delete.setContentDescription("Delete " + book.title + " from WoW Audio");
        delete.setOnClickListener(v -> confirmDelete(book));
        card.addView(delete, marginTop(9));
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
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Adding book")
                .setMessage("စာအုပ်ကို Library ထဲ ထည့်နေပါတယ်…")
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
                .setMessage(book.title + "\n\nWoW Audio ထဲက private copy နဲ့ saved reading position ကို ဖျက်ပါမယ်။ မူရင်းဖိုင်ကို မဖျက်ပါ။")
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
        return getSharedPreferences("reading_progress", MODE_PRIVATE).getInt(id + ":chunk", 0) > 0;
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

    private TextView statusChip(String value) {
        TextView t = text(value, 14, NAVY_800, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(11), dp(12), dp(11));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(232, 246, 255));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.rgb(167, 219, 245));
        t.setBackground(bg);
        return t;
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

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 55);
        }
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
