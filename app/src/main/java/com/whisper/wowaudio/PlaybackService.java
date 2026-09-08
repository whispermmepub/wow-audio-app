package com.whisper.wowaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

import java.io.File;

public class PlaybackService extends Service {
    static final String ACTION_PLAY_FILE = "com.whisper.wowaudio.PLAY_FILE";
    static final String ACTION_TOGGLE = "com.whisper.wowaudio.TOGGLE";
    static final String ACTION_BACK = "com.whisper.wowaudio.BACK_15";
    static final String ACTION_FORWARD = "com.whisper.wowaudio.FORWARD_15";
    static final String ACTION_STOP = "com.whisper.wowaudio.STOP";
    static final String EXTRA_PATH = "path";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_AUTHOR = "author";
    private static final String CHANNEL = "wow_audio_playback";
    private static final int NOTIFICATION_ID = 4101;

    private MediaPlayer player;
    private MediaSession session;
    private String title = "WoW Audio";
    private String author = "";

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        player.setOnCompletionListener(mp -> {
            updateState(PlaybackState.STATE_STOPPED);
            stopForeground(false);
        });
        session = new MediaSession(this, "WoWAudioPlayback");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSeekTo(long pos) { seek((int) pos); }
            @Override public void onRewind() { jump(-15000); }
            @Override public void onFastForward() { jump(15000); }
        });
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setActive(true);
        updateState(PlaybackState.STATE_NONE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        try {
            if (ACTION_PLAY_FILE.equals(action)) {
                playFile(intent.getStringExtra(EXTRA_PATH), intent.getStringExtra(EXTRA_TITLE), intent.getStringExtra(EXTRA_AUTHOR));
            } else if (ACTION_TOGGLE.equals(action)) {
                if (player.isPlaying()) pause(); else resume();
            } else if (ACTION_BACK.equals(action)) {
                jump(-15000);
            } else if (ACTION_FORWARD.equals(action)) {
                jump(15000);
            } else if (ACTION_STOP.equals(action)) {
                stopPlayback();
            }
        } catch (Exception ignored) { }
        return START_NOT_STICKY;
    }

    private void playFile(String path, String newTitle, String newAuthor) throws Exception {
        if (path == null || !new File(path).isFile()) return;
        title = empty(newTitle) ? "WoW Audio" : newTitle;
        author = empty(newAuthor) ? "" : newAuthor;
        player.reset();
        player.setDataSource(path);
        player.prepare();
        player.start();
        MediaMetadata metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, author)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, player.getDuration())
                .build();
        session.setMetadata(metadata);
        updateState(PlaybackState.STATE_PLAYING);
        startForeground(NOTIFICATION_ID, notification());
    }

    private void resume() {
        if (player == null) return;
        try {
            player.start();
            updateState(PlaybackState.STATE_PLAYING);
            startForeground(NOTIFICATION_ID, notification());
        } catch (Exception ignored) { }
    }

    private void pause() {
        if (player == null) return;
        try {
            if (player.isPlaying()) player.pause();
            updateState(PlaybackState.STATE_PAUSED);
            notifyNow();
        } catch (Exception ignored) { }
    }

    private void stopPlayback() {
        try { if (player != null) player.stop(); } catch (Exception ignored) { }
        updateState(PlaybackState.STATE_STOPPED);
        stopForeground(true);
        stopSelf();
    }

    private void jump(int delta) {
        if (player == null) return;
        try { seek(player.getCurrentPosition() + delta); } catch (Exception ignored) { }
    }

    private void seek(int position) {
        if (player == null) return;
        try {
            int duration = player.getDuration();
            int target = Math.max(0, Math.min(duration, position));
            player.seekTo(target);
            updateState(player.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
        } catch (Exception ignored) { }
    }

    private void updateState(int state) {
        long pos = 0;
        try { if (player != null) pos = player.getCurrentPosition(); } catch (Exception ignored) { }
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE |
                PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, pos, state == PlaybackState.STATE_PLAYING ? 1f : 0f)
                .build());
    }

    private Notification notification() {
        boolean playing = false;
        try { playing = player != null && player.isPlaying(); } catch (Exception ignored) { }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, pendingFlags());
        PendingIntent back = serviceAction(ACTION_BACK, 1);
        PendingIntent toggle = serviceAction(ACTION_TOGGLE, 2);
        PendingIntent forward = serviceAction(ACTION_FORWARD, 3);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(author)
                .setContentIntent(content)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_rew, "15s", back)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", toggle)
                .addAction(android.R.drawable.ic_media_ff, "15s", forward)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent serviceAction(String action, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, code, i, pendingFlags());
    }

    private int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }

    private void notifyNow() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Audiobook playback", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("WoW Audio playback controls");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        if (session != null) { session.setActive(false); session.release(); }
        if (player != null) { try { player.release(); } catch (Exception ignored) { } }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
}
