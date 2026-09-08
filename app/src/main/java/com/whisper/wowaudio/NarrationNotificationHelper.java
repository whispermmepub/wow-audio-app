package com.whisper.wowaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class NarrationNotificationHelper {
    static final String CHANNEL = "wow_audio_generation";
    static final int NOTIFICATION_ID = 4201;

    private NarrationNotificationHelper() { }

    static void ensureChannel(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Audiobook preparation", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progress while WoW Audio prepares imported books for offline listening");
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    static Notification build(Context context, String title, String text, int progress, int max, boolean ongoing) {
        ensureChannel(context);
        Intent open = new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(context, 0, open, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title == null || title.trim().isEmpty() ? "WoW Audio" : title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (max > 0) builder.setProgress(max, Math.max(0, Math.min(max, progress)), false);
        else if (ongoing) builder.setProgress(0, 0, true);
        return builder.build();
    }

    static void notify(Context context, String title, String text, int progress, int max, boolean ongoing) {
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, build(context, title, text, progress, max, ongoing));
    }
}