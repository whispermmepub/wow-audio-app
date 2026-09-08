package com.whisper.wowaudio;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

final class EpubNavigation {
    private EpubNavigation() {}

    static Map<String, String> titles(ZipFile zip, Document opf, String opfPath) {
        Map<String, String> result = new LinkedHashMap<>();
        try { parseEpub3Nav(zip, opf, opfPath, result); } catch (Exception ignored) { }
        try { parseNcx(zip, opf, opfPath, result); } catch (Exception ignored) { }
        return result;
    }

    private static void parseEpub3Nav(ZipFile zip, Document opf, String opfPath, Map<String, String> out) throws Exception {
        NodeList items = opf.getElementsByTagName("item");
        String navHref = null;
        for (int i = 0; i < items.getLength(); i++) {
            Element e = (Element) items.item(i);
            String properties = e.getAttribute("properties");
            if (properties != null && containsToken(properties, "nav")) {
                navHref = e.getAttribute("href");
                break;
            }
        }
        if (empty(navHref)) return;
        String navPath = EpubPath.resolve(opfPath, navHref);
        byte[] raw = read(zip, navPath, 2 * 1024 * 1024);
        if (raw == null) return;
        String html = new String(raw, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?is)<a\\b[^>]*href\\s*=\\s*(['\"])(.*?)\\1[^>]*>(.*?)</a>").matcher(html);
        while (m.find()) {
            String href = m.group(2);
            String title = text(m.group(3));
            String target = EpubPath.resolve(navPath, href);
            if (!empty(target) && !empty(title) && !out.containsKey(target)) out.put(target, title);
        }
    }

    private static void parseNcx(ZipFile zip, Document opf, String opfPath, Map<String, String> out) throws Exception {
        NodeList items = opf.getElementsByTagName("item");
        String ncxHref = null;
        String tocId = null;
        NodeList spines = opf.getElementsByTagName("spine");
        if (spines.getLength() > 0) tocId = ((Element) spines.item(0)).getAttribute("toc");
        for (int i = 0; i < items.getLength(); i++) {
            Element e = (Element) items.item(i);
            String id = e.getAttribute("id");
            String mediaType = e.getAttribute("media-type");
            if ((!empty(tocId) && tocId.equals(id)) || "application/x-dtbncx+xml".equals(mediaType)) {
                ncxHref = e.getAttribute("href");
                if (!empty(tocId) && tocId.equals(id)) break;
            }
        }
        if (empty(ncxHref)) return;
        String ncxPath = EpubPath.resolve(opfPath, ncxHref);
        ZipEntry entry = zip.getEntry(ncxPath);
        if (entry == null) return;
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        Document ncx;
        try (InputStream in = zip.getInputStream(entry)) { ncx = f.newDocumentBuilder().parse(in); }
        NodeList points = ncx.getElementsByTagName("navPoint");
        for (int i = 0; i < points.getLength(); i++) {
            Element point = (Element) points.item(i);
            Element content = firstDescendant(point, "content");
            Element label = firstDescendant(point, "text");
            if (content == null || label == null) continue;
            String target = EpubPath.resolve(ncxPath, content.getAttribute("src"));
            String title = label.getTextContent() == null ? "" : label.getTextContent().trim();
            if (!empty(target) && !empty(title) && !out.containsKey(target)) out.put(target, title);
        }
    }

    private static Element firstDescendant(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element) return (Element) n;
        }
        return null;
    }

    private static byte[] read(ZipFile zip, String path, int max) throws Exception {
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

    private static boolean containsToken(String values, String token) {
        for (String value : values.trim().split("\\s+")) if (token.equals(value)) return true;
        return false;
    }

    private static String text(String html) {
        return html.replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("\\s+", " ").trim();
    }

    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
}
