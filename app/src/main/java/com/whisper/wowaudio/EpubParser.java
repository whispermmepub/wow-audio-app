package com.whisper.wowaudio;

import android.os.Build;
import android.text.Html;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

final class EpubParser {
    static final class Result {
        final String title;
        final String author;
        final String text;
        Result(String title, String author, String text) {
            this.title = title;
            this.author = author;
            this.text = text;
        }
    }

    static Result parse(File epub) throws Exception {
        try (ZipFile zip = new ZipFile(epub)) {
            String opfPath = findOpf(zip);
            byte[] opfBytes = read(zip, opfPath);
            Document opf = xml(opfBytes);

            String title = firstText(opf, "title");
            String author = firstText(opf, "creator");
            String opfDir = parent(opfPath);

            Map<String, String> manifest = new LinkedHashMap<>();
            NodeList items = opf.getElementsByTagNameNS("*", "item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String id = item.getAttribute("id");
                String href = item.getAttribute("href");
                if (!id.isEmpty() && !href.isEmpty()) manifest.put(id, resolve(opfDir, href));
            }

            List<String> ordered = new ArrayList<>();
            NodeList refs = opf.getElementsByTagNameNS("*", "itemref");
            for (int i = 0; i < refs.getLength(); i++) {
                Element ref = (Element) refs.item(i);
                String path = manifest.get(ref.getAttribute("idref"));
                if (path != null) ordered.add(path);
            }

            if (ordered.isEmpty()) {
                for (String path : manifest.values()) {
                    String lower = path.toLowerCase(Locale.US);
                    if (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")) {
                        ordered.add(path);
                    }
                }
            }

            StringBuilder text = new StringBuilder();
            for (String path : ordered) {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null || entry.isDirectory()) continue;
                String html = new String(read(zip, path), StandardCharsets.UTF_8);
                html = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                           .replaceAll("(?is)<style[^>]*>.*?</style>", " ");
                String plain;
                if (Build.VERSION.SDK_INT >= 24) {
                    plain = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString();
                } else {
                    //noinspection deprecation
                    plain = Html.fromHtml(html).toString();
                }
                plain = plain.replaceAll("[ \\t]+", " ")
                             .replaceAll("\\n[ \\t]+", "\\n")
                             .replaceAll("\\n{3,}", "\\n\\n")
                             .trim();
                if (!plain.isEmpty()) {
                    if (text.length() > 0) text.append("\n\n");
                    text.append(plain);
                }
            }

            if (text.length() == 0) throw new IllegalArgumentException("EPUB contains no readable text.");
            return new Result(title, author, text.toString());
        }
    }

    private static String findOpf(ZipFile zip) throws Exception {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container == null) throw new IllegalArgumentException("Invalid EPUB: container.xml missing.");
        Document doc = xml(read(zip, "META-INF/container.xml"));
        NodeList roots = doc.getElementsByTagNameNS("*", "rootfile");
        if (roots.getLength() == 0) throw new IllegalArgumentException("Invalid EPUB: package document missing.");
        String path = ((Element) roots.item(0)).getAttribute("full-path");
        if (path.isEmpty()) throw new IllegalArgumentException("Invalid EPUB package path.");
        return path;
    }

    private static String firstText(Document doc, String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) return "";
        String value = nodes.item(0).getTextContent();
        return value == null ? "" : value.trim();
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
            byte[] buffer = new byte[32768];
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
        String clean = href;
        int hash = clean.indexOf('#');
        if (hash >= 0) clean = clean.substring(0, hash);
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        try { clean = URLDecoder.decode(clean, "UTF-8"); } catch (Exception ignored) { }
        String combined = clean.startsWith("/") ? clean.substring(1) : base + clean;
        String[] parts = combined.split("/");
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!normalized.isEmpty()) normalized.remove(normalized.size() - 1);
            } else normalized.add(part);
        }
        return String.join("/", normalized);
    }
}
