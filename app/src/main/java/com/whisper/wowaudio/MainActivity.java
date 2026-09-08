package com.whisper.wowaudio;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

public class MainActivity extends Activity {
    private static final int PICK_EPUB = 1001;
    private static final String WOW_OPEN_BOOK = "com.whisper.wowaudio.action.OPEN_BOOK";
    private final List<Book> books = new ArrayList<>();
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
        if (!WOW_OPEN_BOOK.equals(action) && !Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_SEND.equals(action)) return;
        Uri uri = Intent.ACTION_SEND.equals(action) ? intent.getParcelableExtra(Intent.EXTRA_STREAM) : intent.getData();
        if (uri == null) return;
        importUri(uri, intent.getStringExtra("wow_book_title"), intent.getStringExtra("wow_book_author"), WOW_OPEN_BOOK.equals(action));
    }

    private void launchPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/epub+zip");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/epub+zip", "application/octet-stream"});
        startActivityForResult(i, PICK_EPUB);
    }

    private void importUri(Uri uri, String suppliedTitle, String suppliedAuthor, boolean fromReader) {
        new Thread(() -> {
            try {
                File imported = importToPrivateStorage(uri);
                Book parsed = parseBook(imported, suppliedTitle, suppliedAuthor);
                bookIndex.put(new BookIndex.Entry(imported.getName(), sha256(imported), parsed.title, parsed.author, imported.lastModified()));
                runOnUiThread(() -> {
                    loadLibrary();
                    Book target = findBook(imported);
                    if (target == null) target = parsed;
                    Toast.makeText(this, fromReader ? "Book received" : "EPUB imported", Toast.LENGTH_SHORT).show();
                    showBookDetail(target);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Unable to import this EPUB", Toast.LENGTH_LONG).show());
            }
        }, "wow-audio-import").start();
    }

    private File importToPrivateStorage(Uri uri) throws Exception {
        File dir = libraryDir();
        String display = displayName(uri);
        if (display == null || display.trim().isEmpty()) display = "Imported-book.epub";
        display = display.replaceAll("[\\\\/:*?\"<>|]", "_");
        File temp = File.createTempFile("import-", ".epub", getCacheDir());
        try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream fos = new FileOutputStream(temp)) {
            if (in == null) throw new Exception("No input stream");
            byte[] buf = new byte[64 * 1024]; int n;
            while ((n = in.read(buf)) >= 0) fos.write(buf, 0, n);
        }
        String hash = sha256(temp);
        for (File f : safeFiles(dir)) if (f.isFile() && sha256(f).equals(hash)) { temp.delete(); return f; }
        File out = uniqueFile(dir, display);
        if (!temp.renameTo(out)) {
            try (InputStream in = new java.io.FileInputStream(temp); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024]; int n; while ((n = in.read(buf)) >= 0) fos.write(buf, 0, n);
            }
            temp.delete();
        }
        return out;
    }

    private void loadLibrary() {
        books.clear();
        Map<String, BookIndex.Entry> index = bookIndex.load();
        for (File f : safeFiles(libraryDir())) {
            if (!f.isFile() || !f.getName().toLowerCase().endsWith(".epub")) continue;
            BookIndex.Entry saved = index.get(f.getName());
            String title = saved == null || empty(saved.title) ? null : saved.title;
            String author = saved == null || empty(saved.author) ? null : saved.author;
            try { books.add(parseBook(f, title, author)); }
            catch (Exception ignored) { books.add(Book.fallback(f, title, author)); }
        }
    }

    private Book parseBook(File file, String suppliedTitle, String suppliedAuthor) throws Exception {
        Book b = new Book(); b.file = file; b.fileName = file.getName();
        try (ZipFile zip = new ZipFile(file)) {
            Document container = xml(zip, "META-INF/container.xml");
            NodeList roots = container.getElementsByTagName("rootfile");
            if (roots.getLength() == 0) throw new Exception("No OPF");
            String opfPath = ((Element) roots.item(0)).getAttribute("full-path");
            Document opf = xml(zip, opfPath);
            b.title = firstText(opf, "dc:title");
            b.author = firstText(opf, "dc:creator");
            if (empty(b.title)) b.title = firstText(opf, "title");
            if (empty(b.author)) b.author = firstText(opf, "creator");
            if (!empty(suppliedTitle)) b.title = suppliedTitle;
            if (!empty(suppliedAuthor)) b.author = suppliedAuthor;

            Map<String, String> hrefs = new HashMap<>();
            Map<String, String> media = new HashMap<>();
            NodeList items = opf.getElementsByTagName("item");
            String coverHref = null;
            for (int i = 0; i < items.getLength(); i++) {
                Element e = (Element) items.item(i);
                String id = e.getAttribute("id");
                String href = e.getAttribute("href");
                hrefs.put(id, href);
                media.put(id, e.getAttribute("media-type"));
                if (e.getAttribute("properties").contains("cover-image") || id.toLowerCase().contains("cover")) coverHref = href;
            }
            if (coverHref != null) b.cover = readEntry(zip, EpubPath.resolve(opfPath, coverHref), 8 * 1024 * 1024);

            Map<String, String> navTitles = EpubNavigation.titles(zip, opf, opfPath);
            NodeList refs = opf.getElementsByTagName("itemref");
            int chapterNo = 1;
            for (int i = 0; i < refs.getLength(); i++) {
                Element ref = (Element) refs.item(i);
                String idref = ref.getAttribute("idref");
                String href = hrefs.get(idref);
                if (href == null) continue;
                String mt = media.get(idref);
                if (mt != null && !mt.contains("html") && !mt.contains("xhtml")) continue;
                String chapterPath = EpubPath.resolve(opfPath, href);
                byte[] raw = readEntry(zip, chapterPath, 4 * 1024 * 1024);
                if (raw == null) continue;
                String html = new String(raw, StandardCharsets.UTF_8);
                String text = htmlToText(html);
                if (text.length() < 2) continue;
                String heading = navTitles.get(chapterPath);
                if (empty(heading)) heading = extractHeading(html);
                if (empty(heading)) heading = "Chapter " + chapterNo;
                b.chapters.add(new Chapter(heading, text, chapterPath));
                chapterNo++;
            }
        }
        if (empty(b.title)) b.title = stripExtension(file.getName());
        if (empty(b.author)) b.author = "Unknown author";
        return b;
    }

    private void showLibrary() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column();
        root.setPadding(dp(22), dp(24), dp(22), dp(36));
        scroll.addView(root);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("WoW Audio", 30, Color.rgb(24, 27, 29), true);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button add = button("＋ Add Book");
        add.setOnClickListener(v -> launchPicker());
        header.addView(add);
        root.addView(header);
        TextView sub = text("Your private audiobook library", 13, Color.rgb(111, 108, 101), false);
        sub.setPadding(0, dp(3), 0, dp(28));
        root.addView(sub);
        if (books.isEmpty()) {
            LinearLayout hero = card();
            hero.setPadding(dp(22), dp(28), dp(22), dp(28));
            hero.addView(text("🎧", 38, Color.DKGRAY, false));
            TextView h = text("Turn EPUBs into listening", 22, Color.rgb(24, 27, 29), true);
            h.setPadding(0, dp(14), 0, dp(7));
            hero.addView(h);
            TextView p = text("Import an EPUB from Files, Downloads or Telegram. Your books stay in WoW Audio's private library.", 14, Color.rgb(91, 91, 87), false);
            p.setLineSpacing(0, 1.25f);
            hero.addView(p);
            Button cta = button("Import EPUB");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(20);
            hero.addView(cta, lp);
            cta.setOnClickListener(v -> launchPicker());
            root.addView(hero);
        } else {
            root.addView(text("Imported Books", 18, Color.rgb(24, 27, 29), true));
            for (Book b : books) root.addView(bookCard(b));
        }
        setContentView(scroll);
    }

    private View bookCard(Book b) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(-1, -2);
        outer.topMargin = dp(12);
        card.setLayoutParams(outer);
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bm = bitmap(b.cover);
        if (bm != null) cover.setImageBitmap(bm); else cover.setBackgroundColor(Color.rgb(224, 216, 201));
        card.addView(cover, new LinearLayout.LayoutParams(dp(72), dp(104)));
        LinearLayout meta = column();
        meta.setPadding(dp(16), 0, 0, 0);
        meta.addView(text(b.title, 17, Color.rgb(27, 29, 30), true));
        TextView a = text(b.author, 13, Color.rgb(105, 104, 99), false);
        a.setPadding(0, dp(5), 0, dp(8));
        meta.addView(a);
        meta.addView(text(b.chapters.size() + " chapters", 12, Color.rgb(126, 122, 113), false));
        card.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
        card.setOnClickListener(v -> showBookDetail(b));
        return card;
    }

    private void showBookDetail(Book b) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = column();
        root.setPadding(dp(22), dp(20), dp(22), dp(40));
        scroll.addView(root);
        TextView back = text("‹  Library", 15, Color.rgb(86, 78, 62), true);
        back.setPadding(0, dp(8), 0, dp(20));
        back.setOnClickListener(v -> { loadLibrary(); showLibrary(); });
        root.addView(back);
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bm = bitmap(b.cover);
        if (bm != null) cover.setImageBitmap(bm); else cover.setBackgroundColor(Color.rgb(224, 216, 201));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(154), dp(224));
        cp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(cover, cp);
        TextView title = text(b.title, 25, Color.rgb(24, 27, 29), true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(20), 0, dp(6));
        root.addView(title);
        TextView author = text(b.author, 14, Color.rgb(103, 101, 95), false);
        author.setGravity(Gravity.CENTER);
        root.addView(author);
        Button narrate = button("Set up narration");
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(22);
        root.addView(narrate, np);
        narrate.setOnClickListener(v -> Toast.makeText(this, "Gemini BYOK narration is the next phase", Toast.LENGTH_SHORT).show());
        TextView ch = text("Chapters", 18, Color.rgb(24, 27, 29), true);
        ch.setPadding(0, dp(28), 0, dp(8));
        root.addView(ch);
        if (b.chapters.isEmpty()) root.addView(text("No readable chapters found", 13, Color.rgb(112, 109, 103), false));
        for (int i = 0; i < b.chapters.size(); i++) {
            Chapter c = b.chapters.get(i);
            LinearLayout row = card();
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.topMargin = dp(9);
            row.setLayoutParams(rp);
            row.addView(text((i + 1) + "  " + c.title, 15, Color.rgb(35, 36, 36), true));
            String preview = c.text.length() > 110 ? c.text.substring(0, 110) + "…" : c.text;
            TextView pv = text(preview, 12, Color.rgb(112, 109, 103), false);
            pv.setPadding(0, dp(6), 0, 0);
            pv.setLineSpacing(0, 1.2f);
            row.addView(pv);
            root.addView(row);
        }
        setContentView(scroll);
    }

    private Document xml(ZipFile zip, String path) throws Exception {
        ZipEntry e = zip.getEntry(normalize(path));
        if (e == null) throw new Exception("Missing " + path);
        try (InputStream in = zip.getInputStream(e)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
            return f.newDocumentBuilder().parse(in);
        }
    }

    private byte[] readEntry(ZipFile zip, String path, int max) throws Exception {
        if (path == null) return null;
        ZipEntry e = zip.getEntry(path);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n, total = 0;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > max) break;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private String firstText(Document d, String tag) {
        NodeList n = d.getElementsByTagName(tag);
        if (n.getLength() == 0 && tag.contains(":")) n = d.getElementsByTagName(tag.substring(tag.indexOf(':') + 1));
        return n.getLength() > 0 ? n.item(0).getTextContent().trim() : null;
    }

    private String extractHeading(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").matcher(html);
        return m.find() ? htmlToText(m.group(1)) : null;
    }

    private String htmlToText(String html) {
        return html.replaceAll("(?is)<script.*?</script>|<style.*?</style>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
    }

    private String normalize(String p) { try { return Uri.decode(p).replace("\\", "/"); } catch (Exception e) { return p; } }
    private Bitmap bitmap(byte[] data) { try { return data == null ? null : BitmapFactory.decodeByteArray(data, 0, data.length); } catch (Exception e) { return null; } }
    private File libraryDir() { File d = new File(getFilesDir(), "library"); if (!d.exists()) d.mkdirs(); return d; }
    private File[] safeFiles(File d) { File[] f = d.listFiles(); return f == null ? new File[0] : f; }
    private Book findBook(File f) { for (Book b : books) if (b.file.getAbsolutePath().equals(f.getAbsolutePath())) return b; return null; }

    private String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest()) s.append(String.format("%02x", x));
        return s.toString();
    }

    private String displayName(Uri uri) {
        ContentResolver r = getContentResolver();
        try (Cursor c = r.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment();
    }

    private File uniqueFile(File dir, String name) {
        File c = new File(dir, name);
        if (!c.exists()) return c;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            c = new File(dir, base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return new File(dir, System.currentTimeMillis() + "-" + name);
    }

    private boolean empty(String s) { return s == null || s.trim().isEmpty(); }
    private String stripExtension(String n) { int d = n.lastIndexOf('.'); return d > 0 ? n.substring(0, d) : n; }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackgroundColor(Color.rgb(247, 244, 237));
        return v;
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(17), dp(17), dp(17), dp(17));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(255, 253, 249));
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), Color.rgb(226, 219, 206));
        v.setBackground(bg);
        v.setElevation(dp(1));
        return v;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(48, 48, 43));
        bg.setCornerRadius(dp(18));
        b.setBackground(bg);
        return b;
    }

    private TextView text(String s, float z, int c, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(z);
        v.setTextColor(c);
        v.setGravity(Gravity.START);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    static class Book {
        File file;
        String fileName, title, author;
        byte[] cover;
        List<Chapter> chapters = new ArrayList<>();
        static Book fallback(File f, String savedTitle, String savedAuthor) {
            Book b = new Book();
            b.file = f;
            b.fileName = f.getName();
            b.title = savedTitle == null || savedTitle.trim().isEmpty() ? f.getName().replaceFirst("(?i)\\.epub$", "") : savedTitle;
            b.author = savedAuthor == null || savedAuthor.trim().isEmpty() ? "Unknown author" : savedAuthor;
            return b;
        }
    }

    static class Chapter {
        String title, text, path;
        Chapter(String t, String x, String p) { title = t; text = x; path = p; }
    }
}
