package com.diykaraoke.tv;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Finds karaoke files on USB drives and internal storage. */
public class Library {

    private volatile List<Song> songs = new ArrayList<>();
    private final Map<String, Song> byId = new HashMap<>();
    private volatile boolean scanning = false;
    private volatile String lastScanInfo = "Not scanned yet";

    public List<Song> all() { return songs; }
    public boolean isScanning() { return scanning; }
    public String info() { return lastScanInfo; }

    public synchronized Song get(String id) { return byId.get(id); }

    /** Folders to scan: every mounted drive under /storage plus internal storage. */
    public static List<File> roots() {
        List<File> roots = new ArrayList<>();
        File storage = new File("/storage");
        File[] vols = storage.listFiles();
        if (vols != null) {
            for (File v : vols) {
                String n = v.getName();
                if (n.equals("emulated") || n.equals("self") || n.equals("sdcard0")) continue;
                if (v.isDirectory() && v.canRead()) roots.add(v);
            }
        }
        File internal = new File("/storage/emulated/0");
        if (internal.canRead()) roots.add(internal);
        return roots;
    }

    public void scan() {
        if (scanning) return;
        scanning = true;
        try {
            List<File> roots = roots();
            List<Song> found = new ArrayList<>();
            for (File r : roots) {
                boolean internal = r.getPath().startsWith("/storage/emulated");
                walk(r, found, 0, internal);
            }
            Collections.sort(found, (a, b) -> a.title.compareToIgnoreCase(b.title));
            Map<String, Song> map = new HashMap<>();
            for (Song s : found) map.put(s.id, s);
            synchronized (this) {
                byId.clear();
                byId.putAll(map);
                songs = found;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(found.size()).append(" songs found in ");
            for (int i = 0; i < roots.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(roots.get(i).getPath());
            }
            if (roots.isEmpty()) sb.append("(no readable drives — check storage permission)");
            lastScanInfo = sb.toString();
        } finally {
            scanning = false;
        }
    }

    private void walk(File dir, List<Song> out, int depth, boolean internal) {
        if (depth > 12) return;
        String dn = dir.getName();
        if (dn.startsWith(".")) return;
        // On internal storage skip the Android system folder (huge, no songs).
        if (internal && depth == 1 && dn.equals("Android")) return;
        File[] files = dir.listFiles();
        if (files == null) return;

        Map<String, File> cdgs = new HashMap<>();
        Map<String, File> audios = new HashMap<>();
        for (File f : files) {
            if (f.isDirectory()) {
                walk(f, out, depth + 1, internal);
                continue;
            }
            String name = f.getName();
            if (name.startsWith(".")) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            int dot = lower.lastIndexOf('.');
            if (dot < 0) continue;
            String ext = lower.substring(dot + 1);
            String base = name.substring(0, dot);
            String key = base.toLowerCase(Locale.ROOT);
            switch (ext) {
                case "cdg":
                    cdgs.put(key, f);
                    break;
                case "mp3": case "m4a": case "ogg": case "wav": case "flac": case "aac":
                    audios.put(key, f);
                    break;
                case "zip":
                    out.add(new Song(makeId(f), Song.Type.ZIP, base, f.getPath(), null));
                    break;
                case "mid": case "midi": case "kar":
                    out.add(new Song(makeId(f), Song.Type.MIDI, base, f.getPath(), null));
                    break;
                case "mp4": case "mkv": case "avi": case "webm": case "mov": case "mpg": case "mpeg": case "vob": case "ts":
                    out.add(new Song(makeId(f), Song.Type.VIDEO, base, f.getPath(), null));
                    break;
                default:
                    break;
            }
        }
        for (Map.Entry<String, File> e : cdgs.entrySet()) {
            File audio = audios.get(e.getKey());
            if (audio != null) {
                String name = audio.getName();
                String base = name.substring(0, name.lastIndexOf('.'));
                out.add(new Song(makeId(e.getValue()), Song.Type.CDG, base, audio.getPath(), e.getValue().getPath()));
            }
        }
    }

    private static String makeId(File f) {
        return Integer.toHexString(f.getPath().hashCode()) + Long.toHexString(f.length() & 0xFFFFF);
    }

    /** Case-insensitive search; every word must appear in the title. */
    public List<Song> search(String query, int limit) {
        List<Song> result = new ArrayList<>();
        String[] words = query == null ? new String[0] : query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        for (Song s : songs) {
            String t = s.title.toLowerCase(Locale.ROOT);
            boolean ok = true;
            for (String w : words) {
                if (!w.isEmpty() && !t.contains(w)) { ok = false; break; }
            }
            if (ok) {
                result.add(s);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }
}
