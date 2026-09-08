package com.whisper.wowaudio;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.StatFs;

final class GenerationEnvironment {
    private static final long STORAGE_RESERVE_BYTES = 192L * 1024L * 1024L;

    private GenerationEnvironment() { }

    static boolean hasNetwork(Context context) {
        if (context == null) return false;
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            Network network = manager.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception ignored) {
            return true;
        }
    }

    static boolean hasStorageHeadroom(Context context) {
        return availableBytes(context) >= STORAGE_RESERVE_BYTES;
    }

    static long availableBytes(Context context) {
        if (context == null) return 0;
        try {
            return new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
        } catch (Exception ignored) {
            return Long.MAX_VALUE;
        }
    }

    static String availableStorageText(Context context) {
        long bytes = availableBytes(context);
        if (bytes == Long.MAX_VALUE) return "unknown";
        long mb = Math.max(0, bytes / (1024L * 1024L));
        return mb >= 1024 ? String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0) : mb + " MB";
    }
}