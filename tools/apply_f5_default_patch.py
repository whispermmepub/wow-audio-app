from pathlib import Path


def replace(path, old, new):
    p = Path(path)
    s = p.read_text(encoding="utf-8")
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    p.write_text(s.replace(old, new), encoding="utf-8")


# Public Gradio API was verified in CI to generate valid 24 kHz PCM with ref_audio=null.
path = "app/src/main/java/com/whisper/wowaudio/HuggingFaceF5Client.java"
marker = '''    File synthesizeToFile(String text, File referenceWav, String referenceText,
                          float speed, File target) throws Exception {
'''
default_method = '''    File synthesizeDefaultToFile(String text, float speed, File target) throws Exception {
        String cleanText = TtsText.normalizeForSpeech(text);
        if (cleanText.isEmpty()) throw new IllegalArgumentException("No readable text for F5 Myanmar voice.");
        float safeSpeed = Math.max(0.7f, Math.min(1.5f, speed));

        Throwable first = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String eventId = startGeneration(cleanText, null, "", safeSpeed);
                String outputUrl = awaitOutputUrl(eventId);
                download(outputUrl, target);
                if (!target.isFile() || target.length() < 1000L) {
                    throw new IOException("F5 Myanmar default voice returned empty audio.");
                }
                return target;
            } catch (Throwable problem) {
                if (first == null) first = problem;
                if (attempt == 1) {
                    if (problem instanceof Exception) throw (Exception) problem;
                    throw new IOException(problem);
                }
            }
        }
        if (first instanceof Exception) throw (Exception) first;
        throw new IOException("F5 Myanmar default voice failed.");
    }

''' + marker
replace(path, marker, default_method)

old = '''        JSONObject fileData = new JSONObject()
                .put("path", referencePath)
                .put("meta", new JSONObject().put("_type", "gradio.FileData"));
        JSONObject payload = new JSONObject()
                .put("text", text)
                .put("ref_audio", fileData)
                .put("ref_text", referenceText)
                .put("speed", speed);
'''
new = '''        Object refAudio = JSONObject.NULL;
        if (referencePath != null && !referencePath.trim().isEmpty()) {
            refAudio = new JSONObject()
                    .put("path", referencePath)
                    .put("meta", new JSONObject().put("_type", "gradio.FileData"));
        }
        JSONObject payload = new JSONObject()
                .put("text", text)
                .put("ref_audio", refAudio)
                .put("ref_text", referenceText == null ? "" : referenceText)
                .put("speed", speed);
'''
replace(path, old, new)

# F5 engine: no model ZIP or reference WAV required for the default online voice.
path = "app/src/main/java/com/whisper/wowaudio/F5LocalTtsEngine.java"
old = '''/**
 * Crash-safe Aung Gyi/F5 voice bridge.
 *
 * The local Q4 ONNX runtime can terminate the Android process inside native ONNX Runtime on some
 * phones. This class therefore never enters any native fallback path. It first calls the public
 * F5 Myanmar Space with the user's imported reference WAV, then tries the user's Gemini TTS key
 * when available. If both network paths fail, it throws back to AudiobookService, which can use
 * the normal non-native Edge Myanmar fallback without taking the app process down.
 *
 * The imported Q4 model stays installed for a future isolated-process local runtime.
 */
'''
new = '''/**
 * Crash-safe F5 Myanmar default voice bridge.
 *
 * The public F5 Myanmar Space can synthesize its base/default Burmese voice with ref_audio=null.
 * This keeps the experimental native ONNX path out of the audiobook process and requires no model
 * ZIP or reference WAV. Gemini remains an optional safe online backup; the service then has a
 * non-native WoW Natural fallback if both are unavailable.
 */
'''
replace(path, old, new)

old = '''    F5LocalTtsEngine(Context context) {
        if (context == null) throw new IllegalArgumentException("Missing Android context.");
        if (!F5MyanmarVoicePack.isInstalled(context)) {
            throw new IllegalStateException("အောင်ကြီး voice is not installed: "
                    + F5MyanmarVoicePack.missingReason(context));
        }
        appContext = context.getApplicationContext();
    }
'''
new = '''    F5LocalTtsEngine(Context context) {
        if (context == null) throw new IllegalArgumentException("Missing Android context.");
        appContext = context.getApplicationContext();
    }
'''
replace(path, old, new)

old = '''        // 1) Real F5 Myanmar zero-shot cloning online. This uses the imported Aung Gyi WAV and
        // matching transcript, so unlike generic fallbacks it preserves the selected voice.
        File onlineTemp = new File(appContext.getCacheDir(), "aung-gyi-f5-online.wav");
        try {
            onlineF5.synthesizeToFile(
                    clean,
                    F5MyanmarVoicePack.referenceAudio(appContext),
                    F5MyanmarVoicePack.REFERENCE_TEXT,
                    1.0f,
                    onlineTemp);
            Audio cloned = readWav(onlineTemp);
            //noinspection ResultOfMethodCallIgnored
            onlineTemp.delete();
            if (cloned.samples.length > 0) return cloned;
            f5Failure = new IllegalStateException("Online F5 returned no audio samples.");
'''
new = '''        // 1) Verified F5 Myanmar base/default voice. No ZIP and no reference WAV are needed.
        File onlineTemp = new File(appContext.getCacheDir(), "f5-myanmar-default-online.wav");
        try {
            onlineF5.synthesizeDefaultToFile(clean, 1.0f, onlineTemp);
            Audio generated = readWav(onlineTemp);
            //noinspection ResultOfMethodCallIgnored
            onlineTemp.delete();
            if (generated.samples.length > 0) return generated;
            f5Failure = new IllegalStateException("Online F5 default voice returned no audio samples.");
'''
replace(path, old, new)

replace(
    path,
    'String reason = f5Failure == null ? "Aung Gyi online voice is unavailable." : safeMessage(f5Failure);\n        throw new IllegalStateException("Aung Gyi voice unavailable: " + reason, f5Failure);',
    'String reason = f5Failure == null ? "F5 Myanmar default voice is unavailable." : safeMessage(f5Failure);\n        throw new IllegalStateException("F5 Myanmar default voice unavailable: " + reason, f5Failure);',
)

# Separate cache from all previous experimental Aung Gyi/custom output.
path = "app/src/main/java/com/whisper/wowaudio/AudioCache.java"
old = '''    static File f5(BookStore.Book book, int index, String text) {
        File dir = new File(book.directory, "speech-cache/f5/" + F5MyanmarVoicePack.VOICE_ID_AUNG_GYI);
        String signature = sha256(F5MyanmarVoicePack.RUNTIME_VERSION + "\\n" + text);
        return new File(dir, String.format(Locale.US, "%05d-%s.wav", index, signature.substring(0, 16)));
    }
'''
new = '''    static File f5(BookStore.Book book, int index, String text) {
        File dir = new File(book.directory, "speech-cache/f5/default-online");
        String signature = sha256("f5-myanmar-default-online-v1\\n" + text);
        return new File(dir, String.format(Locale.US, "%05d-%s.wav", index, signature.substring(0, 16)));
    }
'''
replace(path, old, new)

# F5 playback never enters the bundled native offline engine. If F5/Gemini fail, use
# the proven network WoW Natural voice; if that also fails, report an error in-app.
path = "app/src/main/java/com/whisper/wowaudio/AudiobookService.java"
replace(
    path,
    'if (VoiceSettings.ENGINE_F5.equals(engine)) return F5MyanmarVoicePack.DISPLAY_NAME_AUNG_GYI + " • Local";',
    'if (VoiceSettings.ENGINE_F5.equals(engine)) return "F5 Myanmar • Default";',
)

old = '''            if (VoiceSettings.ENGINE_F5.equals(profile.engine)) {
                if (!F5MyanmarVoicePack.isInstalled(this)) {
                    throw new IllegalStateException("အောင်ကြီး voice needs the Q4 model ZIP and private reference WAV. Open Settings to import them.");
                }
                File local = AudioCache.f5(book, index, text);
                if (local.isFile() && local.length() > 44) return local;
                if (allowFallback) {
                    broadcast("Generating အောင်ကြီး locally…", false);
                    updateNotification("Local voice • အောင်ကြီး…", false);
                }
                AudioCache.ensureParent(local);
                F5LocalTtsEngine.Audio generated = f5Engine().synthesize(this, text);
                if (generated == null || generated.samples == null || generated.samples.length == 0) {
                    throw new IllegalStateException("အောင်ကြီး local voice produced no audio.");
                }
                WavFile.writeMonoPcm16(local, generated.samples, generated.sampleRate);
                return local;
            }
'''
new = '''            if (VoiceSettings.ENGINE_F5.equals(profile.engine)) {
                File f5 = AudioCache.f5(book, index, text);
                if (f5.isFile() && f5.length() > 44) return f5;
                if (allowFallback) {
                    broadcast("Getting F5 Myanmar default voice…", false);
                    updateNotification("F5 Myanmar • Default…", false);
                }
                AudioCache.ensureParent(f5);
                try {
                    F5LocalTtsEngine.Audio generated = f5Engine().synthesize(this, text);
                    if (generated == null || generated.samples == null || generated.samples.length == 0) {
                        throw new IllegalStateException("F5 Myanmar default voice produced no audio.");
                    }
                    WavFile.writeMonoPcm16(f5, generated.samples, generated.sampleRate);
                    return f5;
                } catch (Throwable f5Failure) {
                    if (!allowFallback) return null;
                    File edge = AudioCache.edge(book, index, profile.edgeVoice, profile.speed, text);
                    if (edge.isFile() && edge.length() > 1024) return edge;
                    try {
                        broadcast("F5 unavailable • using WoW Natural", false);
                        updateNotification("WoW Natural fallback • " + edgeLabel(profile.edgeVoice), false);
                        AudioCache.ensureParent(edge);
                        edgeTts.synthesizeToFile(text, profile.edgeVoice, profile.speed, edge);
                        return edge;
                    } catch (Throwable edgeFailure) {
                        throw new IllegalStateException(
                                "F5 Myanmar failed: " + safeMessage(f5Failure)
                                        + "; WoW Natural failed: " + safeMessage(edgeFailure),
                                f5Failure);
                    }
                }
            }
'''
replace(path, old, new)

# Settings: F5 default is one-tap online and custom files are clearly optional.
path = "app/src/main/java/com/whisper/wowaudio/SettingsActivity.java"
replace(
    path,
    'f5Engine = radio("အောင်ကြီး • Local Custom Voice");',
    'f5Engine = radio("F5 Myanmar • Default (Online)");',
)
replace(
    path,
    'root.addView(section("Local Custom Voice • အောင်ကြီး"), marginTop(22));',
    'root.addView(section("Optional Custom Voice Files • အောင်ကြီး"), marginTop(22));',
)
replace(
    path,
    'root.addView(body("အောင်ကြီးက server/API မလိုဘဲ ဖုန်းထဲမှာပဲ ONNX Runtime နဲ့ အသံထုတ်ပါတယ်။ ပထမတစ်ကြိမ် Q4 model ZIP နဲ့ ကိုယ်ပိုင်/ခွင့်ပြုထားတဲ့ reference WAV ကို import လုပ်ရပါမယ်။ Model နဲ့ voice sample ကို app-private storage ထဲသာ သိမ်းပါတယ်။"), marginTop(8));',
    'root.addView(body("F5 Myanmar • Default ကို သုံးဖို့ ZIP/WAV မလိုပါ။ Internet ရှိရင် public F5 Myanmar service က default Burmese voice ထုတ်ပေးပါတယ်။ အောက်က Model ZIP / Voice WAV က အောင်ကြီး custom voice စမ်းသပ်မှုအတွက် optional အဖြစ်သာ သိမ်းထားပါတယ်။"), marginTop(8));',
)
old = '''            if (VoiceSettings.ENGINE_F5.equals(engine) && !F5MyanmarVoicePack.isInstalled(this)) {
                showMessage("အောင်ကြီး voice မပြည့်စုံသေးပါ", "Model ZIP နဲ့ reference WAV နှစ်ခုလုံးကို အရင် import လုပ်ပါ။\\n\\n" + F5MyanmarVoicePack.status(this));
                return;
            }
'''
replace(path, old, "")

# User-facing labels.
path = "app/src/main/java/com/whisper/wowaudio/VoiceSettings.java"
replace(
    path,
    'if (ENGINE_F5.equals(engine)) return F5MyanmarVoicePack.DISPLAY_NAME_AUNG_GYI + " • Local";',
    'if (ENGINE_F5.equals(engine)) return "F5 Myanmar • Default";',
)

print("F5 default voice patch applied successfully")
