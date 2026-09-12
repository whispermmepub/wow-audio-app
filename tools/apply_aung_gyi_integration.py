from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path, old, new):
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Patch anchor not found in {path}: {old[:120]!r}")
    text = text.replace(old, new, 1)
    p.write_text(text, encoding="utf-8")
    print("patched", path)


# ---- VoiceSettings: add a dedicated local Aung Gyi engine ----
replace_once(
    "app/src/main/java/com/whisper/wowaudio/VoiceSettings.java",
    '    static final String ENGINE_OFFLINE = "offline";\n',
    '    static final String ENGINE_OFFLINE = "offline";\n'
    '    static final String ENGINE_F5 = "f5_myanmar";\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/VoiceSettings.java",
    '        if (ENGINE_GEMINI.equals(value) || ENGINE_OFFLINE.equals(value)) return value;\n',
    '        if (ENGINE_GEMINI.equals(value) || ENGINE_OFFLINE.equals(value) || ENGINE_F5.equals(value)) return value;\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/VoiceSettings.java",
    '        String safe = ENGINE_GEMINI.equals(value) || ENGINE_OFFLINE.equals(value) ? value : ENGINE_EDGE;\n',
    '        String safe = ENGINE_GEMINI.equals(value) || ENGINE_OFFLINE.equals(value) || ENGINE_F5.equals(value) ? value : ENGINE_EDGE;\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/VoiceSettings.java",
    '        if (ENGINE_GEMINI.equals(engine)) return "Gemini • " + geminiVoice(context);\n        if (ENGINE_OFFLINE.equals(engine)) return "Offline Burmese";\n',
    '        if (ENGINE_GEMINI.equals(engine)) return "Gemini • " + geminiVoice(context);\n'
    '        if (ENGINE_F5.equals(engine)) return F5MyanmarVoicePack.DISPLAY_NAME_AUNG_GYI + " • Local";\n'
    '        if (ENGINE_OFFLINE.equals(engine)) return "Offline Burmese";\n'
)

# ---- AudioCache: isolate F5 cache from Edge/Gemini/MMS ----
replace_once(
    "app/src/main/java/com/whisper/wowaudio/AudioCache.java",
    '    static File offline(BookStore.Book book, int index, float speed, String text) {\n        return SpeechCache.offline(book, index, speed, text);\n    }\n\n',
    '    static File offline(BookStore.Book book, int index, float speed, String text) {\n'
    '        return SpeechCache.offline(book, index, speed, text);\n'
    '    }\n\n'
    '    static File f5(BookStore.Book book, int index, String text) {\n'
    '        File dir = new File(book.directory, "speech-cache/f5/" + F5MyanmarVoicePack.VOICE_ID_AUNG_GYI);\n'
    '        String signature = sha256(F5MyanmarVoicePack.RUNTIME_VERSION + "\\n" + text);\n'
    '        return new File(dir, String.format(Locale.US, "%05d-%s.wav", index, signature.substring(0, 16)));\n'
    '    }\n\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/AudioCache.java",
    '        if (VoiceSettings.ENGINE_GEMINI.equals(engine)) {\n',
    '        if (VoiceSettings.ENGINE_F5.equals(engine)) {\n'
    '            File f = f5(book, index, text);\n'
    '            if (f.isFile() && f.length() > 44) return f;\n'
    '        } else if (VoiceSettings.ENGINE_GEMINI.equals(engine)) {\n'
)

# ---- Settings UI: select/import the local model and private reference voice ----
replace_once(
    "app/src/main/java/com/whisper/wowaudio/SettingsActivity.java",
    'import android.app.AlertDialog;\n',
    'import android.app.AlertDialog;\nimport android.content.Intent;\nimport android.net.Uri;\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/SettingsActivity.java",
    '    private static final int PAGE = Color.rgb(246, 248, 253);\n\n',
    '    private static final int PAGE = Color.rgb(246, 248, 253);\n'
    '    private static final int REQ_F5_MODEL = 4101;\n'
    '    private static final int REQ_F5_REFERENCE = 4102;\n\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/SettingsActivity.java",
    '    private RadioButton offlineEngine;\n',
    '    private RadioButton offlineEngine;\n    private RadioButton f5Engine;\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/SettingsActivity.java",
    '    private TextView keyStatus;\n',
    '    private TextView keyStatus;\n    private TextView f5Status;\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/SettingsActivity.java",
    '        offlineEngine = radio("Offline Burmese backup");\n        engineGroup.addView(edgeEngine);\n        engineGroup.addView(geminiEngine);\n        engineGroup.addView(offlineEngine);\n',
    '        offlineEngine = radio("Offline Burmese backup");\n'
    '        f5Engine = radio("အောင်ကြီး • Local Custom Voice");\n'
    '        engineGroup.addView(edgeEngine);\n'
    '        engineGroup.addView(geminiEngine);\n'
    '        engineGroup.addView(offlineEngine);\n'
    '        engineGroup.addView(f5Engine);\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/SettingsActivity.java",
    '        root.addView(body("Nilar / Thiha က API key မလိုပါ။ စာအုပ်စာသားကို အသံမထွက်ခင် သန့်စင်ပြီး မြန်မာစာ ဝါကျဖြတ်ပုံနဲ့ pause ကို ပိုသဘာဝကျအောင် ချိန်ထားပါတယ်။ Generate ပြီးသားအသံကို စာအုပ်အလိုက် cache သိမ်းထားပါတယ်။"), marginTop(6));\n\n        root.addView(section("Gemini Natural Voice"), marginTop(22));\n',
    '        root.addView(body("Nilar / Thiha က API key မလိုပါ။ စာအုပ်စာသားကို အသံမထွက်ခင် သန့်စင်ပြီး မြန်မာစာ ဝါကျဖြတ်ပုံနဲ့ pause ကို ပိုသဘာဝကျအောင် ချိန်ထားပါတယ်။ Generate ပြီးသားအသံကို စာအုပ်အလိုက် cache သိမ်းထားပါတယ်။"), marginTop(6));\n\n'
    '        root.addView(section("Local Custom Voice • အောင်ကြီး"), marginTop(22));\n'
    '        f5Status = body("");\n'
    '        root.addView(f5Status, marginTop(5));\n'
    '        LinearLayout f5Actions = new LinearLayout(this);\n'
    '        f5Actions.setOrientation(LinearLayout.HORIZONTAL);\n'
    '        Button importModel = secondary("Import Model ZIP");\n'
    '        importModel.setOnClickListener(v -> pickF5Model());\n'
    '        f5Actions.addView(importModel, new LinearLayout.LayoutParams(0, dp(56), 1f));\n'
    '        Button importVoice = secondary("Import Voice WAV");\n'
    '        importVoice.setOnClickListener(v -> pickF5Reference());\n'
    '        LinearLayout.LayoutParams f5vp = new LinearLayout.LayoutParams(0, dp(56), 1f);\n'
    '        f5vp.leftMargin = dp(8);\n'
    '        f5Actions.addView(importVoice, f5vp);\n'
    '        root.addView(f5Actions, marginTop(10));\n'
    '        root.addView(body("အောင်ကြီးက server/API မလိုဘဲ ဖုန်းထဲမှာပဲ ONNX Runtime နဲ့ အသံထုတ်ပါတယ်။ ပထမတစ်ကြိမ် Q4 model ZIP နဲ့ ကိုယ်ပိုင်/ခွင့်ပြုထားတဲ့ reference WAV ကို import လုပ်ရပါမယ်။ Model နဲ့ voice sample ကို app-private storage ထဲသာ သိမ်းပါတယ်။"), marginTop(8));\n\n'
    '        root.addView(section("Gemini Natural Voice"), marginTop(22));\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/SettingsActivity.java",
    '        if (VoiceSettings.ENGINE_GEMINI.equals(engine)) geminiEngine.setChecked(true);\n        else if (VoiceSettings.ENGINE_OFFLINE.equals(engine)) offlineEngine.setChecked(true);\n        else edgeEngine.setChecked(true);\n',
    '        if (VoiceSettings.ENGINE_GEMINI.equals(engine)) geminiEngine.setChecked(true);\n'
    '        else if (VoiceSettings.ENGINE_F5.equals(engine)) f5Engine.setChecked(true);\n'
    '        else if (VoiceSettings.ENGINE_OFFLINE.equals(engine)) offlineEngine.setChecked(true);\n'
    '        else edgeEngine.setChecked(true);\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/SettingsActivity.java",
    '        apiKey.setHint(has ? "Leave blank to keep saved key" : "Paste API key here");\n    }\n\n',
    '        apiKey.setHint(has ? "Leave blank to keep saved key" : "Paste API key here");\n'
    '        updateF5Status();\n'
    '    }\n\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/SettingsActivity.java",
    '            if (geminiEngine.isChecked()) engine = VoiceSettings.ENGINE_GEMINI;\n            else if (offlineEngine.isChecked()) engine = VoiceSettings.ENGINE_OFFLINE;\n            else engine = VoiceSettings.ENGINE_EDGE;\n            VoiceSettings.setEngine(this, engine);\n',
    '            if (geminiEngine.isChecked()) engine = VoiceSettings.ENGINE_GEMINI;\n'
    '            else if (f5Engine.isChecked()) engine = VoiceSettings.ENGINE_F5;\n'
    '            else if (offlineEngine.isChecked()) engine = VoiceSettings.ENGINE_OFFLINE;\n'
    '            else engine = VoiceSettings.ENGINE_EDGE;\n'
    '            if (VoiceSettings.ENGINE_F5.equals(engine) && !F5MyanmarVoicePack.isInstalled(this)) {\n'
    '                showMessage("အောင်ကြီး voice မပြည့်စုံသေးပါ", "Model ZIP နဲ့ reference WAV နှစ်ခုလုံးကို အရင် import လုပ်ပါ။\\n\\n" + F5MyanmarVoicePack.status(this));\n'
    '                return;\n'
    '            }\n'
    '            VoiceSettings.setEngine(this, engine);\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/SettingsActivity.java",
    '    private void clearKey() {\n',
    '    private void pickF5Model() {\n'
    '        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);\n'
    '        i.addCategory(Intent.CATEGORY_OPENABLE);\n'
    '        i.setType("application/zip");\n'
    '        startActivityForResult(i, REQ_F5_MODEL);\n'
    '    }\n\n'
    '    private void pickF5Reference() {\n'
    '        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);\n'
    '        i.addCategory(Intent.CATEGORY_OPENABLE);\n'
    '        i.setType("audio/*");\n'
    '        startActivityForResult(i, REQ_F5_REFERENCE);\n'
    '    }\n\n'
    '    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {\n'
    '        super.onActivityResult(requestCode, resultCode, data);\n'
    '        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;\n'
    '        Uri uri = data.getData();\n'
    '        if (requestCode != REQ_F5_MODEL && requestCode != REQ_F5_REFERENCE) return;\n'
    '        Toast.makeText(this, requestCode == REQ_F5_MODEL ? "Importing local voice model…" : "Importing private voice sample…", Toast.LENGTH_SHORT).show();\n'
    '        previewExecutor.submit(() -> {\n'
    '            try {\n'
    '                if (requestCode == REQ_F5_MODEL) F5MyanmarVoicePack.installModelZip(this, uri);\n'
    '                else F5MyanmarVoicePack.installReferenceWav(this, uri);\n'
    '                runIfActive(() -> {\n'
    '                    updateF5Status();\n'
    '                    Toast.makeText(this, requestCode == REQ_F5_MODEL ? "Model installed" : "Voice sample installed", Toast.LENGTH_SHORT).show();\n'
    '                });\n'
    '            } catch (Throwable e) {\n'
    '                runIfActive(() -> {\n'
    '                    updateF5Status();\n'
    '                    showError("Could not import အောင်ကြီး voice", e);\n'
    '                });\n'
    '            }\n'
    '        });\n'
    '    }\n\n'
    '    private void updateF5Status() {\n'
    '        if (f5Status != null) f5Status.setText(F5MyanmarVoicePack.status(this));\n'
    '    }\n\n'
    '    private void clearKey() {\n'
)

# ---- AudiobookService: synthesize F5 locally and keep stable Nilar/Thiha untouched ----
replace_once(
    "app/src/main/java/com/whisper/wowaudio/AudiobookService.java",
    '    private MmsMyanmarTtsEngine offlineTts;\n',
    '    private MmsMyanmarTtsEngine offlineTts;\n    private F5LocalTtsEngine f5Tts;\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/AudiobookService.java",
    '            if (VoiceSettings.ENGINE_GEMINI.equals(engine)) return "Gemini • " + geminiVoice;\n            if (VoiceSettings.ENGINE_OFFLINE.equals(engine)) return "Offline Burmese";\n',
    '            if (VoiceSettings.ENGINE_GEMINI.equals(engine)) return "Gemini • " + geminiVoice;\n'
    '            if (VoiceSettings.ENGINE_F5.equals(engine)) return F5MyanmarVoicePack.DISPLAY_NAME_AUNG_GYI + " • Local";\n'
    '            if (VoiceSettings.ENGINE_OFFLINE.equals(engine)) return "Offline Burmese";\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/AudiobookService.java",
    '            if (offlineTts != null) {\n                try { offlineTts.close(); } catch (Throwable ignored) { }\n                offlineTts = null;\n            }\n',
    '            if (offlineTts != null) {\n'
    '                try { offlineTts.close(); } catch (Throwable ignored) { }\n'
    '                offlineTts = null;\n'
    '            }\n'
    '            if (f5Tts != null) {\n'
    '                try { f5Tts.close(); } catch (Throwable ignored) { }\n'
    '                f5Tts = null;\n'
    '            }\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/AudiobookService.java",
    '            Throwable primaryFailure = null;\n\n            if (VoiceSettings.ENGINE_GEMINI.equals(profile.engine)) {\n',
    '            Throwable primaryFailure = null;\n\n'
    '            if (VoiceSettings.ENGINE_F5.equals(profile.engine)) {\n'
    '                if (!F5MyanmarVoicePack.isInstalled(this)) {\n'
    '                    throw new IllegalStateException("အောင်ကြီး voice needs the Q4 model ZIP and private reference WAV. Open Settings to import them.");\n'
    '                }\n'
    '                File local = AudioCache.f5(book, index, text);\n'
    '                if (local.isFile() && local.length() > 44) return local;\n'
    '                if (allowFallback) {\n'
    '                    broadcast("Generating အောင်ကြီး locally…", false);\n'
    '                    updateNotification("Local voice • အောင်ကြီး…", false);\n'
    '                }\n'
    '                AudioCache.ensureParent(local);\n'
    '                F5LocalTtsEngine.Audio generated = f5Engine().synthesize(this, text);\n'
    '                if (generated == null || generated.samples == null || generated.samples.length == 0) {\n'
    '                    throw new IllegalStateException("အောင်ကြီး local voice produced no audio.");\n'
    '                }\n'
    '                WavFile.writeMonoPcm16(local, generated.samples, generated.sampleRate);\n'
    '                return local;\n'
    '            }\n\n'
    '            if (VoiceSettings.ENGINE_GEMINI.equals(profile.engine)) {\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/AudiobookService.java",
    '    private MmsMyanmarTtsEngine offlineEngine() {\n',
    '    private F5LocalTtsEngine f5Engine() throws Exception {\n'
    '        synchronized (synthesisLock) {\n'
    '            if (f5Tts == null) f5Tts = new F5LocalTtsEngine(this);\n'
    '            return f5Tts;\n'
    '        }\n'
    '    }\n\n'
    '    private MmsMyanmarTtsEngine offlineEngine() {\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/AudiobookService.java",
    '        final int count = VoiceSettings.ENGINE_GEMINI.equals(profile.engine) ? 1 : 3;\n',
    '        final int count = (VoiceSettings.ENGINE_GEMINI.equals(profile.engine) || VoiceSettings.ENGINE_F5.equals(profile.engine)) ? 1 : 3;\n'
)
replace_once(
    "app/src/main/java/com/whisper/wowaudio/AudiobookService.java",
    '        if (f != null) return f;\n        if (VoiceSettings.ENGINE_GEMINI.equals(profile.engine)) {\n',
    '        if (f != null) return f;\n'
    '        if (VoiceSettings.ENGINE_F5.equals(profile.engine)) return null;\n'
    '        if (VoiceSettings.ENGINE_GEMINI.equals(profile.engine)) {\n'
)

# ---- Version 1.9.0 ----
replace_once("app/build.gradle", "        versionCode 14\n        versionName '1.8.1'\n", "        versionCode 15\n        versionName '1.9.0'\n")

# ---- CI: verify and publish the v1.9.0 integration ----
p = ROOT / ".github/workflows/build.yml"
text = p.read_text(encoding="utf-8")
text = text.replace("Build WoW Audio v1.8.1 voice + pause fixes", "Build WoW Audio v1.9.0 Aung Gyi local voice")
text = text.replace("Verify v1.8.1 audiobook contract", "Verify v1.9.0 audiobook contract")
text = text.replace('grep -q "versionCode 14" app/build.gradle', 'grep -q "versionCode 15" app/build.gradle')
text = text.replace("grep -q \"versionName '1.8.1'\" app/build.gradle", "grep -q \"versionName '1.9.0'\" app/build.gradle")
text = text.replace("versionCode='14'", "versionCode='15'")
text = text.replace("versionName='1.8.1'", "versionName='1.9.0'")
text = text.replace("WoW-Audio-v1.8.1-UPDATE-signed.apk", "WoW-Audio-v1.9.0-Aung-Gyi-UPDATE-signed.apk")
text = text.replace("Upload v1.8.1 update-install signed APK", "Upload v1.9.0 update-install signed APK")
text = text.replace("WoW-Audio-v1.8.1-UPDATE-signed", "WoW-Audio-v1.9.0-Aung-Gyi-UPDATE-signed")
text = text.replace("Upload v1.8.1 test APK", "Upload v1.9.0 test APK")
text = text.replace("WoW-Audio-v1.8.1-voice-pause-test", "WoW-Audio-v1.9.0-Aung-Gyi-test")
text = text.replace("Upload v1.8.1 unsigned release", "Upload v1.9.0 unsigned release")
text = text.replace("WoW-Audio-v1.8.1-release-unsigned", "WoW-Audio-v1.9.0-Aung-Gyi-release-unsigned")
contract_anchor = "          grep -q 'STYLE_GEMINI_EXPRESSIVE' app/src/main/java/com/whisper/wowaudio/VoiceSettings.java\n"
if contract_anchor not in text:
    raise SystemExit("build.yml contract anchor missing")
text = text.replace(contract_anchor, contract_anchor +
    "          grep -q 'ENGINE_F5' app/src/main/java/com/whisper/wowaudio/VoiceSettings.java\n"
    "          grep -q 'F5LocalTtsEngine' app/src/main/java/com/whisper/wowaudio/AudiobookService.java\n"
    "          grep -q 'onnxruntime-android:1.23.2' app/build.gradle\n", 1)
p.write_text(text, encoding="utf-8")
print("patched .github/workflows/build.yml")

print("Aung Gyi v1.9.0 integration patch complete")
