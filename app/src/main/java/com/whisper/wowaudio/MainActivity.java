package com.whisper.wowaudio;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {
    private LinearLayout root;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(246, 242, 234));
        getWindow().setNavigationBarColor(Color.rgb(246, 242, 234));
        getWindow().getDecorView().setSystemUiVisibility(ViewGroup.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        buildHome(null, null, null);
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null) return;
        String action = intent.getAction();
        if (!"com.whisper.wowaudio.action.OPEN_BOOK".equals(action)
                && !Intent.ACTION_VIEW.equals(action)) return;

        String title = intent.getStringExtra("wow_book_title");
        String author = intent.getStringExtra("wow_book_author");
        new Thread(() -> {
            try {
                File imported = importToPrivateStorage(uri);
                runOnUiThread(() -> buildHome(imported, title, author));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Unable to import this book", Toast.LENGTH_LONG).show());
            }
        }, "wow-audio-import").start();
    }

    private File importToPrivateStorage(Uri uri) throws Exception {
        File dir = new File(getFilesDir(), "library");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create library");
        String display = displayName(uri);
        if (display == null || display.trim().isEmpty()) display = "Imported-book.epub";
        display = display.replaceAll("[\\\\/:*?\"<>|]", "_");
        File out = uniqueFile(dir, display);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new Exception("No input stream");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) fos.write(buf, 0, n);
            fos.flush();
        }
        return out;
    }

    private String displayName(Uri uri) {
        ContentResolver resolver = getContentResolver();
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return uri.getLastPathSegment();
    }

    private File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, System.currentTimeMillis() + "-" + name);
    }

    private void buildHome(File imported, String title, String author) {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(30), dp(22), dp(28));
        root.setBackgroundColor(Color.rgb(246, 242, 234));

        TextView brand = text("WoW Audio", 30, Color.rgb(25, 29, 31), true);
        root.addView(brand);
        TextView sub = text("Myanmar audiobook companion", 13, Color.rgb(102, 105, 104), false);
        sub.setPadding(0, dp(3), 0, dp(28));
        root.addView(sub);

        if (imported == null) {
            LinearLayout card = card();
            TextView icon = text("🎧", 36, Color.rgb(34, 39, 40), false);
            card.addView(icon);
            TextView t = text("Ready for a book", 21, Color.rgb(25, 29, 31), true);
            t.setPadding(0, dp(14), 0, dp(6));
            card.addView(t);
            TextView body = text("From WoW Reader, choose “Open in WoW Audio”. The book will be copied privately into this app.",
                    14, Color.rgb(83, 87, 86), false);
            body.setLineSpacing(0, 1.25f);
            card.addView(body);
            root.addView(card, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            LinearLayout card = card();
            TextView ok = text("✓  Book received", 13, Color.rgb(20, 130, 78), true);
            card.addView(ok);
            String shownTitle = title == null || title.trim().isEmpty() ? stripExtension(imported.getName()) : title;
            TextView t = text(shownTitle, 23, Color.rgb(25, 29, 31), true);
            t.setPadding(0, dp(14), 0, dp(4));
            card.addView(t);
            if (author != null && !author.trim().isEmpty()) {
                TextView a = text(author, 14, Color.rgb(98, 101, 100), false);
                card.addView(a);
            }
            TextView file = text(imported.getName(), 12, Color.rgb(125, 127, 124), false);
            file.setPadding(0, dp(18), 0, 0);
            card.addView(file);
            root.addView(card, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout next = card();
            LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nextLp.topMargin = dp(14);
            TextView nt = text("Next: AI narration", 17, Color.rgb(25, 29, 31), true);
            next.addView(nt);
            TextView nb = text("Gemini TTS (bring your own API key), voice and style selection, whole-book playback, offline audio, and lock-screen controls will be built on this foundation.",
                    13, Color.rgb(83, 87, 86), false);
            nb.setPadding(0, dp(7), 0, 0);
            nb.setLineSpacing(0, 1.25f);
            next.addView(nb);
            root.addView(next, nextLp);
        }

        setContentView(root);
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(255, 253, 249));
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), Color.rgb(224, 217, 204));
        v.setBackground(bg);
        v.setElevation(dp(2));
        return v;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.START);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
