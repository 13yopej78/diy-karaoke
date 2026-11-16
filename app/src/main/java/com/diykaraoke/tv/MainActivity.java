package com.diykaraoke.tv;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity implements KaraokeServer.Controller, QueueManager.Listener {

    private enum Mode { IDLE, INTRO, LOADING, CDG, VIDEO, MIDI, YOUTUBE }

    private static final int INTRO_MS = 5000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Library library = new Library();
    private final QueueManager queue = new QueueManager();
    private KaraokeServer server;

    // Views
    private FrameLayout root;
    private CdgView cdgView;
    private LyricsView lyricsView;
    private VideoView videoView;
    private WebView webView;
    private LinearLayout splash;
    private TextView splashUrl, splashStatus, splashQueue;
    private ImageView qrImage;
    private TextView introText;
    private TextView banner;
    private TextView toast;

    // Playback state (touched only on the UI thread)
    private Mode mode = Mode.IDLE;
    private int playToken = 0;
    private MediaPlayer player;      // CDG / MIDI audio, or the VideoView's player
    private MediaPlayer videoPlayer; // set when player belongs to the VideoView
    private boolean paused = false;
    private int semitones = 0;
    private String serverUrl = "";
    private long lastSkipPress = 0;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ui.postDelayed(MainActivity.this::rescan, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        queue.setListener(this);
        server = new KaraokeServer(this, library, queue, this);
        server.start();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addDataScheme("file");
        registerReceiver(usbReceiver, f);

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        } else {
            rescan();
        }
        showIdle();
        refreshSplash();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        rescan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSplash();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!paused && (mode == Mode.CDG || mode == Mode.MIDI || mode == Mode.VIDEO || mode == Mode.YOUTUBE)) {
            togglePauseNow();
        }
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
        stopPlayback();
        server.stop();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void rescan() {
        new Thread(() -> {
            library.scan();
            ui.post(this::refreshSplash);
        }).start();
        refreshSplash();
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams match = new FrameLayout.LayoutParams(-1, -1);

        cdgView = new CdgView(this);
        root.addView(cdgView, match);

        lyricsView = new LyricsView(this);
        root.addView(lyricsView, new FrameLayout.LayoutParams(-1, -1));

        videoView = new VideoView(this);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(-1, -1);
        vlp.gravity = Gravity.CENTER;
        root.addView(videoView, vlp);

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setUserAgentString(ws.getUserAgentString().replace("; wv", ""));
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new YtBridge(), "Android");
        webView.setBackgroundColor(Color.BLACK);
        webView.setFocusable(false);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        buildSplash();
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));

        introText = new TextView(this);
        introText.setGravity(Gravity.CENTER);
        introText.setTextColor(Color.WHITE);
        introText.setTextSize(34);
        introText.setTypeface(Typeface.DEFAULT_BOLD);
        introText.setBackgroundColor(Color.rgb(30, 10, 60));
        root.addView(introText, new FrameLayout.LayoutParams(-1, -1));

        banner = new TextView(this);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(18);
        banner.setPadding(dp(16), dp(8), dp(16), dp(8));
        banner.setBackground(rounded(Color.argb(190, 0, 0, 0)));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2);
        blp.gravity = Gravity.TOP | Gravity.START;
        blp.setMargins(dp(24), dp(16), dp(24), 0);
        root.addView(banner, blp);

        toast = new TextView(this);
        toast.setTextColor(Color.WHITE);
        toast.setTextSize(18);
        toast.setPadding(dp(16), dp(8), dp(16), dp(8));
        toast.setBackground(rounded(Color.argb(220, 120, 20, 40)));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
        tlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        tlp.bottomMargin = dp(32);
        root.addView(toast, tlp);

        setContentView(root);
        hideAllLayers();
        banner.setVisibility(View.GONE);
        toast.setVisibility(View.GONE);
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        return g;
    }

    private void buildSplash() {
        splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.HORIZONTAL);
        splash.setGravity(Gravity.CENTER_VERTICAL);
        splash.setPadding(dp(48), dp(32), dp(48), dp(32));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(40, 10, 90), Color.rgb(120, 20, 90)});
        splash.setBackground(bg);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("🎤 DIY Karaoke");
        title.setTextColor(Color.rgb(255, 210, 60));
        title.setTextSize(40);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(title);

        TextView how = new TextView(this);
        how.setText("Scan the QR code with your phone (same Wi-Fi)\nto search songs and add them to the queue.");
        how.setTextColor(Color.WHITE);
        how.setTextSize(20);
        how.setPadding(0, dp(12), 0, dp(8));
        left.addView(how);

        splashUrl = new TextView(this);
        splashUrl.setTextColor(Color.rgb(140, 220, 255));
        splashUrl.setTextSize(24);
        splashUrl.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        left.addView(splashUrl);

        splashQueue = new TextView(this);
        splashQueue.setTextColor(Color.WHITE);
        splashQueue.setTextSize(20);
        splashQueue.setPadding(0, dp(20), 0, dp(12));
        left.addView(splashQueue);

        splashStatus = new TextView(this);
        splashStatus.setTextColor(Color.argb(170, 255, 255, 255));
        splashStatus.setTextSize(14);
        left.addView(splashStatus);

        splash.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        qrImage = new ImageView(this);
        qrImage.setBackgroundColor(Color.WHITE);
        qrImage.setPadding(dp(10), dp(10), dp(10), dp(10));
        splash.addView(qrImage, new LinearLayout.LayoutParams(dp(260), dp(260)));
    }

    private void refreshSplash() {
        String ip = localIp();
        String url = ip == null ? "(not connected to a network)" : "http://" + ip + ":" + KaraokeServer.PORT;
        if (!url.equals(serverUrl)) {
            serverUrl = url;
            splashUrl.setText(url);
            if (ip != null) qrImage.setImageBitmap(qr(url, 512));
        }
        StringBuilder q = new StringBuilder();
        List<QueueManager.Entry> list = queue.snapshot();
        if (list.isEmpty()) {
            q.append("The queue is empty — add a song from your phone!");
        } else {
            q.append("Up next:\n");
            for (int i = 0; i < Math.min(5, list.size()); i++) {
                QueueManager.Entry e = list.get(i);
                q.append(i + 1).append(". ").append(e.song.title);
                if (!e.singer.isEmpty()) q.append("  —  ").append(e.singer);
                q.append('\n');
            }
            if (list.size() > 5) q.append("… and ").append(list.size() - 5).append(" more");
        }
        splashQueue.setText(q.toString().trim());
        String perm = "";
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            perm = "  ⚠ Storage permission not granted — USB songs can't be read.";
        }
        splashStatus.setText((library.isScanning() ? "Scanning drives…" : library.info()) + perm
                + "\nRemote: OK = pause · ▶▶ or → twice = skip · ↑ = show queue");
        if (library.isScanning()) ui.postDelayed(this::refreshSplash, 1500);
    }

    private void hideAllLayers() {
        cdgView.setVisibility(View.GONE);
        lyricsView.setVisibility(View.GONE);
        videoView.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        splash.setVisibility(View.GONE);
        introText.setVisibility(View.GONE);
    }

    private void showIdle() {
        mode = Mode.IDLE;
        hideAllLayers();
        splash.setVisibility(View.VISIBLE);
        banner.setVisibility(View.GONE);
        refreshSplash();
    }

    private final Runnable hideBanner = () -> banner.setVisibility(View.GONE);
    private final Runnable hideToast = () -> toast.setVisibility(View.GONE);

    private void showBanner(String text, long ms) {
        banner.setText(text);
        banner.setVisibility(View.VISIBLE);
        banner.bringToFront();
        ui.removeCallbacks(hideBanner);
        ui.postDelayed(hideBanner, ms);
    }

    private void showToast(String text) {
        toast.setText(text);
        toast.setVisibility(View.VISIBLE);
        toast.bringToFront();
        ui.removeCallbacks(hideToast);
        ui.postDelayed(hideToast, 4000);
    }

    private String nowAndNextText() {
        QueueManager.Entry cur = queue.current();
        StringBuilder sb = new StringBuilder();
        if (cur != null) {
            sb.append("♪ ").append(cur.song.title);
            if (!cur.singer.isEmpty()) sb.append("  —  ").append(cur.singer);
        }
        QueueManager.Entry next = queue.peek();
        sb.append("\nNext: ");
        if (next == null) sb.append("(nothing queued)");
        else {
            sb.append(next.song.title);
            if (!next.singer.isEmpty()) sb.append("  —  ").append(next.singer);
        }
        sb.append("\nAdd songs: ").append(serverUrl);
        return sb.toString();
    }

    // ---------------------------------------------------------------- queue / playback flow

    @Override
    public void onQueueChanged() {
        ui.post(() -> {
            if (mode == Mode.IDLE && queue.size() > 0) {
                startNext();
            } else if (mode == Mode.IDLE) {
                refreshSplash();
            }
        });
    }

    private void startNext() {
        stopPlayback();
        QueueManager.Entry e = queue.next();
        if (e == null) {
            showIdle();
            return;
        }
        final int token = ++playToken;
        mode = Mode.INTRO;
        semitones = 0;
        paused = false;
        hideAllLayers();
        String who = e.singer.isEmpty() ? "" : "🎤  " + e.singer + "\n\n";
        introText.setText(who + e.song.title + "\n\n(" + e.song.typeLabel() + ")");
        introText.setVisibility(View.VISIBLE);
        ui.postDelayed(() -> {
            if (token == playToken) play(e, token);
        }, INTRO_MS);
    }

    private void songFinished(int token) {
        if (token != playToken) return;
        queue.clearCurrent();
        startNext();
    }

    private void failAndSkip(int token, String message) {
        if (token != playToken) return;
        showToast(message);
        stopPlayback();
        ui.postDelayed(() -> songFinished(token), 2500);
    }

    private void play(QueueManager.Entry e, int token) {
        mode = Mode.LOADING;
        Song s = e.song;
        switch (s.type) {
            case CDG:
                prepareCdg(s.path, s.cdgPath, token);
                break;
            case ZIP:
                worker.execute(() -> {
                    try {
                        File[] files = extractZip(new File(s.path));
                        ui.post(() -> prepareCdg(files[0].getPath(), files[1].getPath(), token));
                    } catch (Exception ex) {
                        ui.post(() -> failAndSkip(token, "Can't open zip: " + ex.getMessage()));
                    }
                });
                break;
            case MIDI:
                playMidi(s, token);
                break;
            case VIDEO:
                playVideo(s, token);
                break;
            case YOUTUBE:
                playYouTube(s, token);
                break;
        }
    }

    private void prepareCdg(String audioPath, String cdgPath, int token) {
        if (token != playToken) return;
        worker.execute(() -> {
            try {
                byte[] cdg = readAll(new File(cdgPath));
                ui.post(() -> startCdg(audioPath, new CdgDecoder(cdg), token));
            } catch (Exception ex) {
                ui.post(() -> failAndSkip(token, "Can't read CDG file: " + ex.getMessage()));
            }
        });
    }

    private void startCdg(String audioPath, CdgDecoder decoder, int token) {
        if (token != playToken) return;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(audioPath);
            mp.setOnCompletionListener(m -> songFinished(token));
            mp.setOnErrorListener((m, what, extra) -> { failAndSkip(token, "Audio error (" + what + ")"); return true; });
            mp.prepare();
            player = mp;
            hideAllLayers();
            cdgView.setVisibility(View.VISIBLE);
            mode = Mode.CDG;
            mp.start();
            cdgView.start(decoder, this::audioPosition);
            showBanner(nowAndNextText(), 8000);
        } catch (Exception ex) {
            failAndSkip(token, "Can't play audio: " + ex.getMessage());
        }
    }

    private void playMidi(Song s, int token) {
        worker.execute(() -> {
            try {
                byte[] data = readAll(new File(s.path));
                MidiLyrics lyrics = MidiLyrics.parse(data);
                File dir = new File(getCacheDir(), "midi");
                dir.mkdirs();
                File copy = new File(dir, "song.mid");
                try (OutputStream os = new FileOutputStream(copy)) { os.write(data); }
                ui.post(() -> startMidi(copy, lyrics, s.title, token));
            } catch (Exception ex) {
                ui.post(() -> failAndSkip(token, "Can't read MIDI file: " + ex.getMessage()));
            }
        });
    }

    private void startMidi(File file, MidiLyrics lyrics, String title, int token) {
        if (token != playToken) return;
        try {
            MediaPlayer mp = new MediaPlayer();
            try (FileInputStream fis = new FileInputStream(file)) {
                mp.setDataSource(fis.getFD());
            }
            mp.setOnCompletionListener(m -> songFinished(token));
            mp.setOnErrorListener((m, what, extra) -> { failAndSkip(token, "MIDI error (" + what + ")"); return true; });
            mp.prepare();
            player = mp;
            hideAllLayers();
            lyricsView.setVisibility(View.VISIBLE);
            mode = Mode.MIDI;
            mp.start();
            lyricsView.start(lyrics, lyrics.title.isEmpty() ? title : lyrics.title, this::audioPosition);
            showBanner(nowAndNextText(), 8000);
        } catch (Exception ex) {
            failAndSkip(token, "Can't play MIDI: " + ex.getMessage());
        }
    }

    private void playVideo(Song s, int token) {
        hideAllLayers();
        videoView.setVisibility(View.VISIBLE);
        videoView.setOnPreparedListener(mp -> {
            if (token != playToken) return;
            player = mp;
            videoPlayer = mp;
            mode = Mode.VIDEO;
            videoView.start();
            showBanner(nowAndNextText(), 8000);
        });
        videoView.setOnCompletionListener(mp -> songFinished(token));
        videoView.setOnErrorListener((mp, what, extra) -> {
            failAndSkip(token, "Can't play this video (" + what + ")");
            return true;
        });
        videoView.setVideoPath(s.path);
    }

    private void playYouTube(Song s, int token) {
        hideAllLayers();
        webView.setVisibility(View.VISIBLE);
        mode = Mode.YOUTUBE;
        ytToken = token;
        webView.loadUrl("http://127.0.0.1:" + KaraokeServer.PORT + "/ytplayer.html?v=" + s.id);
        showBanner(nowAndNextText(), 8000);
    }

    private volatile int ytToken = -1;

    private class YtBridge {
        @JavascriptInterface
        public void ended() {
            ui.post(() -> { if (mode == Mode.YOUTUBE && ytToken == playToken) songFinished(playToken); });
        }

        @JavascriptInterface
        public void error(int code) {
            ui.post(() -> {
                if (mode != Mode.YOUTUBE || ytToken != playToken) return;
                String msg = (code == 101 || code == 150)
                        ? "This YouTube video doesn't allow playing in other apps — skipping"
                        : "YouTube error " + code + " — skipping";
                failAndSkip(playToken, msg);
            });
        }

        @JavascriptInterface
        public void state(int s) {
            ui.post(() -> { if (mode == Mode.YOUTUBE) paused = (s == 2); });
        }
    }

    private long audioPosition() {
        MediaPlayer mp = player;
        if (mp == null) return -1;
        try { return mp.getCurrentPosition(); } catch (Exception e) { return -1; }
    }

    private void stopPlayback() {
        cdgView.stop();
        lyricsView.stop();
        boolean videoOwned = videoPlayer != null && player == videoPlayer;
        try { videoView.stopPlayback(); } catch (Exception ignored) { }
        videoPlayer = null;
        if (!videoOwned && player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
        }
        player = null;
        if (mode == Mode.YOUTUBE) webView.loadUrl("about:blank");
        ytToken = -1;
        paused = false;
    }

    // ---------------------------------------------------------------- Controller (called from server threads)

    @Override
    public void skip() { ui.post(this::skipNow); }

    @Override
    public void togglePause() { ui.post(this::togglePauseNow); }

    @Override
    public void restart() {
        ui.post(() -> {
            if (mode == Mode.YOUTUBE) webView.evaluateJavascript("restart()", null);
            else if (player != null) { try { player.seekTo(0); } catch (Exception ignored) { } }
        });
    }

    @Override
    public void changeKey(int delta) {
        ui.post(() -> {
            if (mode == Mode.YOUTUBE) { showToast("Key change isn't available for YouTube songs"); return; }
            if (player == null || Build.VERSION.SDK_INT < 23) return;
            int newKey = Math.max(-6, Math.min(6, semitones + delta));
            try {
                PlaybackParams pp = player.getPlaybackParams();
                pp.setSpeed(1f);
                pp.setPitch((float) Math.pow(2, newKey / 12.0));
                player.setPlaybackParams(pp);
                semitones = newKey;
                paused = false;
                showBanner("Key: " + (semitones > 0 ? "+" : "") + semitones, 2500);
            } catch (Exception e) {
                showToast("Key change isn't supported for this song");
            }
        });
    }

    @Override
    public JSONObject playerState() {
        JSONObject o = new JSONObject();
        try {
            o.put("mode", mode.name());
            o.put("paused", paused);
            o.put("key", semitones);
            o.put("url", serverUrl);
        } catch (Exception ignored) { }
        return o;
    }

    private void skipNow() {
        if (mode == Mode.IDLE) {
            if (queue.size() > 0) startNext();
            return;
        }
        playToken++;
        stopPlayback();
        queue.clearCurrent();
        startNext();
    }

    private void togglePauseNow() {
        switch (mode) {
            case CDG:
            case MIDI:
                if (player == null) return;
                if (paused) player.start(); else player.pause();
                paused = !paused;
                break;
            case VIDEO:
                if (paused) videoView.start(); else videoView.pause();
                paused = !paused;
                break;
            case YOUTUBE:
                webView.evaluateJavascript("togglePause()", null);
                paused = !paused;
                break;
            default:
                return;
        }
        if (paused) showBanner("⏸  Paused — press OK to continue", 600000);
        else banner.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- remote control

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        boolean playing = mode != Mode.IDLE;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (playing) { togglePauseNow(); return true; }
                if (queue.size() > 0) { startNext(); return true; }
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (playing) skipNow();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!playing) return true;
                long now = System.currentTimeMillis();
                if (now - lastSkipPress < 2500) {
                    lastSkipPress = 0;
                    skipNow();
                } else {
                    lastSkipPress = now;
                    showBanner("Press → again to skip this song", 2500);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU:
                if (playing) showBanner(nowAndNextText(), 8000);
                else rescan();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (playing) {
                    long t = System.currentTimeMillis();
                    if (t - lastSkipPress < 2500) {
                        lastSkipPress = 0;
                        skipNow();
                    } else {
                        lastSkipPress = t;
                        showBanner("Press BACK again to stop this song", 2500);
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    // ---------------------------------------------------------------- helpers

    private File[] extractZip(File zip) throws Exception {
        File dir = new File(getCacheDir(), "zip");
        if (dir.exists()) {
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
        }
        dir.mkdirs();
        File audio = null, cdg = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = new File(entry.getName()).getName();
                String lower = name.toLowerCase(Locale.ROOT);
                File out = null;
                if (lower.endsWith(".cdg")) out = cdg = new File(dir, "song.cdg");
                else if (lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".ogg") || lower.endsWith(".wav"))
                    out = audio = new File(dir, "song" + lower.substring(lower.lastIndexOf('.')));
                if (out == null) continue;
                try (OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
        if (audio == null || cdg == null) throw new Exception("zip doesn't contain both an mp3 and a cdg");
        return new File[]{audio, cdg};
    }

    private static byte[] readAll(File f) throws Exception {
        long len = f.length();
        if (len > 200L * 1024 * 1024) throw new Exception("file too large");
        byte[] buf = new byte[(int) len];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return buf;
    }

    private static String localIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        String n = ni.getName();
                        if (n.startsWith("eth") || n.startsWith("wlan")) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap qr(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }
}
