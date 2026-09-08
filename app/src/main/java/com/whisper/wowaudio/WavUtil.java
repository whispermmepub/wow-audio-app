package com.whisper.wowaudio;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

final class WavUtil {
    private WavUtil() {}

    static StreamWriter streamingPcm16Mono(File file) throws IOException {
        return new StreamWriter(file);
    }

    static void writePcm16Mono(File file, byte[] pcm, int sampleRate) throws IOException {
        try (StreamWriter writer = streamingPcm16Mono(file)) {
            writer.write(pcm, sampleRate);
            writer.commit();
        }
    }

    static final class StreamWriter implements AutoCloseable {
        private final File target;
        private final File temp;
        private RandomAccessFile out;
        private int sampleRate;
        private long dataSize;
        private boolean committed;

        StreamWriter(File target) throws IOException {
            this.target = target;
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Unable to create audio cache directory");
            }
            this.temp = new File(target.getAbsolutePath() + ".part");
            if (temp.exists() && !temp.delete()) throw new IOException("Unable to reset partial audio");
            out = new RandomAccessFile(temp, "rw");
            writeHeaderPlaceholder(out);
        }

        void write(byte[] pcm, int blockSampleRate) throws IOException {
            if (pcm == null || pcm.length == 0) return;
            if (blockSampleRate <= 0) blockSampleRate = 24000;
            if (sampleRate == 0) sampleRate = blockSampleRate;
            if (sampleRate != blockSampleRate) {
                throw new IOException("Gemini audio sample rate changed during chapter generation");
            }
            if (dataSize + pcm.length > 0xffffffffL) throw new IOException("Chapter audio exceeds WAV size limit");
            out.write(pcm);
            dataSize += pcm.length;
        }

        long dataSize() { return dataSize; }
        int sampleRate() { return sampleRate == 0 ? 24000 : sampleRate; }

        void commit() throws IOException {
            if (committed) return;
            if (dataSize <= 0) throw new IOException("No narration audio was generated");
            int rate = sampleRate();
            patchHeader(out, rate, dataSize);
            out.getFD().sync();
            out.close();
            out = null;
            if (target.exists() && !target.delete()) throw new IOException("Unable to replace cached audio");
            if (!temp.renameTo(target)) throw new IOException("Unable to commit cached audio");
            committed = true;
        }

        void abort() {
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            out = null;
            if (!committed && temp.exists()) temp.delete();
        }

        @Override public void close() {
            if (!committed) abort();
        }
    }

    private static void writeHeaderPlaceholder(RandomAccessFile out) throws IOException {
        writeAscii(out, "RIFF");
        writeLe32(out, 0);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeLe32(out, 16);
        writeLe16(out, 1);
        writeLe16(out, 1);
        writeLe32(out, 24000);
        writeLe32(out, 48000);
        writeLe16(out, 2);
        writeLe16(out, 16);
        writeAscii(out, "data");
        writeLe32(out, 0);
    }

    private static void patchHeader(RandomAccessFile out, int sampleRate, long dataSize) throws IOException {
        out.seek(4);
        writeLe32(out, 36L + dataSize);
        out.seek(24);
        writeLe32(out, sampleRate);
        writeLe32(out, (long) sampleRate * 2L);
        out.seek(40);
        writeLe32(out, dataSize);
        out.seek(44L + dataSize);
    }

    private static void writeAscii(RandomAccessFile out, String value) throws IOException {
        out.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeLe16(RandomAccessFile out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    private static void writeLe32(RandomAccessFile out, long value) throws IOException {
        out.write((int) (value & 0xff));
        out.write((int) ((value >> 8) & 0xff));
        out.write((int) ((value >> 16) & 0xff));
        out.write((int) ((value >> 24) & 0xff));
    }
}