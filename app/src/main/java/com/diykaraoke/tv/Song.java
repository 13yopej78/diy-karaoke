package com.diykaraoke.tv;

import org.json.JSONException;
import org.json.JSONObject;

/** A song in the library or the queue. */
public class Song {
    public enum Type { CDG, ZIP, VIDEO, MIDI, YOUTUBE }

    public final String id;      // library id, or YouTube video id
    public final Type type;
    public final String title;
    public final String path;    // main file (audio / zip / video / midi); null for YouTube
    public final String cdgPath; // only for CDG

    public Song(String id, Type type, String title, String path, String cdgPath) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.path = path;
        this.cdgPath = cdgPath;
    }

    public String typeLabel() {
        switch (type) {
            case CDG: case ZIP: return "CDG";
            case VIDEO: return "Video";
            case MIDI: return "MIDI";
            case YOUTUBE: return "YouTube";
        }
        return "";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("type", typeLabel());
        o.put("title", title);
        return o;
    }
}
