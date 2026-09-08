package com.whisper.wowaudio;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

public class MainActivity extends Activity {
    private static final int PICK_EPUB = 1001;
    private static final int NOTIFICATION_PERMISSION = 4102;
    private static final String WOW_OPEN_BOOK = "com.whisper.wowaudio.action.OPEN_BOOK";

    private final List<NarrationUi.BookInput> books = new ArrayList<>();
    private BookIndex bookIndex;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bookIndex = new BookIndex(this);
        getWindow().setStatusBarColor(Color.rgb(247, 244, 237));
        getWindow().setNavigationBarColor(Color.rgb(247, 244, 237));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        loadLibrary();
        showLibrary();
        handleIntent(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        if (bookIndex != null && getIntent() != null && Intent.ACTION_MAIN.equals(getIntent().getAction())) {
            loadLibrary();
            showLibrary();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_EPUB && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importUri(data.getData(), null, null, false);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!WOW_OPEN_BOOK.equals(action) && !Intent.ACTION_VIEW.equals(action)) return;
        Uri uri = intent.getData();
        if (uri == null) return;
        importUri(uri, intent.getStringExtra("wow_book_title"), intent.getStringExtra("wow_book_author"), WOW_OPEN_BOOK.equals(action));
    }

    private void launchPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/epub+zip");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/epub+zip", "application/octet-stream", "application/zip", "application/x-zip-compressed"
        });
        startActivityForResult(i, PICK_EPUB);
    }

    private void importUri(Uri uri, String suppliedTitle, String suppliedAuthor, boolean fromReader) {
        new Thread(() -> {
            File imported = null;
            try {
                imported = importToPrivateStorage(uri);
                NarrationUi.BookInput parsed = NarrationSourceLoader.load(this, imported.getName());
                String title = empty(suppliedTitle) ? parsed.title : suppliedTitle.trim();
                String author = empty(suppliedAuthor) ? parsed.author : suppliedAuthor.trim();
                bookIndex.put(new BookIndex.Entry(imported.getName(), sha256(imported), title, author, imported.lastModified()));
                final String bookId = imported.getName();
                runOnUiThread(() -> {
                    loadLibrary();
                    boolean hasKey = new SecretStore(this).hasApiKey();
                    String message = fromReader ? "Book received." : "Book imported.";
                    message += hasKey
                            ? " Narration is being prepared automatically in the background."
                            : " Set up narration once to start automatic preparation.";
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    showLibrary();
                    announceForAccessibility(message);
                    if (!hasKey) {
                        NarrationUi.BookInput target = findBook(bookId);
                        if (target != null) showBookDetail(target);
                    }
                });
            } catch (Exception e) {
                if (imported != null && imported.isFile() && !isIndexed(imported.getName())) imported.delete();
                runOnUiThread(() -> Toast.makeText(this, "Unable to import this EPUB", Toast.LENGTH_LONG).show());
            }
        }, "wow-audio-import").start();
    }

    private File importToPrivateStorage(Uri uri) throws Exception {
        File dir = libraryDir();
        String display = displayName(uri);
        if (empty(display)) display = "Imported-book.epub";
        display = display.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!display.toLowerCase(Locale.US).endsWith(".epub")) display += ".epub";

        File temp = File.createTempFile("import-", ".epub", getCacheDir());
        try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new Exception("No input stream");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        }
        try (ZipFile zip = new ZipFile(temp)) {
            if (zip.getEntry("META-INF/container.xml") == null) throw new Exception("Not an EPUB");
        }

        String hash = sha256(temp);
        for (File file : safeFiles(dir)) {
            if (file.isFile() && file.getName().toLowerCase(Locale.US).endsWith(".epub") && sha256(file).equals(hash)) {
                temp.delete();
                return file;
            }
        }

        File out = uniqueFile(dir, display);
        if (!temp.renameTo(out)) {
            try (InputStream in = new java.io.FileInputStream(temp); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) fos.write(buffer, 0, n);
            }
            temp.delete();
        }
        return out;
    }

    private void loadLibrary() {
        books.clear();
        File[] files = safeFiles(libraryDir());
        java.util.Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.US).endsWith(".epub")) continue;
            try { books.add(NarrationSourceLoader.load(this, file.getName())); }
            catch (Exception ignored) { }
        }
    }

    private void showLibrary() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(20), dp(20), dp(40));
        scroll.addView(root);
        if (Build.VERSION.SDK_INT >= 28) root.setAccessibilityPaneTitle("WoW Audio library");

        TextView title = heading("WoW Audio", 30);
        root.addView(title);
        TextView intro = text("Add a book. WoW Audio prepares all chapters automatically, then you can play from this screen.", 14, Color.rgb(87, 86, 81), false);
        intro.setLineSpacing(0, 1.25f);
        intro.setPadding(0, dp(6), 0, dp(16));
        root.addView(intro);

        Button add = primaryButton("Add Book");
        add.setContentDescription("Add Book. Import an EPUB from Files, Downloads, or another app.");
        add.setOnClickListener(v -> launchPicker());
        root.addView(add, fullButtonParams());

        if (books.isEmpty()) {
            TextView empty = text("No books yet. Use Add Book to import your first EPUB.", 16, Color.rgb(58, 58, 55), false);
            empty.setPadding(0, dp(26), 0, 0);
            root.addView(empty);
            setContentView(scroll);
            return;
        }

        SecretStore secrets = new SecretStore(this);
        if (!secrets.hasApiKey()) {
            TextView setupHeading = heading("One-time narration setup", 20);
            setupHeading.setPadding(0, dp(26), 0, dp(6));
            root.addView(setupHeading);
            TextView setupText = text("Add your Gemini API key once. After that, every imported book is prepared automatically.", 14, Color.rgb(80, 79, 74), false);
            root.addView(setupText);
            Button setup = primaryButton("Set up narration");
            LinearLayout.LayoutParams setupParams = fullButtonParams();
            setupParams.topMargin = dp(10);
            root.addView(setup, setupParams);
            setup.setOnClickListener(v -> showAdvancedNarration(books.get(0)));
        }

        ListeningProgressStore.Entry last = new ListeningProgressStore(this).last();
        NarrationUi.BookInput continueBook = last == null ? null : findBook(last.bookId);
        if (continueBook != null && canPlayAt(continueBook, last.chapterIndex)) {
            TextView continueHeading = heading("Continue Listening", 20);
            continueHeading.setPadding(0, dp(28), 0, dp(8));
            root.addView(continueHeading);
            Button resume = primaryButton("Resume " + continueBook.title);
            resume.setContentDescription("Resume listening to " + continueBook.title + ", chapter " + (last.chapterIndex + 1) + ", " + ListeningProgressStore.percent(last) + " percent through the current chapter.");
            resume.setOnClickListener(v -> playBook(continueBook, last.chapterIndex, last.positionMs));
            root.addView(resume, fullButtonParams());
        }

        TextView readyHeading = heading("Your Books", 20);
        readyHeading.setPadding(0, dp(28), 0, dp(6));
        root.addView(readyHeading);
        for (NarrationUi.BookInput book : books) root.addView(bookCard(book));

        setContentView(scroll);
    }

    private View bookCard(NarrationUi.BookInput book) {
        LinearLayout card = card();
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(-1, -2);
        outer.topMargin = dp(10);
        card.setLayoutParams(outer);

        TextView title = heading(book.title, 18);
        card.addView(title);
        TextView author = text(book.author, 13, Color.rgb(101, 99, 94), false);
        author.setPadding(0, dp(3), 0, dp(8));
        card.addView(author);

        int ready = offlineChapterCount(book);
        int total = book.chapters.size();
        String status;
        if (total == 0) status = "No readable chapters found.";
        else if (ready >= total) status = "Ready to play offline. All " + total + " chapters are prepared.";
        else if (ready > 0) status = ready + " of " + total + " chapters ready. Remaining chapters are preparing automatically.";
        else if (new SecretStore(this).hasApiKey()) status = "Preparing automatically in the background. " + total + " chapters found.";
        else status = "Narration setup required. " + total + " chapters found.";
        TextView state = text(status, 14, ready > 0 ? Color.rgb(61, 99, 66) : Color.rgb(95, 93, 87), false);
        state.setLineSpacing(0, 1.2f);
        card.addView(state);

        if (ready > 0 && canPlayAt(book, 0)) {
            Button play = primaryButton("Play " + book.title);
            play.setContentDescription("Play " + book.title + " from the beginning.");
            LinearLayout.LayoutParams p = fullButtonParams();
            p.topMargin = dp(12);
            card.addView(play, p);
            play.setOnClickListener(v -> playBook(book, 0, 0));
        } else if (!new SecretStore(this).hasApiKey()) {
            Button setup = primaryButton("Set up narration for " + book.title);
            LinearLayout.LayoutParams p = fullButtonParams();
            p.topMargin = dp(12);
            card.addView(setup, p);
            setup.setOnClickListener(v -> showAdvancedNarration(book));
        }

        Button details = secondaryButton("More options for " + book.title);
        LinearLayout.LayoutParams d = fullButtonParams();
        d.topMargin = dp(7);
        card.addView(details, d);
        details.setOnClickListener(v -> showBookDetail(book));
        return card;
    }

    private void showBookDetail(NarrationUi.BookInput book) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(20), dp(20), dp(40));
        scroll.addView(root);
        if (Build.VERSION.SDK_INT >= 28) root.setAccessibilityPaneTitle("Book options");

        Button back = secondaryButton("Back to library");
        back.setOnClickListener(v -> { loadLibrary(); showLibrary(); });
        root.addView(back, fullButtonParams());

        TextView title = heading(book.title, 26);
        title.setPadding(0, dp(22), 0, dp(5));
        root.addView(title);
        root.addView(text(book.author, 15, Color.rgb(94, 92, 87), false));

        int ready = offlineChapterCount(book);
        int total = book.chapters.size();
        TextView status = text(ready + " of " + total + " chapters ready for offline listening.", 14, Color.rgb(75, 74, 69), false);
        status.setPadding(0, dp(10), 0, dp(12));
        root.addView(status);

        if (ready > 0 && canPlayAt(book, 0)) {
            Button play = primaryButton("Play from beginning");
            play.setOnClickListener(v -> playBook(book, 0, 0));
            root.addView(play, fullButtonParams());
        }

        if (new SecretStore(this).hasApiKey() && ready < total) {
            Button prepare = primaryButton("Prepare all chapters now");
            LinearLayout.LayoutParams p = fullButtonParams();
            p.topMargin = dp(8);
            root.addView(prepare, p);
            prepare.setOnClickListener(v -> {
                NarrationGenerationService.enqueue(this, book.bookId);
                Toast.makeText(this, "Preparing all chapters in the background", Toast.LENGTH_LONG).show();
            });
        }

        Button narration = secondaryButton("Narration voice and advanced settings");
        LinearLayout.LayoutParams n = fullButtonParams();
        n.topMargin = dp(8);
        root.addView(narration, n);
        narration.setOnClickListener(v -> showAdvancedNarration(book));

        TextView chapters = heading("Chapters", 20);
        chapters.setPadding(0, dp(28), 0, dp(6));
        root.addView(chapters);
        for (int i = 0; i < book.chapters.size(); i++) {
            NarrationUi.ChapterInput chapter = book.chapters.get(i);
            int chapterIndex = i;
            LinearLayout row = card();
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.topMargin = dp(7);
            row.setLayoutParams(rp);
            row.addView(text((i + 1) + ". " + chapter.title, 15, Color.rgb(42, 42, 39), true));
            if (canPlayAt(book, i)) {
                Button playChapter = secondaryButton("Play chapter " + (i + 1));
                LinearLayout.LayoutParams cp = fullButtonParams();
                cp.topMargin = dp(6);
                row.addView(playChapter, cp);
                playChapter.setOnClickListener(v -> playBook(book, chapterIndex, 0));
            }
            root.addView(row);
        }
        setContentView(scroll);
    }

    private void showAdvancedNarration(NarrationUi.BookInput book) {
        new NarrationUi(this, book, () -> showBookDetail(book)).show();
    }

    private void playBook(NarrationUi.BookInput book, int startChapter, long startPosition) {
        NarrationSettings settings = new NarrationSettings(this);
        AudioCache cache = new AudioCache(this);
        int start = Math.max(0, Math.min(book.chapters.size() - 1, startChapter));
        ArrayList<String> paths = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        try {
            for (int i = start; i < book.chapters.size(); i++) {
                NarrationUi.ChapterInput chapter = book.chapters.get(i);
                File audio = cache.fileFor(book.bookId, i, chapter.text, settings.voice(), settings.style());
                if (!cache.isReady(audio)) break;
                paths.add(audio.getAbsolutePath());
                titles.add(chapter.title);
            }
        } catch (Exception ignored) { }
        if (paths.isEmpty()) {
            Toast.makeText(this, "This book is still being prepared", Toast.LENGTH_LONG).show();
            return;
        }
        requestNotifications();
        Intent intent = new Intent(this, PlaybackService.class).setAction(PlaybackService.ACTION_PLAY_QUEUE);
        intent.putStringArrayListExtra(PlaybackService.EXTRA_PATHS, paths);
        intent.putStringArrayListExtra(PlaybackService.EXTRA_TITLES, titles);
        intent.putExtra(PlaybackService.EXTRA_BOOK_ID, book.bookId);
        intent.putExtra(PlaybackService.EXTRA_BOOK_TITLE, book.title);
        intent.putExtra(PlaybackService.EXTRA_AUTHOR, book.author);
        intent.putExtra(PlaybackService.EXTRA_START_CHAPTER, start);
        intent.putExtra(PlaybackService.EXTRA_START_POSITION, Math.max(0, startPosition));
        intent.putExtra(PlaybackService.EXTRA_SPEED, settings.speed());
        intent.putExtra(PlaybackService.EXTRA_MINUTES, settings.sleepMinutes());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        announceForAccessibility("Playing " + book.title);
    }

    private boolean canPlayAt(NarrationUi.BookInput book, int chapterIndex) {
        if (chapterIndex < 0 || chapterIndex >= book.chapters.size()) return false;
        try {
            NarrationSettings settings = new NarrationSettings(this);
            NarrationUi.ChapterInput chapter = book.chapters.get(chapterIndex);
            File audio = new AudioCache(this).fileFor(book.bookId, chapterIndex, chapter.text, settings.voice(), settings.style());
            return new AudioCache(this).isReady(audio);
        } catch (Exception ignored) { return false; }
    }

    private int offlineChapterCount(NarrationUi.BookInput book) {
        try {
            AudioCache cache = new AudioCache(this);
            NarrationSettings settings = new NarrationSettings(this);
            int ready = 0;
            for (int i = 0; i < book.chapters.size(); i++) {
                NarrationUi.ChapterInput chapter = book.chapters.get(i);
                File audio = cache.fileFor(book.bookId, i, chapter.text, settings.voice(), settings.style());
                if (cache.isReady(audio)) ready++;
            }
            return ready;
        } catch (Exception ignored) { return 0; }
    }

    private NarrationUi.BookInput findBook(String bookId) {
        if (bookId == null) return null;
        for (NarrationUi.BookInput book : books) if (bookId.equals(book.bookId)) return book;
        return null;
    }

    private boolean isIndexed(String fileName) {
        return bookIndex != null && bookIndex.load().containsKey(fileName);
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
    }

    private File libraryDir() {
        File dir = new File(getFilesDir(), "library");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File[] safeFiles(File dir) {
        File[] files = dir.listFiles();
        return files == null ? new File[0] : files;
    }

    private String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) >= 0) md.update(buffer, 0, n);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : md.digest()) result.append(String.format(Locale.US, "%02x", value));
        return result.toString();
    }

    private String displayName(Uri uri) {
        ContentResolver resolver = getContentResolver();
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
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

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.rgb(247, 244, 237));
        return layout;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(17), dp(17), dp(17), dp(17));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(255, 253, 249));
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.rgb(226, 219, 206));
        layout.setBackground(background);
        return layout;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setMinHeight(dp(56));
        button.setContentDescription(label);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(48, 48, 43));
        background.setCornerRadius(dp(16));
        button.setBackground(background);
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(Color.rgb(42, 42, 39));
        button.setMinHeight(dp(52));
        button.setContentDescription(label);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(239, 234, 224));
        background.setCornerRadius(dp(15));
        button.setBackground(background);
        return button;
    }

    private LinearLayout.LayoutParams fullButtonParams() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private TextView heading(String value, float size) {
        TextView view = text(value, size, Color.rgb(24, 27, 29), true);
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        return view;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
