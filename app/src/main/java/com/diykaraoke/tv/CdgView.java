package com.diykaraoke.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Choreographer;
import android.view.View;

/** Draws CD+G graphics in sync with the audio clock. */
public class CdgView extends View implements Choreographer.FrameCallback {

    public interface Clock { long positionMs(); }

    private CdgDecoder decoder;
    private Clock clock;
    private final Bitmap bitmap = Bitmap.createBitmap(CdgDecoder.WIDTH, CdgDecoder.HEIGHT, Bitmap.Config.ARGB_8888);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private boolean running;

    public CdgView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
    }

    public void start(CdgDecoder d, Clock c) {
        decoder = d;
        clock = c;
        decoder.reset();
        decoder.advanceTo(0);
        bitmap.setPixels(decoder.pixels, 0, CdgDecoder.WIDTH, 0, 0, CdgDecoder.WIDTH, CdgDecoder.HEIGHT);
        if (!running) {
            running = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
        invalidate();
    }

    public void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        decoder = null;
        clock = null;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        CdgDecoder d = decoder;
        Clock c = clock;
        if (d != null && c != null) {
            long pos = c.positionMs();
            if (pos >= 0 && d.advanceTo(pos)) {
                bitmap.setPixels(d.pixels, 0, CdgDecoder.WIDTH, 0, 0, CdgDecoder.WIDTH, CdgDecoder.HEIGHT);
                invalidate();
            }
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        CdgDecoder d = decoder;
        canvas.drawColor(d != null ? d.getBorderColor() : Color.BLACK);
        if (d == null) return;
        float w = getWidth(), h = getHeight();
        float aspect = (float) CdgDecoder.WIDTH / CdgDecoder.HEIGHT;
        float dw = w, dh = w / aspect;
        if (dh > h) { dh = h; dw = h * aspect; }
        dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
        canvas.drawBitmap(bitmap, null, dst, paint);
    }
}
