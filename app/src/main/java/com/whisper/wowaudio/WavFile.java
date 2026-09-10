package com.whisper.wowaudio;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Writes mono 16-bit PCM WAV files that Android's media stack can play reliably. */
final class WavFile {
    private WavFile() { }

    static void writeMonoPcm16(File file, float[] samples, int sampleRate) throws IOException {
        if (file == null) throw new IllegalArgumentException("Missing WAV destination.");
        if (samples == null || samples.length == 0) throw new IllegalArgumentException("No audio samples.");
        if (sampleRate <= 0) throw new IllegalArgumentException("Invalid sample rate.");

        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create audio cache directory.");
        }

        long dataBytesLong = (long) samples.length * 2L;
        if (dataBytesLong > 0x7fffffffL) throw new IOException("Generated speech chunk is too large.");
        int dataBytes = (int) dataBytesLong;
        int byteRate = sampleRate * 2;
        int riffSize = 36 + dataBytes;

        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file), 64 * 1024)) {
            ascii(out, "RIFF");
            le32(out, riffSize);
            ascii(out, "WAVE");
            ascii(out, "fmt ");
            le32(out, 16);          // PCM fmt chunk size
            le16(out, 1);           // PCM
            le16(out, 1);           // mono
            le32(out, sampleRate);
            le32(out, byteRate);
            le16(out, 2);           // block align
            le16(out, 16);          // bits/sample
            ascii(out, "data");
            le32(out, dataBytes);

            byte[] buffer = new byte[64 * 1024];
            int pos = 0;
            for (float sample : samples) {
                float v = Float.isFinite(sample) ? Math.max(-1.0f, Math.min(1.0f, sample)) : 0.0f;
                short pcm = (short) Math.round(v * 32767.0f);
                buffer[pos++] = (byte) (pcm & 0xff);
                buffer[pos++] = (byte) ((pcm >>> 8) & 0xff);
                if (pos >= buffer.length - 1) {
                    out.write(buffer, 0, pos);
                    pos = 0;
                }
            }
            if (pos > 0) out.write(buffer, 0, pos);
            out.flush();
        }
    }

    private static void ascii(BufferedOutputStream out, String value) throws IOException {
        for (int i = 0; i < value.length(); i++) out.write((byte) value.charAt(i));
    }

    private static void le16(BufferedOutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void le32(BufferedOutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }
}
