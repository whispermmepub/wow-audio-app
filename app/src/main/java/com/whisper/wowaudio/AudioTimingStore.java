package com.whisper.wowaudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class AudioTimingStore {
    private AudioTimingStore() { }

    static File sidecar(File audio) { return new File(audio.getAbsolutePath() + ".segments.json"); }

    static void write(File audio, List<Segment> segments) throws Exception {
        JSONArray a = new JSONArray();
        for (Segment s : segments) {
            JSONObject o = new JSONObject();
            o.put("text", s.text);
            o.put("start", s.startMs);
            o.put("end", s.endMs);
            a.put(o);
        }
        File target = sidecar(audio);
        File temp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new Exception("Unable to replace timing metadata");
        if (!temp.renameTo(target)) throw new Exception("Unable to save timing metadata");
    }

    static List<Segment> read(File audio) {
        List<Segment> result = new ArrayList<>();
        File file = sidecar(audio);
        if (!file.isFile()) return result;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = in.read(bytes);
            if (read <= 0) return result;
            JSONArray a = new JSONArray(new String(bytes, 0, read, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                result.add(new Segment(o.optString("text", ""), o.optLong("start"), o.optLong("end")));
            }
        } catch (Exception ignored) { }
        return result;
    }

    static Segment current(File audio, long positionMs) {
        List<Segment> segments = read(audio);
        if (segments.isEmpty()) return null;
        for (Segment segment : segments) if (positionMs >= segment.startMs && positionMs < segment.endMs) return segment;
        return positionMs >= segments.get(segments.size() - 1).endMs ? segments.get(segments.size() - 1) : segments.get(0);
    }

    static final class Segment {
        final String text;
        final long startMs;
        final long endMs;
        Segment(String text, long startMs, long endMs) {
            this.text = text == null ? "" : text;
            this.startMs = Math.max(0, startMs);
            this.endMs = Math.max(this.startMs, endMs);
        }
    }
}
