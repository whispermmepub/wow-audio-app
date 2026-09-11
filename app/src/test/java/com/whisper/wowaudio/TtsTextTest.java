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

    @Test public void keepsLongNarrationInPhraseSizedChunks() {
        String sentence = "သူသည် တိတ်ဆိတ်သောလမ်းပေါ်တွင် ဖြည်းဖြည်းလျှောက်နေခဲ့သည်။ ";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 14; i++) text.append(sentence);
        List<String> chunks = TtsText.chunks(text.toString());
        assertTrue(chunks.size() >= 3);
        for (int i = 1; i < chunks.size() - 1; i++) {
            assertTrue(chunks.get(i).length() <= 365);
        }
    }

    @Test public void closesDialogueQuoteWithTheSentenceChunk() {
        StringBuilder text = new StringBuilder();
        text.append("ဇာတ်လမ်းကို စတင်ပြောပြလိုက်သည်။ ");
        text.append("“မင်း ဘယ်မှာလဲ?” ");
        for (int i = 0; i < 8; i++) text.append("နောက်ထပ် မြန်မာစာကြောင်းတစ်ကြောင်း ဖြစ်သည်။ ");
        List<String> chunks = TtsText.chunks(text.toString());
        boolean foundDialogue = false;
        for (String chunk : chunks) {
            if (chunk.contains("မင်း ဘယ်မှာလဲ?")) {
                foundDialogue = true;
                assertTrue(chunk.contains("?”"));
            }
        }
        assertTrue(foundDialogue);
    }
}
