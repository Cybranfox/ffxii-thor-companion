package local.thor.ffxiicompanion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Draws the current area among its region's explored areas (Regions.View), tile by tile, only where each part's
 * "visible" grid allows it: hidden tiles are not drawn at all (no outline, no silhouette), so nothing unexplored
 * leaks. Layout as in the game's region view: sheet pixel (px, py) of a part sits at layout (x + px/4, y + py/4).
 * Colours are the companion's own (the game's tint is not recovered). The "you are here" marker (Position) is drawn
 * on the current area at sheet px (px, py): solid when the feed is live, hollow when it is only the last known spot.
 * Exit labels (0.12.0) are drawn centred on their anchor; one that would overlap a label already drawn moves a line
 * down or up, and is left out only when there is no room. A locked exit's label is drawn faded, as the game dims it
 * (0.13.0). Map symbols (0.13.0) are the game's own pictures, centred on their point at a fixed on-screen size.
 *
 * Two uses:
 * - the Status card (not interactive): centred on the current area with a margin, so neighbours show at its edges;
 * - the full map (interactive, 0.12.0): fits everything explored in the region; pinch to zoom, drag to pan, double tap
 *   to switch between the whole region and the current area. The view resets when the region changes.
 */
final class RevealMapView extends View {
    static final int TILE = Reveal.TILE;
    static final float UNITS_PER_PX = 0.25f;
    /** Card: margin around the current area, as a fraction of its larger side. */
    static final float MARGIN = 0.55f;
    /** Full map: margin around the explored region. */
    static final float REGION_MARGIN = 0.06f;
    static final float MIN_ZOOM = 1f, MAX_ZOOM = 8f;
    /** Where a clashing label may move, in lines from its anchor. */
    static final float[] LABEL_SHIFTS = { 0f, 1.1f, -1.1f, 2.2f, -2.2f };

    interface OnMoved { void moved(boolean moved); }

    private List<Regions.Part> parts = new ArrayList<>();
    private List<Regions.Link> links = new ArrayList<>();
    private List<Regions.Exit> exits = new ArrayList<>();
    private java.util.Set<Regions.Exit> dimmed = new java.util.HashSet<>();
    private List<Regions.Shown> shown = new ArrayList<>();
    private Map<Integer, Bitmap> symbolArt;
    private final float symbolPx;
    private final Paint symbolPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Map<String, Bitmap> masks;
    private int sample = 1;
    private final Paint focusPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint otherPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint linkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Float markerPx, markerPy;
    private boolean markerLive;
    private final Map<Regions.Part, Bitmap> fog = new java.util.IdentityHashMap<>();   // 0.16.0: soft edges per part
    private final Map<String, Object[]> fogCache = new java.util.HashMap<>();           // sheet -> {cells, bitmap or null}
    private final Paint fogPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final List<RectF> taken = new ArrayList<>();

    private final boolean interactive;
    private String region;
    private boolean areaFit;           // full map: fit the current area instead of the whole region
    private float zoom = 1f, panX, panY;
    private OnMoved onMoved;
    private ScaleGestureDetector scaler;
    private GestureDetector gestures;

    RevealMapView(Context c, int focusTone, int otherTone, int linkColor, int labelColor, int haloColor,
                  float labelSp, boolean interactive) {
        super(c);
        this.interactive = interactive;
        fogPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        focusPaint.setColorFilter(tint(focusTone));
        otherPaint.setColorFilter(tint(otherTone));
        linkPaint.setColor(linkColor);
        linkPaint.setStrokeCap(Paint.Cap.ROUND);
        markerFill.setColor(0xFFFF8C28);
        markerRing.setStyle(Paint.Style.STROKE);
        float px = labelSp * c.getResources().getDisplayMetrics().scaledDensity;
        symbolPx = labelSp * 1.5f * c.getResources().getDisplayMetrics().density;
        for (Paint p : new Paint[] { label, labelHalo }) {
            p.setTextSize(px);
            p.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            p.setTextAlign(Paint.Align.CENTER);
        }
        label.setColor(labelColor);
        labelHalo.setColor(haloColor);
        labelHalo.setStyle(Paint.Style.STROKE);
        labelHalo.setStrokeWidth(px * 0.28f);
        labelHalo.setStrokeJoin(Paint.Join.ROUND);
        if (!interactive) return;
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                panX -= dx;
                panY -= dy;
                changed();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                areaFit = !areaFit;
                zoom = 1f; panX = 0; panY = 0;
                changed();
                return true;
            }
        });
    }

    void setOnMoved(OnMoved l) { onMoved = l; }

    /** The symbol pictures by type (from the atlas, checked against their hashes); null draws none. */
    void setSymbolArt(Map<Integer, Bitmap> art) {
        symbolArt = art;
        invalidate();
    }

    /** Full map: back to the whole explored region, unzoomed. */
    void recentre() {
        areaFit = false;
        zoom = 1f; panX = 0; panY = 0;
        changed();
    }

    boolean moved() { return areaFit || zoom != 1f || panX != 0 || panY != 0; }

    private void changed() {
        invalidate();
        if (onMoved != null) onMoved.moved(moved());
    }

    private void zoomBy(float f, float fx, float fy) {
        // 0.16.0: pinching out past "this area" carries on into the whole region (it used to stop at the area's fit)
        if (areaFit && zoom * f < MIN_ZOOM) {
            float[] a = fitView(true), r = fitView(false);
            if (a != null && r != null && r[0] < a[0]) {   // only when the region view is wider (else stay put)
                float total = a[0] * zoom;                       // keep the picture where it is while switching
                panX += (r[1] - a[1]) * total;
                panY += (r[2] - a[2]) * total;
                zoom = total / r[0];
                areaFit = false;
            }
        }
        float z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * f));
        f = z / zoom;
        // keep the point under the fingers still: the view is centred, so work relative to the centre
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        panX = (fx - cx) - ((fx - cx) - panX) * f;
        panY = (fy - cy) - ((fy - cy) - panY) * f;
        zoom = z;
        if (!areaFit && zoom <= MIN_ZOOM) { panX = 0; panY = 0; }   // all the way out: back to the normal view
        changed();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!interactive) return super.onTouchEvent(e);
        getParent().requestDisallowInterceptTouchEvent(true);
        boolean a = scaler.onTouchEvent(e);
        boolean b = scaler.isInProgress() || gestures.onTouchEvent(e);
        return a || b;
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

    /** masks: sheet file -> bitmap decoded with the given inSampleSize. A new region resets the full map's zoom. */
    void set(Regions.View v, List<Regions.Part> parts, Map<String, Bitmap> masks, int sample) {
        this.parts = parts != null ? parts : new ArrayList<>();
        this.links = v != null ? v.links : new ArrayList<>();
        this.exits = v != null ? v.exits : new ArrayList<>();
        this.dimmed = v != null ? v.dimmed : new java.util.HashSet<>();
        this.shown = v != null ? v.symbols : new ArrayList<>();
        this.masks = masks;
        this.sample = sample;
        fog.clear();
        Map<String, Object[]> keep = new java.util.HashMap<>();
        for (Regions.Part p : this.parts) {
            boolean[][] cells = p.revealed != null ? p.revealed : p.visible;
            if (p.sheet == null || cells == null) continue;
            Object[] c = fogCache.get(p.sheet.file);             // rebuilt only when the explored cells change
            if (c == null || !java.util.Arrays.deepEquals((boolean[][]) c[0], cells)) c = new Object[] { copy(cells), fogBitmap(cells) };
            keep.put(p.sheet.file, c);
            if (c[1] != null) fog.put(p, (Bitmap) c[1]);
        }
        fogCache.clear();
        fogCache.putAll(keep);
        String r = v != null ? v.region : null;
        if (interactive && (r == null ? region != null : !r.equals(region))) {
            region = r;
            areaFit = false;
            zoom = 1f; panX = 0; panY = 0;
            if (onMoved != null) onMoved.moved(false);
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (masks == null || parts.isEmpty()) return;
        Regions.Part focus = null;
        for (Regions.Part p : parts) if (p.focus) focus = p;
        if (focus == null) return;
        float[] v = fitView(!interactive || areaFit);
        if (v == null) return;
        float scale = v[0] * zoom;
        float ox = getWidth() / 2f + panX - v[1] * scale;
        float oy = getHeight() / 2f + panY - v[2] * scale;

        for (Regions.Part p : parts) if (!p.focus) drawPart(canvas, p, otherPaint, scale, ox, oy);
        linkPaint.setStrokeWidth(Math.max(1.5f, 2.5f * scale));
        for (Regions.Link l : links) canvas.drawLine(ox + l.ax * scale, oy + l.ay * scale, ox + l.bx * scale, oy + l.by * scale, linkPaint);
        drawPart(canvas, focus, focusPaint, scale, ox, oy);
        drawSymbols(canvas, scale, ox, oy);
        float d = getResources().getDisplayMetrics().density;
        if (markerPx != null && markerPy != null) {
            float cx = ox + (focus.x + markerPx * UNITS_PER_PX) * scale, cy = oy + (focus.y + markerPy * UNITS_PER_PX) * scale;
            float r = 6 * d;
            markerRing.setStrokeWidth(2.5f * d);
            markerRing.setColor(markerLive ? 0xFFFFFFFF : 0xFFFF8C28);
            if (markerLive) canvas.drawCircle(cx, cy, r, markerFill);
            canvas.drawCircle(cx, cy, r, markerRing);
        }
        drawLabels(canvas, scale, ox, oy, d);
    }

    /** The part's soft-edge alpha, or null when it has no edge (nothing or everything explored: no layer needed). */
    private static Bitmap fogBitmap(boolean[][] cells) {
        int[] a = Regions.fogAlpha(cells);
        if (a == null) return null;
        boolean edge = false;
        for (int v : a) if (v == Regions.FOG_EDGE) { edge = true; break; }
        if (!edge) return null;
        int[] px = new int[a.length];
        for (int i = 0; i < a.length; i++) px[i] = a[i] << 24;
        return Bitmap.createBitmap(px, cells[0].length * 2, cells.length * 2, Bitmap.Config.ARGB_8888);
    }

    private static boolean[][] copy(boolean[][] c) {
        boolean[][] o = new boolean[c.length][];
        for (int i = 0; i < c.length; i++) o[i] = c[i].clone();
        return o;
    }

    /** The unzoomed fit: {scale, centre x, centre y} in layout units, for the area or the whole region; null = nothing. */
    private float[] fitView(boolean area) {
        float[] b = Regions.fit(parts, area, markerPx, markerPy);
        if (b == null) return null;
        if (interactive && !area) {
            // exits sit off the explored ground, at the end of their lines: fit them and their labels too
            for (Regions.Exit e : exits) {
                b[0] = Math.min(b[0], Math.min(e.x, e.lx)); b[1] = Math.min(b[1], Math.min(e.y, e.ly));
                b[2] = Math.max(b[2], Math.max(e.x, e.lx)); b[3] = Math.max(b[3], Math.max(e.y, e.ly));
            }
        }
        float m = interactive ? Math.max(b[2] - b[0], b[3] - b[1]) * (area ? MARGIN / 2 : REGION_MARGIN)
                : Math.max(b[2] - b[0], b[3] - b[1]) * MARGIN;
        float vx0 = b[0] - m, vy0 = b[1] - m, vx1 = b[2] + m, vy1 = b[3] + m;
        return new float[] { Math.min(getWidth() / (vx1 - vx0), getHeight() / (vy1 - vy0)), (vx0 + vx1) / 2f, (vy0 + vy1) / 2f };
    }

    private void drawSymbols(Canvas canvas, float scale, float ox, float oy) {
        if (symbolArt == null) return;
        for (Regions.Shown s : shown) {
            Bitmap bm = symbolArt.get(s.type);
            if (bm == null) continue;
            float h = symbolPx, w = h * bm.getWidth() / bm.getHeight();
            float cx = ox + s.x * scale, cy = oy + s.y * scale;
            if (s.x != s.sx || s.y != s.sy) canvas.drawLine(cx, cy, ox + s.sx * scale, oy + s.sy * scale, linkPaint);
            dst.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            canvas.drawBitmap(bm, null, dst, symbolPaint);
        }
    }

    private void drawLabels(Canvas canvas, float scale, float ox, float oy, float d) {
        taken.clear();
        Paint.FontMetrics fm = label.getFontMetrics();
        float pad = 2 * d;
        for (Regions.Exit e : exits) {
            float w = label.measureText(e.text);
            float cx = ox + e.lx * scale, cy = oy + e.ly * scale;
            if (cx < 0 || cy < 0 || cx > getWidth() || cy > getHeight()) continue;   // its exit is off screen
            // keep the whole label on screen
            cx = Math.max(w / 2 + pad, Math.min(getWidth() - w / 2 - pad, cx));
            float line = fm.descent - fm.ascent;
            float base = cy - (fm.ascent + fm.descent) / 2;
            // on a clash with a label already drawn, try a line below, then above, before leaving it out
            for (float shift : LABEL_SHIFTS) {
                float y = Math.max(-fm.ascent + pad, Math.min(getHeight() - fm.descent - pad, base + shift * line));
                RectF box = new RectF(cx - w / 2 - pad, y + fm.ascent - pad, cx + w / 2 + pad, y + fm.descent + pad);
                boolean clash = false;
                for (RectF t : taken) if (RectF.intersects(t, box)) { clash = true; break; }
                if (clash) continue;
                taken.add(box);
                int alpha = dimmed.contains(e) ? 0x70 : 0xFF;      // a locked route, faded like the game's
                label.setAlpha(alpha);
                labelHalo.setAlpha(alpha);
                canvas.drawText(e.text, cx, y, labelHalo);
                canvas.drawText(e.text, cx, y, label);
                label.setAlpha(0xFF);
                labelHalo.setAlpha(0xFF);
                break;
            }
        }
    }

    private void drawPart(Canvas canvas, Regions.Part p, Paint paint, float scale, float ox, float oy) {
        Bitmap bm = masks.get(p.sheet.file);
        if (bm == null) return;
        float tu = TILE * UNITS_PER_PX * scale;
        int ts = TILE / sample;
        Bitmap soft = fog.get(p);
        float px0 = ox + p.x * scale, py0 = oy + p.y * scale;
        RectF area = new RectF(px0, py0, px0 + p.visible[0].length * tu, py0 + p.visible.length * tu);
        // 0.16.0: soft edges. Draw the explored tiles and the game's edge tiles into a layer, then keep only what the
        // smoothly scaled fog alpha allows (explored solid, fading out across the edge corners).
        int layer = soft != null && area.intersects(0, 0, getWidth(), getHeight()) ? canvas.saveLayer(area, null) : -1;
        boolean[][] cells = p.revealed != null ? p.revealed : p.visible;
        for (int y = 0; y < p.visible.length; y++) {
            for (int x = 0; x < p.visible[y].length; x++) {
                boolean edge = layer >= 0 && !p.visible[y][x] && y < cells.length && x < cells[y].length
                        && !cells[y][x] && Regions.edgeMask(cells, x, y) != 0;
                if (!p.visible[y][x] && !edge) continue;
                src.set(x * ts, y * ts, (x + 1) * ts, (y + 1) * ts);
                float dx = px0 + x * tu, dy = py0 + y * tu;
                dst.set(dx, dy, dx + tu, dy + tu);
                canvas.drawBitmap(bm, src, dst, paint);
            }
        }
        if (layer >= 0) {
            canvas.drawBitmap(soft, null, area, fogPaint);
            canvas.restoreToCount(layer);
        }
    }
}
