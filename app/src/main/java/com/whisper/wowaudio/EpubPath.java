package com.whisper.wowaudio;

import android.net.Uri;

import java.util.ArrayDeque;
import java.util.Deque;

final class EpubPath {
    private EpubPath() {}

    static String resolve(String baseFile, String href) {
        if (href == null) return null;
        String clean = href;
        int hash = clean.indexOf('#');
        if (hash >= 0) clean = clean.substring(0, hash);
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        clean = Uri.decode(clean).replace('\\', '/');
        String base = "";
        if (baseFile != null) {
            String b = baseFile.replace('\\', '/');
            int slash = b.lastIndexOf('/');
            if (slash >= 0) base = b.substring(0, slash + 1);
        }
        String joined = clean.startsWith("/") ? clean.substring(1) : base + clean;
        Deque<String> parts = new ArrayDeque<>();
        for (String part : joined.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) { if (!parts.isEmpty()) parts.removeLast(); }
            else parts.addLast(part);
        }
        return String.join("/", parts);
    }
}
