package local.thor.ffxiicompanion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Draws the current area among its region's explored areas (Regions.View), tile by tile, only where each part's
 * "visible" grid allows it: hidden tiles are not drawn at all (no outline, no silhouette), so nothing unexplored
 * leaks. Layout as in the game's region view: sheet pixel (px, py) of a part sits at layout (x + px/4, y + py/4).
 * The view is centred on the current area with a margin, so neighbours show at its edges like the game's screen.
 * Colours are the companion's own (the game's tint is unknown). The "you are here" marker (Position) is drawn on
 * the current area at sheet px (px, py): solid when the feed is live, hollow when it is only the last known spot.
 */
final class RevealMapView extends View {
    static final int TILE = Reveal.TILE;
    static final float UNITS_PER_PX = 0.25f;
    /** Margin around the current area, as a fraction of its larger side. */
    static final float MARGIN = 0.55f;

    private List<Regions.Part> parts = new ArrayList<>();
    private List<Regions.Link> links = new ArrayList<>();
    private Map<String, Bitmap> masks;
    private int sample = 1;
    private final Paint focusPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint otherPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint linkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Float markerPx, markerPy;
    private boolean markerLive;
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    RevealMapView(Context c, int focusTone, int otherTone, int linkColor) {
        super(c);
        focusPaint.setColorFilter(tint(focusTone));
        otherPaint.setColorFilter(tint(otherTone));
        linkPaint.setColor(linkColor);
        linkPaint.setStrokeCap(Paint.Cap.ROUND);
        markerFill.setColor(0xFFFF8C28);
        markerRing.setStyle(Paint.Style.STROKE);
    }

    /** Marker in the current area's sheet pixels; null hides it. */
    void setMarker(Float px, Float py, boolean live) {
        markerPx = px;
        markerPy = py;
        markerLive = live;
        invalidate();
    }

    /** Grey mask (R = G = B = coverage) -> constant tone with alpha = coverage. */
    static ColorMatrixColorFilter tint(int tone) {
        float r = (tone >> 16) & 0xFF, g = (tone >> 8) & 0xFF, b = tone & 0xFF;
        return new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                0, 0, 0, 0, r,
                0, 0, 0, 0, g,
                0, 0, 0, 0, b,
                1, 0, 0, 0, 0 }));
    }

    /** masks: sheet file -> bitmap decoded with the given inSampleSize. */
    void set(List<Regions.Part> parts, List<Regions.Link> links, Map<String, Bitmap> masks, int sample) {
        this.parts = parts != null ? parts : new ArrayList<>();
        this.links = links != null ? links : new ArrayList<>();
        this.masks = masks;
        this.sample = sample;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (masks == null || parts.isEmpty()) return;
        Regions.Part focus = null;
        for (Regions.Part p : parts) if (p.focus) focus = p;
        if (focus == null) return;
        // Viewport (layout units): the current area's visible tiles plus a margin.
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < focus.visible.length; y++) {
            for (int x = 0; x < focus.visible[y].length; x++) {
                if (!focus.visible[y][x]) continue;
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
            }
        }
        if (x1 < 0) return;
        float tu = TILE * UNITS_PER_PX;
        float vx0 = focus.x + x0 * tu, vy0 = focus.y + y0 * tu, vx1 = focus.x + (x1 + 1) * tu, vy1 = focus.y + (y1 + 1) * tu;
        float m = Math.max(vx1 - vx0, vy1 - vy0) * MARGIN;
        vx0 -= m; vy0 -= m; vx1 += m; vy1 += m;
        float scale = Math.min(getWidth() / (vx1 - vx0), getHeight() / (vy1 - vy0));
        float ox = (getWidth() - (vx1 - vx0) * scale) / 2f - vx0 * scale;
        float oy = (getHeight() - (vy1 - vy0) * scale) / 2f - vy0 * scale;

        for (Regions.Part p : parts) if (!p.focus) drawPart(canvas, p, otherPaint, scale, ox, oy);
        linkPaint.setStrokeWidth(Math.max(1.5f, 2.5f * scale));
        for (Regions.Link l : links) canvas.drawLine(ox + l.ax * scale, oy + l.ay * scale, ox + l.bx * scale, oy + l.by * scale, linkPaint);
        drawPart(canvas, focus, focusPaint, scale, ox, oy);
        if (markerPx != null && markerPy != null) {
            float d = getResources().getDisplayMetrics().density;
            float cx = ox + (focus.x + markerPx * UNITS_PER_PX) * scale, cy = oy + (focus.y + markerPy * UNITS_PER_PX) * scale;
            float r = 6 * d;
            markerRing.setStrokeWidth(2.5f * d);
            markerRing.setColor(markerLive ? 0xFFFFFFFF : 0xFFFF8C28);
            if (markerLive) canvas.drawCircle(cx, cy, r, markerFill);
            canvas.drawCircle(cx, cy, r, markerRing);
        }
    }

    private void drawPart(Canvas canvas, Regions.Part p, Paint paint, float scale, float ox, float oy) {
        Bitmap bm = masks.get(p.sheet.file);
        if (bm == null) return;
        float tu = TILE * UNITS_PER_PX * scale;
        int ts = TILE / sample;
        for (int y = 0; y < p.visible.length; y++) {
            for (int x = 0; x < p.visible[y].length; x++) {
                if (!p.visible[y][x]) continue;
                src.set(x * ts, y * ts, (x + 1) * ts, (y + 1) * ts);
                float dx = ox + p.x * scale + x * tu, dy = oy + p.y * scale + y * tu;
                dst.set(dx, dy, dx + tu, dy + tu);
                canvas.drawBitmap(bm, src, dst, paint);
            }
        }
    }
}
