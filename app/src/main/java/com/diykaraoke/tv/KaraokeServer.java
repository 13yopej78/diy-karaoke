package com.diykaraoke.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small web server that phones connect to for searching and queueing songs. */
public class KaraokeServer {
    private static final String TAG = "KaraokeServer";
    public static final int PORT = 5555;

    /** Playback actions the phone can trigger; implemented by MainActivity. */
    public interface Controller {
        void skip();
        void togglePause();
        void restart();
        void changeKey(int semitonesDelta);
        JSONObject playerState();
    }

    private final Context context;
    private final Library library;
    private final QueueManager queue;
    private final Controller controller;
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private ServerSocket server;
    private volatile boolean running;

    public KaraokeServer(Context context, Library library, QueueManager queue, Controller controller) {
        this.context = context.getApplicationContext();
        this.library = library;
        this.queue = queue;
        this.controller = controller;
    }

    public void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(PORT));
                while (running) {
                    Socket s = server.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (Exception e) {
                if (running) Log.e(TAG, "server stopped", e);
            }
        }, "karaoke-server");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(20000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) { socket.close(); return; }
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String target = parts.length > 1 ? parts[1] : "/";
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("content-length")) {
                    contentLength = Integer.parseInt(line.substring(c + 1).trim());
                }
            }
            byte[] body = new byte[Math.min(contentLength, 1 << 20)];
            int read = 0;
            while (read < body.length) {
                int n = in.read(body, read, body.length - read);
                if (n < 0) break;
                read += n;
            }
            String path = target;
            Map<String, String> params = new HashMap<>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                parseParams(target.substring(q + 1), params);
            }
            JSONObject json = null;
            if (body.length > 0) {
                try { json = new JSONObject(new String(body, "UTF-8")); } catch (Exception ignored) { }
            }
            route(socket.getOutputStream(), method, path, params, json);
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
        } finally {
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    private void route(OutputStream out, String method, String path, Map<String, String> params, JSONObject body) throws Exception {
        if (body == null) body = new JSONObject();
        switch (path) {
            case "/":
            case "/index.html":
                sendAsset(out, "web/index.html", "text/html; charset=utf-8");
                return;
            case "/ytplayer.html":
                sendAsset(out, "web/ytplayer.html", "text/html; charset=utf-8");
                return;
            case "/api/state": {
                JSONObject o = new JSONObject();
                o.put("now", queue.currentJson() == null ? JSONObject.NULL : queue.currentJson());
                o.put("queue", queue.queueJson());
                o.put("player", controller.playerState());
                o.put("library", library.all().size());
                o.put("scanning", library.isScanning());
                o.put("libraryInfo", library.info());
                sendJson(out, o);
                return;
            }
            case "/api/library": {
                String query = params.containsKey("q") ? params.get("q") : "";
                List<Song> res = library.search(query, 300);
                JSONArray a = new JSONArray();
                for (Song s : res) a.put(s.toJson());
                JSONObject o = new JSONObject();
                o.put("results", a);
                o.put("total", library.all().size());
                sendJson(out, o);
                return;
            }
            case "/api/youtube": {
                String query = params.containsKey("q") ? params.get("q") : "";
                JSONObject o = new JSONObject();
                try {
                    String id = YouTube.extractId(query);
                    if (id != null && query.contains("/")) {
                        JSONArray a = new JSONArray();
                        JSONObject r = new JSONObject();
                        r.put("videoId", id);
                        r.put("title", YouTube.title(id));
                        r.put("channel", "");
                        r.put("duration", "");
                        r.put("thumb", "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg");
                        a.put(r);
                        o.put("results", a);
                    } else {
                        boolean addKaraoke = !"0".equals(params.get("karaoke"));
                        String full = addKaraoke && !query.toLowerCase(Locale.ROOT).contains("karaoke") ? query + " karaoke" : query;
                        o.put("results", YouTube.search(full, prefs().getString("ytKey", "")));
                    }
                } catch (Exception e) {
                    o.put("results", new JSONArray());
                    o.put("error", "YouTube search failed: " + e.getMessage());
                }
                sendJson(out, o);
                return;
            }
            case "/api/add": {
                String singer = body.optString("singer", "");
                String type = body.optString("type", "local");
                Song song;
                if (type.equals("youtube")) {
                    String vid = body.optString("videoId");
                    if (vid.isEmpty()) { sendError(out, 400, "missing videoId"); return; }
                    String title = body.optString("title", "");
                    if (title.isEmpty()) title = YouTube.title(vid);
                    song = new Song(vid, Song.Type.YOUTUBE, title, null, null);
                } else {
                    song = library.get(body.optString("id"));
                    if (song == null) { sendError(out, 404, "song not found — try Rescan"); return; }
                }
                queue.add(song, singer);
                sendJson(out, new JSONObject().put("ok", true).put("position", queue.size()));
                return;
            }
            case "/api/remove":
                queue.remove(body.optLong("key"));
                sendJson(out, new JSONObject().put("ok", true));
                return;
            case "/api/move":
                queue.move(body.optLong("key"), body.optInt("dir"));
                sendJson(out, new JSONObject().put("ok", true));
                return;
            case "/api/clear":
                queue.clear();
                sendJson(out, new JSONObject().put("ok", true));
                return;
            case "/api/skip":
                controller.skip();
                sendJson(out, new JSONObject().put("ok", true));
                return;
            case "/api/pause":
                controller.togglePause();
                sendJson(out, new JSONObject().put("ok", true));
                return;
            case "/api/restart":
                controller.restart();
                sendJson(out, new JSONObject().put("ok", true));
                return;
            case "/api/key":
                controller.changeKey(body.optInt("delta"));
                sendJson(out, new JSONObject().put("ok", true));
                return;
            case "/api/rescan":
                new Thread(library::scan).start();
                sendJson(out, new JSONObject().put("ok", true));
                return;
            case "/api/settings": {
                if (method.equals("POST") && body.has("ytKey")) {
                    prefs().edit().putString("ytKey", body.optString("ytKey").trim()).apply();
                }
                String key = prefs().getString("ytKey", "");
                sendJson(out, new JSONObject().put("hasKey", !key.isEmpty()));
                return;
            }
            default:
                sendError(out, 404, "not found");
        }
    }

    private void sendAsset(OutputStream out, String name, String type) throws Exception {
        InputStream in = context.getAssets().open(name);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        send(out, 200, type, bo.toByteArray());
    }

    private void sendJson(OutputStream out, JSONObject o) throws Exception {
        send(out, 200, "application/json; charset=utf-8", o.toString().getBytes("UTF-8"));
    }

    private void sendError(OutputStream out, int code, String msg) throws Exception {
        send(out, code, "application/json; charset=utf-8",
                new JSONObject().put("ok", false).put("error", msg).toString().getBytes("UTF-8"));
    }

    private void send(OutputStream out, int code, String type, byte[] body) throws Exception {
        String status = code == 200 ? "OK" : (code == 404 ? "Not Found" : "Error");
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') bo.write(c);
            if (bo.size() > 16384) break;
        }
        if (c < 0 && bo.size() == 0) return null;
        return bo.toString("UTF-8");
    }

    private static void parseParams(String qs, Map<String, String> out) {
        for (String kv : qs.split("&")) {
            int e = kv.indexOf('=');
            try {
                if (e > 0) out.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                else if (!kv.isEmpty()) out.put(URLDecoder.decode(kv, "UTF-8"), "");
            } catch (Exception ignored) { }
        }
    }
}
