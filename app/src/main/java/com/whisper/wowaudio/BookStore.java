package com.whisper.wowaudio;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class BookStore {
    static final class Book {
        final String id;
        final String title;
        final String author;
        final String originalName;
        final String type;
        final long addedAt;
        final File directory;
        final File textFile;

        Book(String id, String title, String author, String originalName, String type,
             long addedAt, File directory) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.originalName = originalName;
            this.type = type;
            this.addedAt = addedAt;
            this.directory = directory;
            this.textFile = new File(directory, "text.txt");
        }
    }

    private final Context context;
    private final File root;

    BookStore(Context context) {
        this.context = context.getApplicationContext();
        this.root = new File(this.context.getFilesDir(), "library");
        if (!root.exists()) root.mkdirs();
    }

    Book importBook(Uri uri) throws Exception {
        String name = displayName(uri);
        if (name == null || name.trim().isEmpty()) name = "book.epub";
        String lower = name.toLowerCase(Locale.US);
        String type;
        if (lower.endsWith(".epub")) type = "epub";
        else if (lower.endsWith(".txt")) type = "txt";
        else {
            String mime = context.getContentResolver().getType(uri);
            if ("application/epub+zip".equals(mime)) type = "epub";
            else if (mime != null && mime.startsWith("text/")) type = "txt";
            else throw new IllegalArgumentException("Only EPUB and TXT files are supported in this clean build.");
        }

        String id = UUID.randomUUID().toString();
        File dir = new File(root, id);
        if (!dir.mkdirs()) throw new IllegalStateException("Cannot create private book folder.");

        try {
            File source = new File(dir, type.equals("epub") ? "source.epub" : "source.txt");
            copyUri(uri, source);

            String title = stripExtension(name);
            String author = "";
            String text;
            if (type.equals("epub")) {
                EpubParser.Result parsed = EpubParser.parse(source);
                if (!empty(parsed.title)) title = parsed.title.trim();
                if (!empty(parsed.author)) author = parsed.author.trim();
                text = parsed.text;
            } else {
                text = readUtf8(source);
            }

            text = cleanText(text);
            if (text.length() < 2) throw new IllegalArgumentException("This file does not contain readable text.");
            writeUtf8(new File(dir, "text.txt"), text);

            long now = System.currentTimeMillis();
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("title", title);
            json.put("author", author);
            json.put("originalName", name);
            json.put("type", type);
            json.put("addedAt", now);
            writeUtf8(new File(dir, "book.json"), json.toString());
            return new Book(id, title, author, name, type, now, dir);
        } catch (Exception e) {
            deleteRecursive(dir);
            throw e;
        }
    }

    List<Book> list() {
        List<Book> out = new ArrayList<>();
        File[] dirs = root.listFiles();
        if (dirs == null) return out;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            Book book = readBook(dir);
            if (book != null && book.textFile.isFile()) out.add(book);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.addedAt, a.addedAt));
        return out;
    }

    Book get(String id) {
        if (id == null || id.isEmpty()) return null;
        return readBook(new File(root, id));
    }

    String readText(Book book) throws Exception {
        return readUtf8(book.textFile);
    }

    boolean delete(String id) {
        if (id == null || id.isEmpty()) return false;
        return deleteRecursive(new File(root, id));
    }

    private Book readBook(File dir) {
        File meta = new File(dir, "book.json");
        if (!meta.isFile()) return null;
        try {
            JSONObject json = new JSONObject(readUtf8(meta));
            return new Book(
                    json.optString("id", dir.getName()),
                    json.optString("title", "Book"),
                    json.optString("author", ""),
                    json.optString("originalName", ""),
                    json.optString("type", ""),
                    json.optLong("addedAt", dir.lastModified()),
                    dir);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void copyUri(Uri uri, File target) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        try (InputStream in = resolver.openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IllegalArgumentException("Cannot open selected file.");
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            out.getFD().sync();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment();
    }

    private static String readUtf8(File file) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int n;
            while ((n = reader.read(buffer)) >= 0) out.append(buffer, 0, n);
        }
        return out.toString();
    }

    private static void writeUtf8(File file, String value) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    private static String cleanText(String value) {
        if (value == null) return "";
        return value.replace('\u00a0', ' ')
                .replace("\u200B", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n[ \\t]+", "\\n")
                .replaceAll("\\n{3,}", "\\n\\n")
                .trim();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean deleteRecursive(File file) {
        if (!file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        return file.delete();
    }
}
