package com.diykaraoke.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.Choreographer;
import android.view.View;

import java.util.List;

/** Shows MIDI/KAR lyrics with the sung part highlighted. */
public class LyricsView extends View implements Choreographer.FrameCallback {

    private static final int SUNG = Color.rgb(255, 210, 60);
    private static final int UNSUNG = Color.WHITE;
    private static final int DIM = Color.argb(120, 255, 255, 255);

    private MidiLyrics lyrics;
    private CdgView.Clock clock;
    private String title = "";
    private boolean running;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LyricsView(Context c) {
        super(c);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        paint.setShadowLayer(6, 3, 3, Color.BLACK);
        titlePaint.setColor(Color.argb(200, 255, 255, 255));
        titlePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
    }

    public void start(MidiLyrics l, String title, CdgView.Clock c) {
        lyrics = l;
        clock = c;
        this.title = title;
        if (!running) {
            running = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
        invalidate();
    }

    public void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        lyrics = null;
        clock = null;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, 0, h, Color.rgb(20, 10, 60), Color.rgb(70, 10, 70), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, bg);

        titlePaint.setTextSize(h / 22f);
        canvas.drawText(title, w * 0.05f, h * 0.08f, titlePaint);

        MidiLyrics l = lyrics;
        CdgView.Clock c = clock;
        if (l == null || c == null) return;
        long now = c.positionMs();
        List<MidiLyrics.Line> lines = l.lines;
        if (lines.isEmpty()) {
            paint.setTextSize(h / 14f);
            paint.setColor(UNSUNG);
            drawCentered(canvas, "♪  (this MIDI file has no lyrics)  ♪", w, h * 0.5f);
            return;
        }

        int cur = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startMs() <= now + 400) cur = i; else break;
        }
        float baseSize = h / 11f;
        float lineGap = h / 6.5f;
        float y0 = h * 0.42f;

        for (int k = -1; k <= 2; k++) {
            int idx = cur + k;
            if (idx < 0 || idx >= lines.size()) continue;
            MidiLyrics.Line line = lines.get(idx);
            String text = line.text().trim();
            paint.setTextSize(baseSize);
            float tw = paint.measureText(text);
            if (tw > w * 0.92f) {
                paint.setTextSize(baseSize * (w * 0.92f) / tw);
                tw = paint.measureText(text);
            }
            float y = y0 + k * lineGap;
            float x = (w - tw) / 2f;
            if (k < 0) {
                paint.setColor(DIM);
                canvas.drawText(text, x, y, paint);
            } else if (k == 0) {
                float sung = sungWidth(line, now, text);
                paint.setColor(UNSUNG);
                canvas.drawText(text, x, y, paint);
                if (sung > 0) {
                    canvas.save();
                    canvas.clipRect(x, y - paint.getTextSize() * 1.2f, x + sung, y + paint.getTextSize() * 0.5f);
                    paint.setColor(SUNG);
                    canvas.drawText(text, x, y, paint);
                    canvas.restore();
                }
            } else {
                paint.setColor(UNSUNG);
                canvas.drawText(text, x, y, paint);
            }
        }
    }

    /** Width of the already-sung part of a line (with smooth progress through the current syllable). */
    private float sungWidth(MidiLyrics.Line line, long now, String trimmed) {
        List<MidiLyrics.Syllable> syl = line.syllables;
        String full = line.text();
        int lead = full.length() - full.replaceAll("^\\s+", "").length();
        StringBuilder done = new StringBuilder();
        float width = 0;
        for (int i = 0; i < syl.size(); i++) {
            MidiLyrics.Syllable s = syl.get(i);
            if (now < s.timeMs) break;
            long next = i + 1 < syl.size() ? syl.get(i + 1).timeMs : s.timeMs + 600;
            float frac = Math.min(1f, (now - s.timeMs) / (float) Math.max(80, Math.min(800, next - s.timeMs)));
            float before = paint.measureText(sub(done.toString(), lead));
            done.append(s.text);
            float after = paint.measureText(sub(done.toString(), lead));
            width = before + (after - before) * frac;
        }
        return Math.min(width, paint.measureText(trimmed));
    }

    private static String sub(String s, int lead) {
        return s.length() <= lead ? "" : s.substring(lead);
    }

    private void drawCentered(Canvas canvas, String text, int w, float y) {
        float tw = paint.measureText(text);
        canvas.drawText(text, (w - tw) / 2f, y, paint);
    }
}
