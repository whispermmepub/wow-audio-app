package com.whisper.wowaudio;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TtsTextTest {
    @Test public void removesMalformedEscapedNewlinesAndRepeatedNNoise() {
        String input = "ပထမစာကြောင်း။\\n\\n n n n n \\nဒုတိယစာကြောင်း။\nN\nတတိယစာကြောင်း။";
        String cleaned = TtsText.normalizeForSpeech(input);
        assertTrue(cleaned.contains("ပထမစာကြောင်း"));
        assertTrue(cleaned.contains("ဒုတိယစာကြောင်း"));
        assertTrue(cleaned.contains("တတိယစာကြောင်း"));
        assertFalse(cleaned.matches("(?s).*\\bn(?:\\s+n)+\\b.*"));

        List<String> chunks = TtsText.chunks(input);
        assertFalse(chunks.isEmpty());
        for (String chunk : chunks) {
            assertFalse(chunk.matches("(?s).*\\bn(?:\\s+n)+\\b.*"));
        }
    }

    @Test public void preservesNormalEnglishInsideMyanmarText() {
        String input = "မြန်မာစာနဲ့ China Dream ဆိုတဲ့ English စကားလုံးကို ထိန်းထားမယ်။";
        String cleaned = TtsText.normalizeForSpeech(input);
        assertTrue(cleaned.contains("China Dream"));
    }
}
