package com.whisper.wowaudio;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;

public class ShareImportActivity extends Activity {
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
        Uri uri = firstUri(source);
        if (uri == null) {
            Toast.makeText(this, "No EPUB file was attached", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String type = source == null ? null : source.getType();
        if (type == null || type.trim().isEmpty() || "*/*".equals(type)) type = "application/epub+zip";
        Intent target = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ClipData clip = source == null ? null : source.getClipData();
        if (clip != null) target.setClipData(clip);
        startActivity(target);
        finish();
    }

    @SuppressWarnings("deprecation")
    private Uri firstUri(Intent intent) {
        if (intent == null) return null;
        try {
            Uri single = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (single != null) return single;
        } catch (Exception ignored) { }
        try {
            ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null && !many.isEmpty() && many.get(0) != null) return many.get(0);
        } catch (Exception ignored) { }
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) return uri;
            }
        }
        return intent.getData();
    }
}
