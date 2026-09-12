package com.whisper.wowaudio;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Experimental local zero-shot F5 Myanmar voice engine.
 *
 * Model files and the private reference voice stay in app-private storage. The stable Edge
 * Nilar/Thiha path is deliberately not touched by this engine.
 */
final class F5LocalTtsEngine implements AutoCloseable {
    static final class Audio {
        final float[] samples;
        final int sampleRate;
        Audio(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    private final OrtEnvironment env;
    private final OrtSession preprocess;
    private final OrtSession transformer;
    private final OrtSession decode;
    private final Map<String, Integer> vocab;
    private final int modelSampleRate;
    private final int inputSampleRate;
    private final int outputSampleRate;
    private final int hopLength;
    private final int maxSignalLength;
    private final int nfeSteps;
    private volatile boolean closed;

    F5LocalTtsEngine(Context context) throws Exception {
        if (!F5MyanmarVoicePack.isInstalled(context)) {
            throw new IllegalStateException("အောင်ကြီး voice is not installed: " + F5MyanmarVoicePack.missingReason(context));
        }
        env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            options.setIntraOpNumThreads(Math.min(4, cores));
            options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setCPUArenaAllocator(true);

            preprocess = env.createSession(F5MyanmarVoicePack.preprocess(context).getAbsolutePath(), options);
            transformer = env.createSession(F5MyanmarVoicePack.transformer(context).getAbsolutePath(), options);
            decode = env.createSession(F5MyanmarVoicePack.decode(context).getAbsolutePath(), options);
        }

        Map<String, String> meta = preprocess.getMetadata().getCustomMetadata();
        modelSampleRate = intMeta(meta, "sample_rate", F5MyanmarVoicePack.SAMPLE_RATE);
        inputSampleRate = intMeta(meta, "in_sample_rate", F5MyanmarVoicePack.SAMPLE_RATE);
        outputSampleRate = intMeta(meta, "out_sample_rate", F5MyanmarVoicePack.SAMPLE_RATE);
        hopLength = intMeta(meta, "hop_length", 256);
        maxSignalLength = intMeta(meta, "max_signal_length", 4096);
        int exportedNfe = intMeta(meta, "nfe_step", 32);
        nfeSteps = Math.max(1, Math.min(F5MyanmarVoicePack.MOBILE_NFE_STEPS, exportedNfe));
        vocab = loadVocab(F5MyanmarVoicePack.vocab(context));
    }

    synchronized Audio synthesize(Context context, String text) throws Exception {
        if (closed) throw new IllegalStateException("F5 voice engine is closed.");
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("No text to synthesize.");

        float[] reference = readPcm16MonoWav(F5MyanmarVoicePack.referenceAudio(context), inputSampleRate);
        int[] textIds = tokenize(F5MyanmarVoicePack.REFERENCE_TEXT + clean);
        int referenceBytes = F5MyanmarVoicePack.REFERENCE_TEXT.getBytes(StandardCharsets.UTF_8).length;
        int generatedBytes = clean.getBytes(StandardCharsets.UTF_8).length;
        if (referenceBytes <= 0 || generatedBytes <= 0) throw new IllegalArgumentException("Invalid F5 reference text.");

        int modelAudioLength = (int) ((long) reference.length * modelSampleRate / inputSampleRate);
        int referenceFrames = Math.max(1, modelAudioLength / hopLength);
        int duration = referenceFrames + (int) Math.round(referenceFrames * (generatedBytes / (double) referenceBytes));
        int maxDuration = Math.max(Math.max(textIds.length + 1, referenceFrames + 2), duration);
        if (maxDuration > maxSignalLength) {
            throw new IllegalArgumentException("Text segment is too long for local Aung Gyi voice.");
        }

        OnnxTensor audioTensor = null;
        OnnxTensor textTensor = null;
        OnnxTensor durationTensor = null;
        OrtSession.Result preResult = null;
        OrtSession.Result stateOwner = null;
        try {
            audioTensor = OnnxTensor.createTensor(env, new float[][][]{reference});
            textTensor = OnnxTensor.createTensor(env, new int[][]{textIds});
            durationTensor = OnnxTensor.createTensor(env, new long[]{maxDuration});

            Map<String, OnnxTensor> preInputs = new HashMap<>();
            preInputs.put("audio", audioTensor);
            preInputs.put("text_ids", textTensor);
            preInputs.put("max_duration", durationTensor);
            preResult = preprocess.run(preInputs);

            OnnxTensor state = tensor(preResult, "noise");
            OnnxTensor ropeCos = tensor(preResult, "rope_cos");
            OnnxTensor ropeSin = tensor(preResult, "rope_sin");
            OnnxTensor catMelText = tensor(preResult, "cat_mel_text");
            OnnxTensor catMelTextDrop = tensor(preResult, "cat_mel_text_drop");

            for (int step = 0; step < nfeSteps; step++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Local voice generation cancelled.");
                try (OnnxTensor timeStep = OnnxTensor.createTensor(env, new int[]{step})) {
                    Map<String, OnnxTensor> transformerInputs = new HashMap<>();
                    transformerInputs.put("noise", state);
                    transformerInputs.put("rope_cos", ropeCos);
                    transformerInputs.put("rope_sin", ropeSin);
                    transformerInputs.put("cat_mel_text", catMelText);
                    transformerInputs.put("cat_mel_text_drop", catMelTextDrop);
                    transformerInputs.put("time_step", timeStep);
                    OrtSession.Result nextOwner = transformer.run(transformerInputs, Collections.singleton("denoised"));
                    OnnxTensor nextState = tensor(nextOwner, "denoised");
                    if (stateOwner != null) stateOwner.close();
                    stateOwner = nextOwner;
                    state = nextState;
                }
            }

            Map<String, OnnxTensor> decodeInputs = new HashMap<>();
            decodeInputs.put("denoised", tensor(stateOwner, "denoised"));
            decodeInputs.put("ref_signal_len", tensor(preResult, "ref_signal_len"));
            decodeInputs.put("rms_scale", tensor(preResult, "rms_scale"));
            decodeInputs.put("ref_mel_tail", tensor(preResult, "ref_mel_tail"));
            try (OrtSession.Result decoded = decode.run(decodeInputs, Collections.singleton("output_audio"))) {
                OnnxTensor output = tensor(decoded, "output_audio");
                FloatBuffer samplesBuffer = output.getFloatBuffer();
                if (samplesBuffer == null || !samplesBuffer.hasRemaining()) {
                    throw new IllegalStateException("F5 decoder produced no audio.");
                }
                float[] samples = new float[samplesBuffer.remaining()];
                samplesBuffer.get(samples);
                return new Audio(samples, outputSampleRate);
            }
        } finally {
            if (stateOwner != null) try { stateOwner.close(); } catch (Throwable ignored) { }
            if (preResult != null) try { preResult.close(); } catch (Throwable ignored) { }
            if (durationTensor != null) try { durationTensor.close(); } catch (Throwable ignored) { }
            if (textTensor != null) try { textTensor.close(); } catch (Throwable ignored) { }
            if (audioTensor != null) try { audioTensor.close(); } catch (Throwable ignored) { }
        }
    }

    private int[] tokenize(String text) {
        List<Integer> ids = new ArrayList<>(text.length());
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            String token = new String(Character.toChars(cp));
            Integer id = vocab.get(token);
            if (id == null) id = 0;
            ids.add(id);
            offset += Character.charCount(cp);
        }
        int[] out = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) out[i] = ids.get(i);
        return out;
    }

    private static Map<String, Integer> loadVocab(File file) throws Exception {
        Map<String, Integer> map = new HashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int index = 0;
            while ((line = in.readLine()) != null) map.put(line, index++);
        }
        if (map.isEmpty()) throw new IllegalArgumentException("F5 vocabulary is empty.");
        return map;
    }

    private static float[] readPcm16MonoWav(File file, int requiredSampleRate) throws Exception {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 44) throw new IllegalArgumentException("Reference WAV is too short.");
            if (!"RIFF".equals(readAscii(in, 4))) throw new IllegalArgumentException("Reference is not RIFF WAV.");
            readLe32(in);
            if (!"WAVE".equals(readAscii(in, 4))) throw new IllegalArgumentException("Reference is not WAVE audio.");

            int channels = -1;
            int sampleRate = -1;
            int bits = -1;
            long dataOffset = -1;
            int dataSize = -1;
            while (in.getFilePointer() + 8 <= in.length()) {
                String chunk = readAscii(in, 4);
                int size = readLe32(in);
                if (size < 0 || in.getFilePointer() + size > in.length()) throw new IllegalArgumentException("Malformed reference WAV.");
                long start = in.getFilePointer();
                if ("fmt ".equals(chunk)) {
                    int format = readLe16(in);
                    channels = readLe16(in);
                    sampleRate = readLe32(in);
                    readLe32(in);
                    readLe16(in);
                    bits = readLe16(in);
                    if (format != 1) throw new IllegalArgumentException("Reference WAV must use PCM audio.");
                } else if ("data".equals(chunk)) {
                    dataOffset = start;
                    dataSize = size;
                }
                in.seek(start + size + (size & 1));
            }
            if (channels != 1 || sampleRate != requiredSampleRate || bits != 16 || dataOffset < 0 || dataSize <= 0) {
                throw new IllegalArgumentException("Reference WAV must be mono 16-bit " + requiredSampleRate + " Hz.");
            }
            int count = dataSize / 2;
            float[] samples = new float[count];
            in.seek(dataOffset);
            for (int i = 0; i < count; i++) {
                int lo = in.readUnsignedByte();
                int hi = in.readUnsignedByte();
                short pcm = (short) ((hi << 8) | lo);
                samples[i] = pcm / 32768.0f;
            }
            return samples;
        }
    }

    private static OnnxTensor tensor(OrtSession.Result result, String name) {
        if (result == null) throw new IllegalStateException("Missing ONNX result for " + name);
        OnnxValue value = result.get(name).orElseThrow(() -> new IllegalStateException("Missing ONNX output " + name));
        if (!(value instanceof OnnxTensor)) throw new IllegalStateException("ONNX output is not a tensor: " + name);
        return (OnnxTensor) value;
    }

    private static int intMeta(Map<String, String> meta, String key, int fallback) {
        try { return Integer.parseInt(meta.get(key)); }
        catch (Exception ignored) { return fallback; }
    }

    private static String readAscii(RandomAccessFile in, int count) throws Exception {
        byte[] b = new byte[count];
        in.readFully(b);
        return new String(b, StandardCharsets.US_ASCII);
    }

    private static int readLe16(RandomAccessFile in) throws Exception {
        int a = in.readUnsignedByte();
        int b = in.readUnsignedByte();
        return a | (b << 8);
    }

    private static int readLe32(RandomAccessFile in) throws Exception {
        int a = in.readUnsignedByte();
        int b = in.readUnsignedByte();
        int c = in.readUnsignedByte();
        int d = in.readUnsignedByte();
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { decode.close(); } catch (Throwable ignored) { }
        try { transformer.close(); } catch (Throwable ignored) { }
        try { preprocess.close(); } catch (Throwable ignored) { }
    }
}
