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
        assertTrue(cleaned.contains("\n"));
        assertFalse(cleaned.matches("(?s).*\\bn(?:\\s+n)+\\b.*"));

        List<String> chunks = TtsText.chunks(input);
        assertFalse(chunks.isEmpty());
        for (String chunk : chunks) {
            assertFalse(chunk.matches("(?s).*\\bn(?:\\s+n)+\\b.*"));
        }
    }

    @Test public void removesSingleStandaloneNNoiseAnywhereInBurmeseText() {
        String input = "ပထမစာကြောင်း။ n ဒုတိယစာကြောင်း၊ (N) တတိယစာကြောင်း။";
        String cleaned = TtsText.normalizeForSpeech(input);
        assertTrue(cleaned.contains("ပထမစာကြောင်း"));
        assertTrue(cleaned.contains("ဒုတိယစာကြောင်း"));
        assertTrue(cleaned.contains("တတိယစာကြောင်း"));
        assertFalse(cleaned.matches("(?s).*(?<![A-Za-z0-9])[nN](?![A-Za-z0-9]).*"));
    }

    @Test public void preservesNormalEnglishInsideMyanmarText() {
        String input = "မြန်မာစာနဲ့ China Dream, Internet နဲ့ Nilar ဆိုတဲ့ English စကားလုံးတွေကို ထိန်းထားမယ်။";
        String cleaned = TtsText.normalizeForSpeech(input);
        assertTrue(cleaned.contains("China Dream"));
        assertTrue(cleaned.contains("Internet"));
        assertTrue(cleaned.contains("Nilar"));
    }

    @Test public void keepsLongNarrationInPhraseSizedChunks() {
        String sentence = "သူသည် တိတ်ဆိတ်သောလမ်းပေါ်တွင် ဖြည်းဖြည်းလျှောက်နေခဲ့သည်။ ";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 14; i++) text.append(sentence);
        List<String> chunks = TtsText.chunks(text.toString());
        assertTrue(chunks.size() >= 3);
        for (int i = 1; i < chunks.size() - 1; i++) {
            assertTrue(chunks.get(i).length() <= 305);
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

    @Test public void lineBreakSeparatesHeadingFromBody() {
        String input = "အခန်း (၁)\nဒါက စာကိုယ်ရဲ့ ပထမစာကြောင်း ဖြစ်ပါတယ်။ နောက်ထပ် စာကြောင်းလည်း ရှိပါတယ်။";
        List<String> chunks = TtsText.chunks(input);
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(0).contains("အခန်း (၁)"));
        assertFalse(chunks.get(0).contains("ပထမစာကြောင်း"));
    }

    @Test public void burmeseFullStopAndCommaCreateNaturalBoundaries() {
        String input = "သူ ပြန်လာခဲ့သည်။ နောက်တစ်ကြောင်းကို ဆက်ဖတ်မည်၊ ပြီးတော့ နောက်ဆုံးစာကို ဆက်မည်။";
        List<String> chunks = TtsText.chunks(input);
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(0).endsWith("။"));
    }
}
