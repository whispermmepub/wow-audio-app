package com.whisper.wowaudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class SettingsActivity extends Activity {
    private static final int NAVY = Color.rgb(11, 45, 105);
    private static final int INK = Color.rgb(23, 36, 64);
    private static final int MUTED = Color.rgb(91, 105, 132);
    private static final int PAGE = Color.rgb(246, 248, 253);
    private static final int REQ_F5_MODEL = 4101;
    private static final int REQ_F5_REFERENCE = 4102;

    private RadioGroup engineGroup;
    private RadioButton edgeEngine;
    private RadioButton geminiEngine;
    private RadioButton offlineEngine;
    private RadioButton f5Engine;
    private RadioGroup edgeVoiceGroup;
    private RadioButton nilarVoice;
    private RadioButton thihaVoice;
    private Spinner geminiVoice;
    private EditText geminiModel;
    private EditText geminiStyle;
    private EditText apiKey;
    private TextView keyStatus;
    private TextView f5Status;
    private Button previewButton;
    private MediaPlayer previewPlayer;

    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();
    private volatile Future<?> previewTask;
    private volatile GeminiTtsClient previewClient;
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        build();
        load();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        cancelPreviewWork();
        releasePreview();
        previewExecutor.shutdownNow();
        super.onDestroy();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = smallButton("‹");
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView title = heading("Settings", 28);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = dp(10);
        top.addView(title, tp);
        root.addView(top);

        root.addView(section("Voice Engine"), marginTop(18));
        engineGroup = new RadioGroup(this);
        engineGroup.setOrientation(RadioGroup.VERTICAL);
        edgeEngine = radio("WoW Natural • Nilar / Thiha");
        geminiEngine = radio("Google Gemini TTS • your API key");
        offlineEngine = radio("Offline Burmese backup");
        engineGroup.addView(edgeEngine);
        engineGroup.addView(geminiEngine);
        engineGroup.addView(offlineEngine);
        root.addView(engineGroup, marginTop(8));

        root.addView(section("WoW Natural Voice"), marginTop(20));
        edgeVoiceGroup = new RadioGroup(this);
        edgeVoiceGroup.setOrientation(RadioGroup.HORIZONTAL);
        nilarVoice = radio("Nilar");
        thihaVoice = radio("Thiha");
        edgeVoiceGroup.addView(nilarVoice, new RadioGroup.LayoutParams(0, -2, 1f));
        edgeVoiceGroup.addView(thihaVoice, new RadioGroup.LayoutParams(0, -2, 1f));
        root.addView(edgeVoiceGroup, marginTop(8));
        root.addView(body("Nilar / Thiha က API key မလိုပါ။ စာအုပ်စာသားကို အသံမထွက်ခင် သန့်စင်ပြီး မြန်မာစာ ဝါကျဖြတ်ပုံနဲ့ pause ကို ပိုသဘာဝကျအောင် ချိန်ထားပါတယ်။ Generate ပြီးသားအသံကို စာအုပ်အလိုက် cache သိမ်းထားပါတယ်။"), marginTop(6));

        root.addView(section("Gemini Natural Voice"), marginTop(22));
        keyStatus = body("");
        root.addView(keyStatus, marginTop(5));

        apiKey = edit("Google AI Studio API key");
        apiKey.setSingleLine(true);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(apiKey, marginTop(10));

        geminiVoice = new Spinner(this);
        ArrayAdapter<String> voices = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, VoiceSettings.GEMINI_VOICES);
        geminiVoice.setAdapter(voices);
        geminiVoice.setContentDescription("Gemini voice");
        root.addView(geminiVoice, marginTop(10));

        geminiModel = edit("Gemini TTS model");
        geminiModel.setSingleLine(true);
        root.addView(geminiModel, marginTop(10));

        geminiStyle = edit("Audiobook voice direction");
        geminiStyle.setMinLines(3);
        geminiStyle.setGravity(Gravity.TOP | Gravity.START);
        root.addView(geminiStyle, marginTop(10));

        LinearLayout keyActions = new LinearLayout(this);
        keyActions.setOrientation(LinearLayout.HORIZONTAL);
        previewButton = secondary("Preview Gemini");
        previewButton.setOnClickListener(v -> previewGemini());
        keyActions.addView(previewButton, new LinearLayout.LayoutParams(0, dp(56), 1f));
        Button clear = secondary("Clear API key");
        clear.setOnClickListener(v -> clearKey());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        cp.leftMargin = dp(8);
        keyActions.addView(clear, cp);
        root.addView(keyActions, marginTop(10));

        root.addView(body("Gemini TTS က optional ဖြစ်ပါတယ်။ API key ကို app က Android Keystore နဲ့ local device ထဲ encrypt လုပ်သိမ်းပြီး GitHub သို့မဟုတ် WoW server ကို မပို့ပါ။ Gemini အသံသုံးချိန် စာသားကို Google Gemini API သို့ ပို့ရပါတယ်။ Quota၊ billing နဲ့ data terms က သင့် Google API account အတိုင်းဖြစ်ပါတယ်။"), marginTop(10));
        root.addView(body("Voice setting ပြောင်းပြီး Save လုပ်ထားတာကို နောက်တစ်ကြိမ် Play/Resume စတဲ့အချိန်ကစပြီး အသုံးပြုပါမယ်။"), marginTop(6));

        Button save = primary("Save Settings");
        save.setOnClickListener(v -> save());
        root.addView(save, marginTop(24));
    }

    private void load() {
        String engine = VoiceSettings.engine(this);
        if (VoiceSettings.ENGINE_GEMINI.equals(engine)) geminiEngine.setChecked(true);
        else if (VoiceSettings.ENGINE_OFFLINE.equals(engine)) offlineEngine.setChecked(true);
        else edgeEngine.setChecked(true);

        if (EdgeMyanmarTtsClient.VOICE_THIHA.equals(VoiceSettings.edgeVoice(this))) thihaVoice.setChecked(true);
        else nilarVoice.setChecked(true);

        String selected = VoiceSettings.geminiVoice(this);
        for (int i = 0; i < VoiceSettings.GEMINI_VOICES.length; i++) {
            if (VoiceSettings.GEMINI_VOICES[i].equals(selected)) {
                geminiVoice.setSelection(i);
                break;
            }
        }
        geminiModel.setText(VoiceSettings.geminiModel(this));
        geminiStyle.setText(VoiceSettings.geminiStyle(this));
        boolean has = SecureApiKeyStore.hasGeminiKey(this);
        keyStatus.setText(has ? "Gemini API key: saved securely" : "Gemini API key: not set");
        apiKey.setHint(has ? "Leave blank to keep saved key" : "Paste API key here");
        updateF5Status();
    }

    private void save() {
        try {
            String engine;
            if (geminiEngine.isChecked()) engine = VoiceSettings.ENGINE_GEMINI;
            else if (offlineEngine.isChecked()) engine = VoiceSettings.ENGINE_OFFLINE;
            else engine = VoiceSettings.ENGINE_EDGE;
            VoiceSettings.setEngine(this, engine);
            VoiceSettings.setEdgeVoice(this,
                    thihaVoice.isChecked() ? EdgeMyanmarTtsClient.VOICE_THIHA : EdgeMyanmarTtsClient.VOICE_NILAR);
            VoiceSettings.setGeminiVoice(this, String.valueOf(geminiVoice.getSelectedItem()));
            VoiceSettings.setGeminiModel(this, geminiModel.getText().toString());
            VoiceSettings.setGeminiStyle(this, geminiStyle.getText().toString());
            String entered = apiKey.getText().toString().trim();
            if (!entered.isEmpty()) SecureApiKeyStore.saveGeminiKey(this, entered);

            if (VoiceSettings.ENGINE_GEMINI.equals(engine) && !SecureApiKeyStore.hasGeminiKey(this)) {
                showMessage("Gemini API key needed",
                        "Gemini voice ကိုရွေးထားပေမယ့် API key မရှိသေးပါ။ Key မထည့်မချင်း WoW Natural voice ကို fallback သုံးပါမယ်။");
            } else {
                cancelPreviewWork();
                releasePreview();
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
                finish();
            }
        } catch (Exception e) {
            showError("Could not save settings", e);
        }
    }

    private void pickF5Model() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        startActivityForResult(i, REQ_F5_MODEL);
    }

    private void pickF5Reference() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, REQ_F5_REFERENCE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode != REQ_F5_MODEL && requestCode != REQ_F5_REFERENCE) return;
        Toast.makeText(this, requestCode == REQ_F5_MODEL ? "Importing local voice model…" : "Importing private voice sample…", Toast.LENGTH_SHORT).show();
        previewExecutor.submit(() -> {
            try {
                if (requestCode == REQ_F5_MODEL) F5MyanmarVoicePack.installModelZip(this, uri);
                else F5MyanmarVoicePack.installReferenceWav(this, uri);
                runIfActive(() -> {
                    updateF5Status();
                    Toast.makeText(this, requestCode == REQ_F5_MODEL ? "Model installed" : "Voice sample installed", Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable e) {
                runIfActive(() -> {
                    updateF5Status();
                    showError("Could not import အောင်ကြီး voice", e);
                });
            }
        });
    }

    private void updateF5Status() {
        if (f5Status != null) f5Status.setText(F5MyanmarVoicePack.status(this));
    }

    private void clearKey() {
        try {
            cancelPreviewWork();
            releasePreview();
            SecureApiKeyStore.clearGeminiKey(this);
            apiKey.setText("");
            keyStatus.setText("Gemini API key: not set");
            apiKey.setHint("Paste API key here");
            Toast.makeText(this, "Gemini API key cleared", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            showError("Could not clear API key", e);
        }
    }

    private void previewGemini() {
        cancelPreviewWork();
        releasePreview();

        String entered = apiKey.getText().toString().trim();
        final String key;
        try {
            if (!entered.isEmpty()) {
                SecureApiKeyStore.saveGeminiKey(this, entered);
                key = entered;
                keyStatus.setText("Gemini API key: saved securely");
            } else key = SecureApiKeyStore.getGeminiKey(this);
        } catch (Exception e) {
            showError("Could not secure API key", e);
            return;
        }
        if (key.isEmpty()) {
            Toast.makeText(this, "Add a Gemini API key first", Toast.LENGTH_SHORT).show();
            return;
        }

        final String voice = String.valueOf(geminiVoice.getSelectedItem());
        final String model = geminiModel.getText().toString().trim();
        final String style = geminiStyle.getText().toString().trim();
        setPreviewBusy(true);
        Toast.makeText(this, "Generating voice preview…", Toast.LENGTH_SHORT).show();

        previewTask = previewExecutor.submit(() -> {
            GeminiTtsClient client = new GeminiTtsClient();
            previewClient = client;
            try {
                File target = new File(getCacheDir(), "gemini-preview.wav");
                client.synthesizeToFile("မင်္ဂလာပါ။ WoW Audio မှ ကြိုဆိုပါတယ်။ စာအုပ်ကောင်းတစ်အုပ်ကို သဘာဝကျကျ နားထောင်ကြရအောင်။",
                        key, model, voice, style, target);
                runIfActive(() -> {
                    setPreviewBusy(false);
                    playPreview(target);
                });
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    runIfActive(() -> {
                        setPreviewBusy(false);
                        showError("Gemini preview failed", e);
                    });
                }
            } finally {
                if (previewClient == client) previewClient = null;
                client.close();
            }
        });
    }

    private void playPreview(File file) {
        if (!isUiActive()) return;
        releasePreview();
        try {
            MediaPlayer p = new MediaPlayer();
            previewPlayer = p;
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            p.setDataSource(file.getAbsolutePath());
            p.setOnCompletionListener(mp -> releasePreview());
            p.prepare();
            p.start();
        } catch (Exception e) {
            releasePreview();
            showError("Could not play preview", e);
        }
    }

    private void cancelPreviewWork() {
        Future<?> task = previewTask;
        previewTask = null;
        if (task != null) task.cancel(true);
        GeminiTtsClient client = previewClient;
        previewClient = null;
        if (client != null) {
            try { client.close(); } catch (Exception ignored) { }
        }
        if (!destroyed) setPreviewBusy(false);
    }

    private void releasePreview() {
        MediaPlayer p = previewPlayer;
        previewPlayer = null;
        if (p == null) return;
        try { p.stop(); } catch (Exception ignored) { }
        try { p.reset(); } catch (Exception ignored) { }
        try { p.release(); } catch (Exception ignored) { }
    }

    private void setPreviewBusy(boolean busy) {
        Button b = previewButton;
        if (b == null) return;
        b.setEnabled(!busy);
        b.setText(busy ? "Generating…" : "Preview Gemini");
    }

    private boolean isUiActive() {
        return !destroyed && !isFinishing() && !isDestroyed();
    }

    private void runIfActive(Runnable action) {
        if (destroyed) return;
        runOnUiThread(() -> {
            if (isUiActive()) action.run();
        });
    }

    private void showMessage(String title, String message) {
        if (!isUiActive()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void showError(String title, Throwable error) {
        if (!isUiActive()) return;
        String message = error == null || error.getMessage() == null
                ? "Unknown error" : error.getMessage();
        showMessage(title, message);
    }

    private TextView section(String value) {
        return heading(value, 19);
    }

    private TextView heading(String value, float size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(INK);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private TextView body(String value) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(14);
        t.setTextColor(MUTED);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private RadioButton radio(String value) {
        RadioButton b = new RadioButton(this);
        b.setText(value);
        b.setTextSize(16);
        b.setTextColor(INK);
        b.setMinHeight(dp(52));
        return b;
    }

    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(15);
        e.setTextColor(INK);
        e.setHintTextColor(Color.rgb(126, 139, 163));
        e.setPadding(dp(14), dp(11), dp(14), dp(11));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.rgb(205, 216, 236));
        e.setBackground(bg);
        return e;
    }

    private Button primary(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(dp(60));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(NAVY);
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        return b;
    }

    private Button secondary(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(NAVY);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(235, 241, 252));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.rgb(198, 211, 236));
        b.setBackground(bg);
        return b;
    }

    private Button smallButton(String label) {
        Button b = secondary(label);
        b.setTextSize(28);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private LinearLayout.LayoutParams marginTop(int value) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(value);
        return p;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
