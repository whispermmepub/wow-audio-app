package com.whisper.wowaudio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

/** Extracts and caches an EPUB cover without changing the stored book format. */
final class BookCover {
    private BookCover() { }

    static Bitmap load(BookStore.Book book) {
        if (book == null) return null;
        File cached = new File(book.directory, "cover-cache.bin");
        Bitmap bitmap = decode(cached);
        if (bitmap != null) return bitmap;
        if (!"epub".equals(book.type)) return null;
        File epub = new File(book.directory, "source.epub");
        if (!epub.isFile()) return null;
        try {
            byte[] bytes = extract(epub);
            if (bytes == null || bytes.length < 128 || bytes.length > 12 * 1024 * 1024) return null;
            try (FileOutputStream out = new FileOutputStream(cached)) {
                out.write(bytes);
                out.getFD().sync();
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Bitmap decode(File file) {
        if (file == null || !file.isFile()) return null;
        try { return BitmapFactory.decodeFile(file.getAbsolutePath()); }
        catch (Exception ignored) { return null; }
    }

    private static byte[] extract(File epub) throws Exception {
        try (ZipFile zip = new ZipFile(epub)) {
            String opfPath = findOpf(zip);
            Document opf = xml(read(zip, opfPath));
            String opfDir = parent(opfPath);

            Map<String, String> byId = new HashMap<>();
            String propertiesCover = null;
            String filenameCover = null;
            NodeList items = opf.getElementsByTagNameNS("*", "item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String id = item.getAttribute("id");
                String href = item.getAttribute("href");
                String media = item.getAttribute("media-type");
                String properties = item.getAttribute("properties");
                if (id.isEmpty() || href.isEmpty()) continue;
                String path = resolve(opfDir, href);
                byId.put(id, path);
                if (properties.contains("cover-image")) propertiesCover = path;
                String lower = path.toLowerCase(Locale.US);
                if (filenameCover == null && media.startsWith("image/") && lower.contains("cover")) {
                    filenameCover = path;
                }
            }

            String metadataCoverId = null;
            NodeList meta = opf.getElementsByTagNameNS("*", "meta");
            for (int i = 0; i < meta.getLength(); i++) {
                Element e = (Element) meta.item(i);
                if ("cover".equalsIgnoreCase(e.getAttribute("name"))) {
                    metadataCoverId = e.getAttribute("content");
                    if (!metadataCoverId.isEmpty()) break;
                }
            }

            String cover = propertiesCover;
            if (cover == null && metadataCoverId != null) cover = byId.get(metadataCoverId);
            if (cover == null) cover = filenameCover;
            if (cover == null) return null;
            ZipEntry entry = zip.getEntry(cover);
            if (entry == null || entry.isDirectory()) return null;
            try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[32 * 1024];
                int total = 0;
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    total += n;
                    if (total > 12 * 1024 * 1024) return null;
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        }
    }

    private static String findOpf(ZipFile zip) throws Exception {
        Document doc = xml(read(zip, "META-INF/container.xml"));
        NodeList roots = doc.getElementsByTagNameNS("*", "rootfile");
        if (roots.getLength() == 0) throw new IllegalArgumentException("EPUB package missing.");
        return ((Element) roots.item(0)).getAttribute("full-path");
    }

    private static Document xml(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) { }
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) { }
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static byte[] read(ZipFile zip, String path) throws Exception {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) throw new IllegalArgumentException("EPUB entry missing: " + path);
        try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    private static String resolve(String base, String href) {
        String clean = href == null ? "" : href;
        int hash = clean.indexOf('#');
        if (hash >= 0) clean = clean.substring(0, hash);
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        try { clean = URLDecoder.decode(clean, "UTF-8"); } catch (Exception ignored) { }
        String combined = clean.startsWith("/") ? clean.substring(1) : base + clean;
        java.util.ArrayList<String> normalized = new java.util.ArrayList<>();
        for (String part : combined.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!normalized.isEmpty()) normalized.remove(normalized.size() - 1);
            } else normalized.add(part);
        }
        StringBuilder out = new StringBuilder();
        for (String part : normalized) {
            if (out.length() > 0) out.append('/');
            out.append(part);
        }
        return out.toString();
    }
}
