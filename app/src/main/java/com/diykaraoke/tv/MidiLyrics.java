package com.diykaraoke.tv;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the timed lyrics out of a MIDI / KAR file. Pure Java.
 */
public class MidiLyrics {

    public static class Syllable {
        public final long timeMs;
        public final String text;
        Syllable(long t, String s) { timeMs = t; text = s; }
    }

    public static class Line {
        public final List<Syllable> syllables = new ArrayList<>();
        public long startMs() { return syllables.isEmpty() ? 0 : syllables.get(0).timeMs; }
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Syllable s : syllables) sb.append(s.text);
            return sb.toString();
        }
    }

    private static class Ev implements Comparable<Ev> {
        long tick; int type; byte[] payload; int order;
        public int compareTo(Ev o) {
            if (tick != o.tick) return tick < o.tick ? -1 : 1;
            return Integer.compare(order, o.order);
        }
    }

    private static class Tempo { long tick; int uspq; Tempo(long t, int u) { tick = t; uspq = u; } }

    public final List<Line> lines = new ArrayList<>();
    public String title = "";

    private final byte[] d;
    private int p;

    public static MidiLyrics parse(byte[] data) {
        MidiLyrics m = new MidiLyrics(data);
        try {
            m.run();
        } catch (RuntimeException e) {
            // Broken file: keep whatever was read.
        }
        return m;
    }

    private MidiLyrics(byte[] data) { this.d = data; }

    private int u8() { return d[p++] & 0xFF; }
    private int u16() { return (u8() << 8) | u8(); }
    private int u32() { return (u16() << 16) | u16(); }
    private int varLen() {
        int v = 0;
        for (int i = 0; i < 4; i++) {
            int c = u8();
            v = (v << 7) | (c & 0x7F);
            if ((c & 0x80) == 0) break;
        }
        return v;
    }

    private void run() {
        // Some files wrap MIDI in a RIFF (RMID) container; find the MThd header.
        int start = indexOf("MThd");
        if (start < 0) return;
        p = start + 4;
        int hdrLen = u32();
        int hdrEnd = p + hdrLen;
        u16(); // format
        int ntracks = u16();
        int division = u16();
        p = hdrEnd;

        List<Ev> lyricEvents = new ArrayList<>();
        List<Ev> textEvents = new ArrayList<>();
        List<Tempo> tempos = new ArrayList<>();
        int order = 0;

        for (int t = 0; t < ntracks && p + 8 <= d.length; t++) {
            if (!(d[p] == 'M' && d[p + 1] == 'T' && d[p + 2] == 'r' && d[p + 3] == 'k')) {
                // skip unknown chunk
                p += 4;
                int len = u32();
                p += len;
                t--;
                continue;
            }
            p += 4;
            int len = u32();
            int end = Math.min(d.length, p + len);
            long tick = 0;
            int running = 0;
            while (p < end) {
                tick += varLen();
                int status = u8();
                if (status < 0x80) { // running status
                    p--;
                    status = running;
                }
                if (status == 0xFF) {
                    int type = u8();
                    int l = varLen();
                    byte[] payload = new byte[Math.max(0, Math.min(l, end - p))];
                    System.arraycopy(d, p, payload, 0, payload.length);
                    p += l;
                    if (type == 0x51 && payload.length >= 3) {
                        tempos.add(new Tempo(tick, ((payload[0] & 0xFF) << 16) | ((payload[1] & 0xFF) << 8) | (payload[2] & 0xFF)));
                    } else if (type == 0x01 || type == 0x05) {
                        Ev e = new Ev();
                        e.tick = tick; e.type = type; e.payload = payload; e.order = order++;
                        (type == 0x05 ? lyricEvents : textEvents).add(e);
                    } else if (type == 0x03 && title.isEmpty() && t <= 1) {
                        title = decode(payload).trim();
                    } else if (type == 0x2F) {
                        break;
                    }
                } else if (status == 0xF0 || status == 0xF7) {
                    int l = varLen();
                    p += l;
                } else {
                    running = status;
                    int hi = status & 0xF0;
                    p += (hi == 0xC0 || hi == 0xD0) ? 1 : 2;
                }
            }
            p = end;
        }

        // KAR files use text events (with @ headers); others use lyric events.
        List<Ev> source;
        int karText = 0;
        for (Ev e : textEvents) {
            String s = decode(e.payload);
            if (!s.startsWith("@")) karText++;
        }
        boolean isKar = false;
        for (Ev e : textEvents) {
            if (decode(e.payload).startsWith("@KMIDI") || decode(e.payload).startsWith("@L")) { isKar = true; break; }
        }
        if (isKar || lyricEvents.size() < 4 && karText > lyricEvents.size()) {
            source = textEvents;
        } else {
            source = lyricEvents;
        }
        Collections.sort(source);
        Collections.sort(tempos, (a, b) -> Long.compare(a.tick, b.tick));

        Line current = new Line();
        for (Ev e : source) {
            String s = decode(e.payload);
            if (s.startsWith("@")) {
                if (s.startsWith("@T") && title.isEmpty()) title = s.substring(2).trim();
                continue;
            }
            long ms = tickToMs(e.tick, tempos, division);
            boolean breakBefore = false;
            while (s.startsWith("/") || s.startsWith("\\")) {
                breakBefore = true;
                s = s.substring(1);
            }
            if (breakBefore && !current.syllables.isEmpty()) {
                lines.add(current);
                current = new Line();
            }
            boolean breakAfter = false;
            if (s.endsWith("\r") || s.endsWith("\n") || s.endsWith("/") || s.endsWith("\\")) {
                breakAfter = true;
            }
            s = s.replace("\r", "").replace("\n", "").replace("/", "").replace("\\", "");
            if (!s.isEmpty()) current.syllables.add(new Syllable(ms, s));
            if (breakAfter && !current.syllables.isEmpty()) {
                lines.add(current);
                current = new Line();
            }
        }
        if (!current.syllables.isEmpty()) lines.add(current);
    }

    private static long tickToMs(long tick, List<Tempo> tempos, int division) {
        if ((division & 0x8000) != 0) {
            int fps = -((byte) (division >> 8));
            int tpf = division & 0xFF;
            return tick * 1000L / Math.max(1, fps * tpf);
        }
        int tpq = Math.max(1, division);
        double ms = 0;
        long lastTick = 0;
        int uspq = 500000;
        for (Tempo t : tempos) {
            if (t.tick >= tick) break;
            ms += (t.tick - lastTick) * (uspq / 1000.0) / tpq;
            lastTick = t.tick;
            uspq = t.uspq;
        }
        ms += (tick - lastTick) * (uspq / 1000.0) / tpq;
        return (long) ms;
    }

    private int indexOf(String tag) {
        byte[] t = tag.getBytes();
        outer:
        for (int i = 0; i + t.length <= d.length && i < 4096; i++) {
            for (int j = 0; j < t.length; j++) if (d[i + j] != t[j]) continue outer;
            return i;
        }
        return -1;
    }

    static String decode(byte[] b) {
        try {
            return Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("windows-1252"));
        }
    }
}
