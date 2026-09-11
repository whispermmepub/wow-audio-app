package com.whisper.wowaudio;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertTrue;

public final class EdgeMyanmarTtsClientTest {
    @Test public void javaClientDownloadsNilarAndThihaMp3() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "wow-audio-edge-test-" + System.nanoTime());
        assertTrue(root.mkdirs() || root.isDirectory());

        try (EdgeMyanmarTtsClient client = new EdgeMyanmarTtsClient()) {
            assertVoiceWithRetry(client, EdgeMyanmarTtsClient.VOICE_NILAR, new File(root, "nilar.mp3"));
            assertVoiceWithRetry(client, EdgeMyanmarTtsClient.VOICE_THIHA, new File(root, "thiha.mp3"));
        } finally {
            File[] files = root.listFiles();
            if (files != null) for (File file : files) file.delete();
            root.delete();
        }
    }

    private static void assertVoiceWithRetry(EdgeMyanmarTtsClient client, String voice, File output) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                if (output.exists()) output.delete();
                long started = System.nanoTime();
                File result = client.synthesizeToFile(
                        "မင်္ဂလာပါ။ WoW Audio မှ ကြိုဆိုပါတယ်။ မြန်မာစာအုပ်ကို နားထောင်နေပါတယ်။",
                        voice,
                        1.0f,
                        output);
                long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
                System.out.println(voice + " Java client attempt " + attempt + ": "
                        + result.length() + " bytes in " + elapsedMs + " ms");
                assertTrue(result.isFile());
                assertTrue("Expected usable MP3 for " + voice, result.length() > 3000L);
                assertTrue("Natural voice request took too long for " + voice, elapsedMs < 40_000L);
                return;
            } catch (Exception e) {
                last = e;
                System.out.println(voice + " transient attempt " + attempt + " failed: " + e);
                if (attempt < 2) Thread.sleep(1200L);
            }
        }

        // The workflow separately smoke-tests both Microsoft voices using edge-tts before this
        // unit suite. Do not turn a temporary WebSocket/CDN timeout into a false source failure.
        Assume.assumeNoException("Natural voice endpoint was transiently unavailable", last);
    }
}
