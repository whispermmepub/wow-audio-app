package com.whisper.wowaudio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
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

/** Library-first home. Voice controls live in Settings; playback lives in PlayerActivity. */
public final class LibraryActivity extends Activity {
    private static final int PICK_BOOK = 41;
    private static final int NAVY_950 = Color.rgb(7, 27, 69);
    private static final int NAVY_800 = Color.rgb(14, 52, 117);
    private static final int PURPLE = Color.rgb(98, 82, 215);
    private static final int PAGE = Color.rgb(246, 248, 253);
    private static final int INK = Color.rgb(20, 33, 61);
    private static final int MUTED = Color.rgb(91, 105, 132);

    private BookStore store;
    private LinearLayout content;

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
        refresh();
    }

    private void buildShell() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(40));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private void refresh() {
        if (content == null) return;
        content.removeAllViews();
        content.addView(topBar());

        Button add = primary("＋  Add Book");
        add.setContentDescription("Add EPUB or text book");
        add.setOnClickListener(v -> pickBook());
        content.addView(add, marginTop(18));

        List<BookStore.Book> books = store.list();
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView library = heading("Library", 25, INK);
        titleRow.addView(library, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView count = text(books.size() + (books.size() == 1 ? " book" : " books"), 13, MUTED, false);
        titleRow.addView(count);
        content.addView(titleRow, marginTop(26));

        if (books.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(heading("စာအုပ် မရှိသေးပါ", 20, INK));
            empty.addView(text("EPUB သို့မဟုတ် UTF-8 TXT ဖိုင်ကို Add Book နဲ့ထည့်ပါ။ Telegram/File Manager ကနေ Open with / Share နဲ့လည်း တန်းထည့်နိုင်ပါတယ်။",
                    15, MUTED, false), marginTop(8));
            content.addView(empty, marginTop(14));
            return;
        }

        for (BookStore.Book book : books) content.addView(bookCard(book), marginTop(12));
    }

    private View topBar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(heading("WoW Audio", 29, NAVY_950));
        brand.addView(text("Accessible Myanmar Audiobooks", 13, MUTED, false), marginTop(1));
        row.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));

        Button settings = secondary("⚙");
        settings.setTextSize(24);
        settings.setContentDescription("Settings");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        row.addView(settings, new LinearLayout.LayoutParams(dp(58), dp(54)));
        return row;
    }

    private View bookCard(BookStore.Book book) {
        LinearLayout outer = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.TOP);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(coverView(book), new LinearLayout.LayoutParams(dp(86), dp(122)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1f);
        ip.leftMargin = dp(14);
        row.addView(info, ip);

        TextView name = heading(book.title, 19, INK);
        name.setContentDescription("Book: " + book.title);
        info.addView(name);
        if (book.author != null && !book.author.trim().isEmpty()) {
            info.addView(text(book.author, 14, MUTED, false), marginTop(5));
        }
        String type = "epub".equals(book.type) ? "EPUB" : "TXT";
        info.addView(text(type + " • " + VoiceSettings.engineLabel(this), 12, Color.rgb(118, 132, 157), false), marginTop(7));
        outer.addView(row);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        boolean resume = hasProgress(book.id);
        Button play = primary(resume ? "▶  Resume" : "▶  Play");
        play.setContentDescription((resume ? "Resume " : "Play ") + book.title);
        play.setOnClickListener(v -> play(book));
        actions.addView(play, new LinearLayout.LayoutParams(0, dp(58), 1f));

        Button more = secondary("⋮");
        more.setTextSize(24);
        more.setContentDescription("More options for " + book.title);
        more.setOnClickListener(v -> bookMenu(book));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(62), dp(58));
        mp.leftMargin = dp(9);
        actions.addView(more, mp);
        outer.addView(actions, marginTop(14));
        return outer;
    }

    private View coverView(BookStore.Book book) {
        Bitmap bitmap = BookCover.load(book);
        if (bitmap != null) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setContentDescription("Cover of " + book.title);
            GradientDrawable outline = new GradientDrawable();
            outline.setColor(Color.rgb(230, 235, 246));
            outline.setCornerRadius(dp(12));
            image.setBackground(outline);
            image.setClipToOutline(Build.VERSION.SDK_INT >= 21);
            return image;
        }
        TextView fallback = text("WoW\nAudio", 16, Color.WHITE, true);
        fallback.setGravity(Gravity.CENTER);
        fallback.setContentDescription("No cover. " + book.title);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{NAVY_950, NAVY_800, PURPLE});
        bg.setCornerRadius(dp(12));
        fallback.setBackground(bg);
        return fallback;
    }

    private void bookMenu(BookStore.Book book) {
        String[] items = new String[]{"Play / Resume", "Delete Book"};
        new AlertDialog.Builder(this)
                .setTitle(book.title)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) play(book);
                    else confirmDelete(book);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void play(BookStore.Book book) {
        requestNotificationPermission();
        Intent service = new Intent(this, AudiobookService.class)
                .setAction(AudiobookService.ACTION_PLAY_BOOK)
                .putExtra(AudiobookService.EXTRA_BOOK_ID, book.id);
        try {
            ContextCompat.startForegroundService(this, service);
            Intent player = new Intent(this, PlayerActivity.class)
                    .putExtra(AudiobookService.EXTRA_BOOK_ID, book.id);
            startActivity(player);
        } catch (RuntimeException e) {
            showError("Could not start reading", e);
        }
    }

    private void confirmDelete(BookStore.Book book) {
        new AlertDialog.Builder(this)
                .setTitle("Delete book?")
                .setMessage(book.title + "\n\nWoW Audio ထဲက private copy၊ generated audio cache နဲ့ saved reading position ကို ဖျက်ပါမယ်။ မူရင်း EPUB/TXT ကို မဖျက်ပါ။")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    try {
                        startService(new Intent(this, AudiobookService.class).setAction(AudiobookService.ACTION_STOP));
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

    private void pickBook() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/epub+zip", "application/octet-stream", "application/zip",
                "application/x-zip-compressed", "text/plain"});
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
                .setMessage("စာအုပ်ကို Library ထဲ ထည့်နေပါတယ်…")
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            int added = 0;
            String firstError = null;
            String lastTitle = null;
            for (Uri uri : uris) {
                try {
                    BookStore.Book book = store.importBook(uri);
                    added++;
                    lastTitle = book.title;
                    BookCover.load(book);
                } catch (Exception e) {
                    if (firstError == null) firstError = safeMessage(e);
                }
            }
            final int addedCount = added;
            final String error = firstError;
            final String title = lastTitle;
            runOnUiThread(() -> {
                progress.dismiss();
                refresh();
                if (addedCount == total) {
                    Toast.makeText(this, total == 1 ? "Added: " + title : "Added " + addedCount + " books", Toast.LENGTH_SHORT).show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle(addedCount > 0 ? "Some files were skipped" : "Could not add book")
                            .setMessage((addedCount > 0 ? "Added " + addedCount + " of " + total + ".\n\n" : "")
                                    + (error == null ? "Only EPUB and UTF-8 TXT files are supported." : error))
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }, "book-import").start();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 55);
        }
    }

    private void showError(String title, Throwable error) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(safeMessage(error))
                .setPositiveButton("OK", null)
                .show();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
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
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{NAVY_800, PURPLE});
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        return b;
    }

    private Button secondary(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(NAVY_800);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(238, 243, 252));
        bg.setCornerRadius(dp(15));
        bg.setStroke(dp(1), Color.rgb(197, 211, 237));
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
        String m = t == null ? null : t.getMessage();
        return m == null || m.trim().isEmpty()
                ? (t == null ? "Unknown error" : t.getClass().getSimpleName()) : m;
    }
}
