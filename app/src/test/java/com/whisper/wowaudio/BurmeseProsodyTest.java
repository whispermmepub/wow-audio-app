package com.whisper.wowaudio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BurmeseProsodyTest {
    @Test public void autoMoodSoftensSadPassage() {
        BurmeseProsody.Profile p = BurmeseProsody.analyze(
                "သူမ မျက်ရည်ကျပြီး ဝမ်းနည်းစွာ တိတ်ဆိတ်နေခဲ့သည်။", VoiceSettings.STYLE_AUTO);
        assertTrue(p.speedMultiplier < 1.0f);
        assertTrue(p.pitchMultiplier < 1.0f);
        assertTrue(p.pauseAfterMs >= 100);
        assertEquals("Tender", p.label);
    }

    @Test public void autoMoodLiftsQuestionsWithoutExtremePitch() {
        BurmeseProsody.Profile p = BurmeseProsody.analyze(
                "မင်း ဘယ်မှာလဲ?", VoiceSettings.STYLE_AUTO);
        assertTrue(p.pitchMultiplier > 1.0f);
        assertTrue(p.pitchMultiplier < 1.05f);
        assertTrue(p.pauseAfterMs >= 100);
        assertEquals("Question", p.label);
    }

    @Test public void ellipsisBreathesMoreThanPlainStatement() {
        BurmeseProsody.Profile reflective = BurmeseProsody.analyze(
                "သူ ခဏငြိမ်နေပြီး ပြောလိုက်သည်…", VoiceSettings.STYLE_AUTO);
        BurmeseProsody.Profile plain = BurmeseProsody.analyze(
                "သူ ပြောလိုက်သည်။", VoiceSettings.STYLE_AUTO);
        assertTrue(reflective.pauseAfterMs > plain.pauseAfterMs);
        assertTrue(reflective.speedMultiplier < plain.speedMultiplier);
    }

    @Test public void dialogueGetsSubtleContrast() {
        BurmeseProsody.Profile p = BurmeseProsody.analyze(
                "“မင်း ပြန်လာမလား?”", VoiceSettings.STYLE_STORYTELLER);
        assertTrue(p.pitchMultiplier > 1.0f);
        assertTrue(p.pitchMultiplier < 1.05f);
    }

    @Test public void geminiLikeAddsWiderButSafeExpression() {
        String text = "“မင်း ဘယ်မှာလဲ? အခုချက်ချင်း ပြန်လာ!” သူက ဒေါသနဲ့ အော်လိုက်သည်။";
        BurmeseProsody.Profile auto = BurmeseProsody.analyze(text, VoiceSettings.STYLE_AUTO);
        BurmeseProsody.Profile expressive = BurmeseProsody.analyze(text, VoiceSettings.STYLE_GEMINI_EXPRESSIVE);
        assertTrue(Math.abs(expressive.pitchMultiplier - 1.0f) >= Math.abs(auto.pitchMultiplier - 1.0f));
        assertTrue(expressive.gainMb >= auto.gainMb);
        assertTrue(expressive.pitchMultiplier >= 0.925f && expressive.pitchMultiplier <= 1.07f);
        assertTrue(expressive.speedMultiplier >= 0.90f && expressive.speedMultiplier <= 1.08f);
    }

    @Test public void normalStyleKeepsMoodChangesSubtle() {
        BurmeseProsody.Profile p = BurmeseProsody.analyze(
                "သူ အရမ်းကြောက်ပြီး အန္တရာယ်ကနေ ပြေးခဲ့သည်!", VoiceSettings.STYLE_NORMAL);
        assertEquals(1.0f, p.speedMultiplier, 0.0001f);
        assertEquals(1.0f, p.pitchMultiplier, 0.0001f);
    }

    @Test public void fullStopGetsSettledLowerEndingAndPause() {
        BurmeseProsody.Profile p = BurmeseProsody.analyze("သူ ပြန်လာခဲ့သည်။", VoiceSettings.STYLE_GEMINI_EXPRESSIVE);
        assertTrue(p.pitchMultiplier < 1.0f);
        assertTrue(p.speedMultiplier < 1.0f);
        assertTrue(p.pauseAfterMs >= 190);
    }

    @Test public void structuralLineBreakGetsLongPause() {
        BurmeseProsody.Profile p = BurmeseProsody.analyze("အခန်း (၁)\n", VoiceSettings.STYLE_GEMINI_EXPRESSIVE);
        assertTrue(p.pauseAfterMs >= 320);
        assertTrue(p.pitchMultiplier <= 1.0f);
    }

    @Test public void geminiDirectionIncludesPassageAwareGuidance() {
        String direction = BurmeseProsody.geminiDirection("သူမ မျက်ရည်ကျပြီး ဝမ်းနည်းနေသည်။");
        assertTrue(direction.contains("gentle") || direction.contains("emotionally restrained"));
        assertTrue(direction.contains("Pause clearly at Burmese full stops"));
        assertTrue(direction.contains("Preserve every written word exactly"));
    }
}
