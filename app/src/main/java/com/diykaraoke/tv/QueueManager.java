package com.diykaraoke.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The shared song queue. Thread-safe. */
public class QueueManager {

    public static class Entry {
        public final long key;
        public final Song song;
        public final String singer;
        Entry(long key, Song song, String singer) { this.key = key; this.song = song; this.singer = singer; }

        JSONObject toJson() throws JSONException {
            JSONObject o = song.toJson();
            o.put("key", key);
            o.put("singer", singer);
            return o;
        }
    }

    public interface Listener { void onQueueChanged(); }

    private final List<Entry> queue = new ArrayList<>();
    private Entry nowPlaying;
    private long nextKey = 1;
    private Listener listener;

    public void setListener(Listener l) { listener = l; }

    public void add(Song song, String singer) {
        synchronized (this) {
            queue.add(new Entry(nextKey++, song, singer == null ? "" : singer));
        }
        notifyListener();
    }

    /** Takes the next entry off the queue and marks it as playing. */
    public synchronized Entry next() {
        nowPlaying = queue.isEmpty() ? null : queue.remove(0);
        return nowPlaying;
    }

    public synchronized Entry peek() { return queue.isEmpty() ? null : queue.get(0); }

    public synchronized Entry current() { return nowPlaying; }

    public synchronized void clearCurrent() { nowPlaying = null; }

    public synchronized int size() { return queue.size(); }

    public boolean remove(long key) {
        boolean changed = false;
        synchronized (this) {
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).key == key) { queue.remove(i); changed = true; break; }
            }
        }
        if (changed) notifyListener();
        return changed;
    }

    /** direction: -1 = up, +1 = down, -100 = to top */
    public boolean move(long key, int direction) {
        boolean changed = false;
        synchronized (this) {
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).key == key) {
                    int target = direction == -100 ? 0 : i + direction;
                    if (target >= 0 && target < queue.size() && target != i) {
                        Entry e = queue.remove(i);
                        queue.add(target, e);
                        changed = true;
                    }
                    break;
                }
            }
        }
        if (changed) notifyListener();
        return changed;
    }

    public void clear() {
        synchronized (this) { queue.clear(); }
        notifyListener();
    }

    public synchronized JSONArray queueJson() throws JSONException {
        JSONArray a = new JSONArray();
        for (Entry e : queue) a.put(e.toJson());
        return a;
    }

    public synchronized JSONObject currentJson() throws JSONException {
        return nowPlaying == null ? null : nowPlaying.toJson();
    }

    public synchronized List<Entry> snapshot() { return new ArrayList<>(queue); }

    private void notifyListener() {
        Listener l = listener;
        if (l != null) l.onQueueChanged();
    }
}
