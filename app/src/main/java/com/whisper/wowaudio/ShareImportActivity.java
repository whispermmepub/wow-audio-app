package com.whisper.wowaudio;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tiny exported bridge dedicated to files shared by Telegram, file managers and other apps.
 * It normalizes SEND/SEND_MULTIPLE/ClipData/data into one explicit MainActivity intent while
 * preserving temporary URI read grants. MainActivity remains the only place that imports data.
 */
public final class ShareImportActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        forward(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        forward(intent);
    }

    private void forward(Intent source) {
        ArrayList<Uri> uris = collectUris(source);
        if (uris.isEmpty()) {
            Toast.makeText(this, "No EPUB file was attached.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Intent target = new Intent(this, MainActivity.class);
        target.setAction(uris.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_VIEW);
        target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String type = source == null ? null : source.getType();
        if (type == null || "*/*".equals(type)) type = "application/epub+zip";
        target.setType(type);

        if (uris.size() == 1) {
            target.setData(uris.get(0));
        } else {
            target.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }

        ClipData clip = null;
        for (Uri uri : uris) {
            if (clip == null) clip = ClipData.newRawUri("WoW Audio import", uri);
            else clip.addItem(new ClipData.Item(uri));
        }
        if (clip != null) target.setClipData(clip);
        startActivity(target);
        finish();
    }

    private static ArrayList<Uri> collectUris(Intent intent) {
        Set<Uri> unique = new LinkedHashSet<>();
        if (intent == null) return new ArrayList<>();

        if (intent.getData() != null) unique.add(intent.getData());

        Uri single;
        if (Build.VERSION.SDK_INT >= 33) single = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        else {
            //noinspection deprecation
            single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (single != null) unique.add(single);

        ArrayList<Uri> many;
        if (Build.VERSION.SDK_INT >= 33) many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
        else {
            //noinspection deprecation
            many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        }
        if (many != null) for (Uri uri : many) if (uri != null) unique.add(uri);

        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) unique.add(uri);
            }
        }
        return new ArrayList<>(unique);
    }
}
