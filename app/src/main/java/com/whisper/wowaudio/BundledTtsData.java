package com.whisper.wowaudio;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class BundledTtsData {
    private static final String VERSION = "espeak-ng-9eaee8bc62e9";

    private BundledTtsData() { }

    static File ensureInstalled(Context context) throws Exception {
        File parent = context.getDir("voices", Context.MODE_PRIVATE);
        File data = new File(parent, "espeak-ng-data");
        File marker = new File(parent, ".wow-espeak-version");
        if (data.isDirectory() && marker.isFile() && VERSION.equals(readSmall(marker))) return parent;

        deleteRecursively(data);
        if (!data.mkdirs() && !data.isDirectory()) throw new IllegalStateException("Could not prepare Burmese voice data");

        try (InputStream raw = context.getAssets().open("espeakdata.zip");
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            String root = parent.getCanonicalPath() + File.separator;
            byte[] buffer = new byte[32 * 1024];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(parent, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(root)) throw new SecurityException("Unsafe voice-data entry");
                if (entry.isDirectory()) {
                    if (!out.mkdirs() && !out.isDirectory()) throw new IllegalStateException("Could not create voice-data directory");
                } else {
                    File p = out.getParentFile();
                    if (p != null && !p.mkdirs() && !p.isDirectory()) throw new IllegalStateException("Could not create voice-data directory");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) fos.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        } catch (Exception e) {
            deleteRecursively(data);
            throw e;
        }

        File mustExist = new File(data, "phondata");
        if (!mustExist.isFile()) {
            deleteRecursively(data);
            throw new IllegalStateException("Bundled Burmese voice data is incomplete");
        }
        try (FileOutputStream out = new FileOutputStream(marker, false)) {
            out.write(VERSION.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return parent;
    }

    private static String readSmall(File file) {
        try {
            byte[] bytes = new byte[(int) Math.min(256, file.length())];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int n = in.read(bytes);
                return n <= 0 ? "" : new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
