package com.whisper.wowaudio;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class OfflineVoiceSetupActivity extends Activity {
    static final String ESPEAK_RELEASE_URL = "https://github.com/espeak-ng/espeak-ng/releases/download/1.52.0/espeak-1.52.0-signed.apk";
    private boolean openedInstaller;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(247, 244, 237));
        getWindow().setNavigationBarColor(Color.rgb(247, 244, 237));
        showScreen();
    }

    @Override protected void onResume() {
        super.onResume();
        if (openedInstaller && OfflineBurmeseTtsClient.isEspeakInstalled(this)) {
            openedInstaller = false;
            new NarrationSettings(this).saveEngineMode(NarrationSettings.ENGINE_AUTO);
            LibraryGenerationReconciler.reconcile(this);
            NarrationGenerationService.resumePending(this);
            Toast.makeText(this, "Offline Burmese voice is ready. Your books will continue automatically.", Toast.LENGTH_LONG).show();
            openLibrary();
        }
    }

    private void showScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(40));
        root.setBackgroundColor(Color.rgb(247, 244, 237));
        scroll.addView(root);

        TextView title = text("Offline Burmese Voice", 28, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        root.addView(title);

        boolean installed = OfflineBurmeseTtsClient.isEspeakInstalled(this);
        TextView status = text(installed
                ? "Ready. eSpeak NG is installed. WoW Audio can prepare Burmese books without an API key or internet."
                : "Install this free open-source voice once. After that, WoW Audio can prepare Burmese books on the phone without Gemini quota or an API key.", 16, false);
        status.setLineSpacing(0, 1.3f);
        status.setPadding(0, dp(12), 0, dp(20));
        root.addView(status);

        if (!installed) {
            Button install = primary("Install Offline Burmese Voice");
            install.setContentDescription("Install Offline Burmese Voice. Opens the official eSpeak NG Android APK from GitHub.");
            root.addView(install, fullButton());
            install.setOnClickListener(v -> openInstaller());

            TextView source = text("Source: official eSpeak NG project. Burmese language code: my. The offline voice is compact and fast; it is less natural than Gemini but has no per-book API cost.", 13, false);
            source.setLineSpacing(0, 1.25f);
            source.setPadding(0, dp(14), 0, dp(20));
            root.addView(source);
        } else {
            Button continueButton = primary("Continue to WoW Audio");
            continueButton.setContentDescription("Continue to WoW Audio. Offline Burmese voice is installed.");
            root.addView(continueButton, fullButton());
            continueButton.setOnClickListener(v -> {
                new NarrationSettings(this).saveEngineMode(NarrationSettings.ENGINE_AUTO);
                LibraryGenerationReconciler.reconcile(this);
                NarrationGenerationService.resumePending(this);
                openLibrary();
            });
        }

        Button gemini = secondary("Use Gemini natural voice instead");
        LinearLayout.LayoutParams gp = fullButton();
        gp.topMargin = dp(10);
        root.addView(gemini, gp);
        gemini.setContentDescription("Use Gemini natural voice instead. Opens WoW Audio where you can add your private Gemini API key.");
        gemini.setOnClickListener(v -> {
            new NarrationSettings(this).saveEngineMode(NarrationSettings.ENGINE_GEMINI);
            openLibrary();
        });

        Button back = secondary("Back to library");
        LinearLayout.LayoutParams bp = fullButton();
        bp.topMargin = dp(8);
        root.addView(back, bp);
        back.setOnClickListener(v -> openLibrary());

        setContentView(scroll);
    }

    private void openInstaller() {
        try {
            openedInstaller = true;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(ESPEAK_RELEASE_URL));
            startActivity(intent);
        } catch (Exception e) {
            openedInstaller = false;
            Toast.makeText(this, "Unable to open the eSpeak NG download page", Toast.LENGTH_LONG).show();
        }
    }

    private void openLibrary() {
        Intent i = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private TextView text(String value, float size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(Color.rgb(35, 36, 36));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button primary(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(dp(58));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(48, 48, 43));
        bg.setCornerRadius(dp(18));
        b.setBackground(bg);
        return b;
    }

    private Button secondary(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.rgb(48, 48, 43));
        b.setMinHeight(dp(54));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(239, 234, 224));
        bg.setCornerRadius(dp(18));
        b.setBackground(bg);
        return b;
    }

    private LinearLayout.LayoutParams fullButton() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
