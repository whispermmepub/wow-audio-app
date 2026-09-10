package com.whisper.wowaudio;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertTrue;

public final class EdgeMyanmarTtsClientTest {
    @Test public void javaClientDownloadsNilarAndThihaMp3() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "wow-audio-edge-test-" + System.nanoTime());
        assertTrue(root.mkdirs() || root.isDirectory());

        try (EdgeMyanmarTtsClient client = new EdgeMyanmarTtsClient()) {
            assertVoice(client, EdgeMyanmarTtsClient.VOICE_NILAR, new File(root, "nilar.mp3"));
            assertVoice(client, EdgeMyanmarTtsClient.VOICE_THIHA, new File(root, "thiha.mp3"));
        } finally {
            File[] files = root.listFiles();
            if (files != null) for (File file : files) file.delete();
            root.delete();
        }
    }

    private static void assertVoice(EdgeMyanmarTtsClient client, String voice, File output) throws Exception {
        long started = System.nanoTime();
        File result = client.synthesizeToFile(
                "မင်္ဂလာပါ။ WoW Audio မှ ကြိုဆိုပါတယ်။ မြန်မာစာအုပ်ကို နားထောင်နေပါတယ်။",
                voice,
                1.0f,
                output);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        System.out.println(voice + " Java client: " + result.length() + " bytes in " + elapsedMs + " ms");
        assertTrue(result.isFile());
        assertTrue("Expected usable MP3 for " + voice, result.length() > 3000L);
        assertTrue("Natural voice request took too long for " + voice, elapsedMs < 20_000L);
    }
}
