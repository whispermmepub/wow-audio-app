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
        assertEquals("Tender", p.label);
    }

    @Test public void autoMoodLiftsQuestionsAndEmphasis() {
        BurmeseProsody.Profile p = BurmeseProsody.analyze(
                "မင်း ဘယ်မှာလဲ? အခု ပြန်လာမလား!", VoiceSettings.STYLE_AUTO);
        assertTrue(p.pitchMultiplier > 1.0f);
        assertTrue(p.pauseAfterMs >= 90);
    }

    @Test public void normalStyleKeepsMoodChangesSubtle() {
        BurmeseProsody.Profile p = BurmeseProsody.analyze(
                "သူ အရမ်းကြောက်ပြီး အန္တရာယ်ကနေ ပြေးခဲ့သည်!", VoiceSettings.STYLE_NORMAL);
        assertEquals(1.0f, p.speedMultiplier, 0.0001f);
        assertEquals(1.0f, p.pitchMultiplier, 0.0001f);
    }
}
