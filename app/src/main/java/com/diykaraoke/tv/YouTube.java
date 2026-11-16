package com.diykaraoke.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** YouTube search. Uses the official API if a key is set, otherwise reads the search page. */
public class YouTube {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    public static JSONArray search(String query, String apiKey) throws Exception {
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return searchApi(query, apiKey.trim());
        }
        return searchPage(query);
    }

    private static JSONArray searchApi(String query, String key) throws Exception {
        String url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=25"
                + "&videoEmbeddable=true&q=" + URLEncoder.encode(query, "UTF-8") + "&key=" + URLEncoder.encode(key, "UTF-8");
        JSONObject res = new JSONObject(get(url));
        JSONArray items = res.optJSONArray("items");
        JSONArray out = new JSONArray();
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            JSONObject id = it.optJSONObject("id");
            JSONObject sn = it.optJSONObject("snippet");
            if (id == null || sn == null) continue;
            String vid = id.optString("videoId");
            if (vid.isEmpty()) continue;
            JSONObject o = new JSONObject();
            o.put("videoId", vid);
            o.put("title", unescapeHtml(sn.optString("title")));
            o.put("channel", sn.optString("channelTitle"));
            o.put("duration", "");
            o.put("thumb", "https://i.ytimg.com/vi/" + vid + "/mqdefault.jpg");
            out.put(o);
        }
        return out;
    }

    private static JSONArray searchPage(String query) throws Exception {
        String html = get("https://www.youtube.com/results?hl=en&search_query=" + URLEncoder.encode(query, "UTF-8"));
        JSONArray out = new JSONArray();
        int idx = html.indexOf("ytInitialData");
        if (idx < 0) return out;
        int start = html.indexOf('{', idx);
        int end = matchBrace(html, start);
        if (start < 0 || end < 0) return out;
        Object root = new JSONObject(html.substring(start, end + 1));
        collect(root, out, 0);
        return out;
    }

    private static void collect(Object node, JSONArray out, int depth) throws Exception {
        if (depth > 40 || out.length() >= 30) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject vr = o.optJSONObject("videoRenderer");
            if (vr != null) {
                String vid = vr.optString("videoId");
                if (!vid.isEmpty()) {
                    JSONObject r = new JSONObject();
                    r.put("videoId", vid);
                    r.put("title", runsText(vr.optJSONObject("title")));
                    r.put("channel", runsText(vr.optJSONObject("ownerText")));
                    JSONObject len = vr.optJSONObject("lengthText");
                    r.put("duration", len == null ? "" : len.optString("simpleText"));
                    r.put("thumb", "https://i.ytimg.com/vi/" + vid + "/mqdefault.jpg");
                    out.put(r);
                }
                return;
            }
            JSONArray names = o.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                collect(o.opt(names.getString(i)), out, depth + 1);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out, depth + 1);
        }
    }

    private static String runsText(JSONObject o) {
        if (o == null) return "";
        if (o.has("simpleText")) return o.optString("simpleText");
        JSONArray runs = o.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r != null) sb.append(r.optString("text"));
        }
        return sb.toString();
    }

    private static int matchBrace(String s, int start) {
        if (start < 0) return -1;
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** Pulls an 11-character video id out of a YouTube link (or returns null). */
    public static String extractId(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(?:v=|youtu\\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})").matcher(text);
        if (m.find()) return m.group(1);
        if (text.trim().matches("[A-Za-z0-9_-]{11}")) return text.trim();
        return null;
    }

    /** Title of a video via oEmbed (no key needed). */
    public static String title(String videoId) {
        try {
            JSONObject o = new JSONObject(get("https://www.youtube.com/oembed?format=json&url="
                    + URLEncoder.encode("https://www.youtube.com/watch?v=" + videoId, "UTF-8")));
            return o.optString("title", "YouTube video");
        } catch (Exception e) {
            return "YouTube video";
        }
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI");
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new Exception("HTTP " + code);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        String body = bo.toString("UTF-8");
        if (code >= 400) throw new Exception("HTTP " + code + ": " + body.substring(0, Math.min(300, body.length())));
        return body;
    }

    private static String unescapeHtml(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">");
    }
}
