package com.whisper.wowaudio;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class WavUtil {
    private WavUtil() {}

    static void writePcm16Mono(File file, byte[] pcm, int sampleRate) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(file.getAbsolutePath() + ".part");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            int dataSize = pcm.length;
            int byteRate = sampleRate * 2;
            writeAscii(out, "RIFF");
            writeLe32(out, 36 + dataSize);
            writeAscii(out, "WAVE");
            writeAscii(out, "fmt ");
            writeLe32(out, 16);
            writeLe16(out, 1);
            writeLe16(out, 1);
            writeLe32(out, sampleRate);
            writeLe32(out, byteRate);
            writeLe16(out, 2);
            writeLe16(out, 16);
            writeAscii(out, "data");
            writeLe32(out, dataSize);
            out.write(pcm);
            out.getFD().sync();
        }
        if (file.exists() && !file.delete()) throw new IOException("Unable to replace cached audio");
        if (!temp.renameTo(file)) throw new IOException("Unable to commit cached audio");
    }

    private static void writeAscii(FileOutputStream out, String value) throws IOException {
        out.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeLe16(FileOutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    private static void writeLe32(FileOutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }
}
