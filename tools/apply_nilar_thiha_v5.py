from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    s = p.read_text(encoding="utf-8")
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    p.write_text(s.replace(old, new), encoding="utf-8")


# Prosody v5: clearer Burmese sentence landings, shorter phrase breaths,
# longer reflective/paragraph pauses, still subtle enough for audiobook use.
prosody = "app/src/main/java/com/whisper/wowaudio/BurmeseProsody.java"
replace_once(
    prosody,
    'static final String RENDER_VERSION = "burmese-prosody-v4-pause-boundaries";',
    'static final String RENDER_VERSION = "burmese-prosody-v5-human-cadence";',
)
replace_once(
    prosody,
    'return new Profile(1.0f, 1.0f, 0, 105, "Natural");',
    'return new Profile(1.0f, 1.0f, 0, 78, "Natural");',
)
replace_once(
    prosody,
    '''    private static int boundaryPause(String raw) {
        if (hasStructuralLineBreak(raw)) return 320;
        String value = stripClosingQuotes(raw.trim());
        if (hasEllipsis(value)) return 205;
        if (value.endsWith("?") || value.endsWith("!")) return 175;
        if (value.endsWith("။") || value.endsWith(".")) return 190;
        if (value.endsWith("၊") || value.endsWith(",") || value.endsWith(";") || value.endsWith(":")) return 115;
        return 95;
    }
''',
    '''    private static int boundaryPause(String raw) {
        if (hasStructuralLineBreak(raw)) return 360;
        String value = stripClosingQuotes(raw.trim());
        if (hasEllipsis(value)) return 290;
        if (value.endsWith("?")) return 225;
        if (value.endsWith("!")) return 190;
        if (value.endsWith("။") || value.endsWith(".")) return 230;
        if (value.endsWith("၊") || value.endsWith(",") || value.endsWith(";") || value.endsWith(":")) return 110;
        return 72;
    }
''',
)
replace_once(prosody,
             'if (sentenceEnd) expressivePause = Math.max(expressivePause, 190);',
             'if (sentenceEnd) expressivePause = Math.max(expressivePause, 230);')
replace_once(prosody,
             'if (phraseEnd) expressivePause = Math.max(expressivePause, 115);',
             'if (phraseEnd) expressivePause = Math.max(expressivePause, 110);')
replace_once(prosody,
             'if (paragraph) expressivePause = Math.max(expressivePause, 320);',
             'if (paragraph) expressivePause = Math.max(expressivePause, 360);')
replace_once(prosody,
             'expressivePause = clamp(expressivePause, 70, 360);',
             'expressivePause = clamp(expressivePause, 55, 420);')

# Retire F5/custom from the selectable engine path. Existing installs that had F5 saved
# migrate automatically to Nilar/Thiha rather than staying on a dead choice.
settings = "app/src/main/java/com/whisper/wowaudio/VoiceSettings.java"
replace_once(
    settings,
    '''    static String engine(Context context) {
        String value = prefs(context).getString("engine", ENGINE_EDGE);
        if (ENGINE_GEMINI.equals(value) || ENGINE_OFFLINE.equals(value) || ENGINE_F5.equals(value)) return value;
        return ENGINE_EDGE;
    }

    static void setEngine(Context context, String value) {
        String safe = ENGINE_GEMINI.equals(value) || ENGINE_OFFLINE.equals(value) || ENGINE_F5.equals(value) ? value : ENGINE_EDGE;
        prefs(context).edit().putString("engine", safe).apply();
    }
''',
    '''    static String engine(Context context) {
        String value = prefs(context).getString("engine", ENGINE_EDGE);
        if (ENGINE_F5.equals(value)) {
            prefs(context).edit().putString("engine", ENGINE_EDGE).apply();
            return ENGINE_EDGE;
        }
        if (ENGINE_GEMINI.equals(value) || ENGINE_OFFLINE.equals(value)) return value;
        return ENGINE_EDGE;
    }

    static void setEngine(Context context, String value) {
        String safe = ENGINE_GEMINI.equals(value) || ENGINE_OFFLINE.equals(value) ? value : ENGINE_EDGE;
        prefs(context).edit().putString("engine", safe).apply();
    }
''',
)
replace_once(settings,
             '        if (ENGINE_F5.equals(engine)) return "F5 Myanmar • Default";\n',
             '')

# Hide/remove F5 custom controls from Settings; Nilar/Thiha become the primary user-facing voices.
activity = "app/src/main/java/com/whisper/wowaudio/SettingsActivity.java"
replace_once(activity, '        f5Engine = radio("F5 Myanmar • Default (Online)");\n', '')
replace_once(activity, '        engineGroup.addView(f5Engine);\n', '')
replace_once(activity,
             '        else if (VoiceSettings.ENGINE_F5.equals(engine)) f5Engine.setChecked(true);\n',
             '')
replace_once(activity,
             '            else if (f5Engine.isChecked()) engine = VoiceSettings.ENGINE_F5;\n',
             '')
replace_once(
    activity,
    '''        root.addView(section("Optional Custom Voice Files • အောင်ကြီး"), marginTop(22));
        f5Status = body("");
        root.addView(f5Status, marginTop(5));
        LinearLayout f5Actions = new LinearLayout(this);
        f5Actions.setOrientation(LinearLayout.HORIZONTAL);
        Button importModel = secondary("Import Model ZIP");
        importModel.setOnClickListener(v -> pickF5Model());
        f5Actions.addView(importModel, new LinearLayout.LayoutParams(0, dp(56), 1f));
        Button importVoice = secondary("Import Voice WAV");
        importVoice.setOnClickListener(v -> pickF5Reference());
        LinearLayout.LayoutParams f5vp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        f5vp.leftMargin = dp(8);
        f5Actions.addView(importVoice, f5vp);
        root.addView(f5Actions, marginTop(10));
        root.addView(body("F5 Myanmar • Default ကို သုံးဖို့ ZIP/WAV မလိုပါ။ Internet ရှိရင် public F5 Myanmar service က default Burmese voice ထုတ်ပေးပါတယ်။ အောက်က Model ZIP / Voice WAV က အောင်ကြီး custom voice စမ်းသပ်မှုအတွက် optional အဖြစ်သာ သိမ်းထားပါတယ်။"), marginTop(8));

''',
    '',
)

# Update CI contract to the new narration pipeline and n-noise guard.
workflow = ".github/workflows/build.yml"
replace_once(workflow,
             "          grep -q 'burmese-prosody-v4-pause-boundaries' app/src/main/java/com/whisper/wowaudio/BurmeseProsody.java\n",
             "          grep -q 'burmese-prosody-v5-human-cadence' app/src/main/java/com/whisper/wowaudio/BurmeseProsody.java\n")
replace_once(workflow,
             "          grep -q 'Structural newlines must not be swallowed' app/src/main/java/com/whisper/wowaudio/TtsText.java\n",
             "          grep -q 'standalone n/N noise is removed everywhere' app/src/main/java/com/whisper/wowaudio/TtsText.java\n")

print("Applied Nilar/Thiha v5 migration")
