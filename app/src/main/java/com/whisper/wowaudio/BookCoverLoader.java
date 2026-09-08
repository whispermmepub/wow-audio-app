package com.whisper.wowaudio;

import android.content.Context;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

final class BookCoverLoader {
    private BookCoverLoader() { }

    static byte[] load(Context context, String bookId) {
        if (context == null || bookId == null || bookId.trim().isEmpty()) return null;
        File file = new File(new File(context.getFilesDir(), "library"), bookId);
        if (!file.isFile()) return null;
        try (ZipFile zip = new ZipFile(file)) {
            Document container = xml(zip, "META-INF/container.xml");
            NodeList roots = container.getElementsByTagName("rootfile");
            if (roots.getLength() == 0) return null;
            String opfPath = ((Element) roots.item(0)).getAttribute("full-path");
            Document opf = xml(zip, opfPath);
            String coverHref = null;
            NodeList items = opf.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element e = (Element) items.item(i);
                String id = e.getAttribute("id");
                String properties = e.getAttribute("properties");
                String media = e.getAttribute("media-type");
                if ((properties != null && properties.contains("cover-image")) ||
                        (id != null && id.toLowerCase(java.util.Locale.US).contains("cover") && media != null && media.startsWith("image/"))) {
                    coverHref = e.getAttribute("href");
                    if (properties != null && properties.contains("cover-image")) break;
                }
            }
            if (coverHref == null || coverHref.isEmpty()) return null;
            return read(zip, EpubPath.resolve(opfPath, coverHref), 8 * 1024 * 1024);
        } catch (Exception ignored) { return null; }
    }

    private static Document xml(ZipFile zip, String path) throws Exception {
        ZipEntry e = zip.getEntry(path);
        if (e == null) throw new Exception("Missing XML");
        try (InputStream in = zip.getInputStream(e)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
            return f.newDocumentBuilder().parse(in);
        }
    }

    private static byte[] read(ZipFile zip, String path, int max) throws Exception {
        if (path == null) return null;
        ZipEntry e = zip.getEntry(path);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n, total = 0;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
                if (total > max) return null;
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
