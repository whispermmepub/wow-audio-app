package com.whisper.wowaudio;

import android.content.Context;
import android.net.Uri;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

final class NarrationSourceLoader {
    private NarrationSourceLoader() { }

    static NarrationUi.BookInput load(Context context, String bookId) throws Exception {
        if (context == null || empty(bookId)) throw new Exception("Book is missing");
        File file = new File(new File(context.getFilesDir(), "library"), bookId);
        if (!file.isFile()) throw new Exception("Book file is missing");

        String savedTitle = null;
        String savedAuthor = null;
        BookIndex.Entry saved = new BookIndex(context).load().get(bookId);
        if (saved != null) {
            savedTitle = saved.title;
            savedAuthor = saved.author;
        }

        String title = savedTitle;
        String author = savedAuthor;
        byte[] cover = null;
        List<NarrationUi.ChapterInput> chapters = new ArrayList<>();

        try (ZipFile zip = new ZipFile(file)) {
            Document container = xml(zip, "META-INF/container.xml");
            NodeList roots = container.getElementsByTagName("rootfile");
            if (roots.getLength() == 0) throw new Exception("EPUB package document not found");
            String opfPath = ((Element) roots.item(0)).getAttribute("full-path");
            Document opf = xml(zip, opfPath);

            if (empty(title)) title = firstText(opf, "dc:title");
            if (empty(author)) author = firstText(opf, "dc:creator");
            if (empty(title)) title = firstText(opf, "title");
            if (empty(author)) author = firstText(opf, "creator");

            Map<String, String> hrefs = new HashMap<>();
            Map<String, String> media = new HashMap<>();
            NodeList items = opf.getElementsByTagName("item");
            String coverHref = null;
            for (int i = 0; i < items.getLength(); i++) {
                Element e = (Element) items.item(i);
                String id = e.getAttribute("id");
                String href = e.getAttribute("href");
                String mediaType = e.getAttribute("media-type");
                hrefs.put(id, href);
                media.put(id, mediaType);
                String properties = e.getAttribute("properties");
                if ((properties != null && properties.contains("cover-image")) ||
                        (coverHref == null && id != null && id.toLowerCase(java.util.Locale.US).contains("cover") && mediaType != null && mediaType.startsWith("image/"))) {
                    coverHref = href;
                }
            }
            if (!empty(coverHref)) cover = readEntry(zip, EpubPath.resolve(opfPath, coverHref), 8 * 1024 * 1024);

            Map<String, String> navTitles = EpubNavigation.titles(zip, opf, opfPath);
            NodeList refs = opf.getElementsByTagName("itemref");
            int chapterNo = 1;
            for (int i = 0; i < refs.getLength(); i++) {
                Element ref = (Element) refs.item(i);
                String idref = ref.getAttribute("idref");
                String href = hrefs.get(idref);
                if (href == null) continue;
                String mediaType = media.get(idref);
                if (mediaType != null && !mediaType.contains("html") && !mediaType.contains("xhtml")) continue;
                String chapterPath = EpubPath.resolve(opfPath, href);
                byte[] raw = readEntry(zip, chapterPath, 4 * 1024 * 1024);
                if (raw == null) continue;
                String html = new String(raw, StandardCharsets.UTF_8);
                String chapterText = htmlToText(html);
                if (chapterText.length() < 2) continue;
                String heading = navTitles.get(chapterPath);
                if (empty(heading)) heading = extractHeading(html);
                if (empty(heading)) heading = "Chapter " + chapterNo;
                chapters.add(new NarrationUi.ChapterInput(heading, chapterText));
                chapterNo++;
            }
        }

        if (empty(title)) title = stripExtension(file.getName());
        if (empty(author)) author = "Unknown author";
        return new NarrationUi.BookInput(bookId, title, author, cover, chapters);
    }

    private static Document xml(ZipFile zip, String path) throws Exception {
        ZipEntry e = zip.getEntry(normalize(path));
        if (e == null) throw new Exception("Missing " + path);
        try (InputStream in = zip.getInputStream(e)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
            return f.newDocumentBuilder().parse(in);
        }
    }

    private static byte[] readEntry(ZipFile zip, String path, int max) throws Exception {
        if (path == null) return null;
        ZipEntry e = zip.getEntry(path);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n, total = 0;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > max) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String firstText(Document d, String tag) {
        NodeList n = d.getElementsByTagName(tag);
        if (n.getLength() == 0 && tag.contains(":")) n = d.getElementsByTagName(tag.substring(tag.indexOf(':') + 1));
        return n.getLength() > 0 ? n.item(0).getTextContent().trim() : null;
    }

    private static String extractHeading(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").matcher(html);
        return m.find() ? htmlToText(m.group(1)) : null;
    }

    private static String htmlToText(String html) {
        return html.replaceAll("(?is)<script.*?</script>|<style.*?</style>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
    }

    private static String normalize(String p) { try { return Uri.decode(p).replace("\\", "/"); } catch (Exception e) { return p; } }
    private static String stripExtension(String n) { int d = n.lastIndexOf('.'); return d > 0 ? n.substring(0, d) : n; }
    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
}
