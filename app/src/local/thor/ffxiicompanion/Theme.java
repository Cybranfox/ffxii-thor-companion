package local.thor.ffxiicompanion;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.21.0 look: in the spirit of FFXII's menus (deep navy, translucent bordered panels, cream text, pale-blue highlight
 * bar with a bobbing pointer and a passing sheen) without using any game asset. Everything here is drawn in code and is
 * original to this project (see CREDITS.md). One shared animator drives all animated drawables, about 30 fps, and is
 * paused when the app is not visible.
 */
final class Theme {
    // palette (ARGB)
    static final int BG = 0xFF070D1F, BG2 = 0xFF14204A;                  // background gradient top / bottom
    static final int PANEL = 0xC0101C3C, PANEL_EDGE = 0x668FB4E8;        // translucent panel + thin edge
    static final int TEXT = 0xFFF2ECDA, DIM = 0xFF98A6C2;                // cream text, muted blue-grey
    static final int ACCENT = 0xFF9CC8FF, GOLD = 0xFFE6C36E;             // highlight blue, "owned" gold
    static final int HILITE_A = 0xFFD9ECFF, HILITE_B = 0xFF86B6F0;       // selection bar gradient
    static final int ON_HILITE = 0xFF0A1430;                             // text on the selection bar

    private static final List<Drawable> live = new ArrayList<>();
    private static ValueAnimator clock;
    static float phase;                                                    // 0..1, loops every LOOP_MS
    static final long LOOP_MS = 4000;

    static void start() {
        if (clock != null) { if (!clock.isStarted()) clock.start(); else clock.resume(); return; }
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(LOOP_MS);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            long now = System.currentTimeMillis();
            for (int i = live.size() - 1; i >= 0; i--) {
                Drawable d = live.get(i);
                // a highlight whose view was dropped (rows are rebuilt often) has no callback: stop animating it
                if (d.getCallback() == null && now - ((Selected) d).born > 2000) live.remove(i);
                else d.invalidateSelf();
            }
        });
        clock.start();
    }

    static void pause() { if (clock != null) clock.pause(); }

    /** Panel: translucent navy with a thin pale edge (FFXII-style window). */
    static GradientDrawable panel(float radiusPx, float edgePx) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xC8142452, PANEL});
        g.setCornerRadius(radiusPx);
        g.setStroke(Math.max(1, Math.round(edgePx)), PANEL_EDGE);
        return g;
    }

    /** The selection highlight: pale gradient bar, a pointer that bobs at the left edge and a sheen that sweeps across. */
    static Drawable selected(float radiusPx, float unitPx) {
        Selected d = new Selected(radiusPx, unitPx);
        live.add(d);
        return d;
    }

    /** Stop animating drawables whose views are gone (called when a screen rebuilds its rows). */
    static void forget(Drawable d) { live.remove(d); }

    static final class Selected extends Drawable {
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG),
                pointer = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float r, u;
        private final RectF box = new RectF();
        private final Path tri = new Path();
        final long born = System.currentTimeMillis();

        Selected(float radiusPx, float unitPx) {
            r = radiusPx; u = unitPx;
            pointer.setColor(GOLD);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, u * 0.75f));
            edge.setColor(0xCCFFFFFF);
        }

        @Override public void draw(Canvas c) {
            box.set(getBounds());
            if (box.isEmpty()) return;
            bar.setShader(new LinearGradient(0, box.top, 0, box.bottom, HILITE_A, HILITE_B, Shader.TileMode.CLAMP));
            c.drawRoundRect(box, r, r, bar);
            // sheen: a soft white band crossing the bar during the first 30% of each loop
            float p = phase / 0.3f;
            if (p < 1f) {
                float w = box.width() * 0.35f, x = box.left - w + (box.width() + w) * p;
                sheen.setShader(new LinearGradient(x, 0, x + w, 0, new int[]{0x00FFFFFF, 0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                c.save();
                c.clipRect(box);
                c.drawRect(x, box.top, x + w, box.bottom, sheen);
                c.restore();
            }
            c.drawRoundRect(box, r, r, edge);
            // pointer: small gold chevron left of the text, bobbing 2 units (sine over the loop, 4 bobs per loop)
            float bob = (float) Math.sin(phase * Math.PI * 8) * u;
            float cy = box.centerY(), h = Math.min(box.height() * 0.5f, u * 10f), x0 = box.left + u * 2f + bob;
            tri.reset();
            tri.moveTo(x0, cy - h / 2);
            tri.lineTo(x0 + h * 0.7f, cy);
            tri.lineTo(x0, cy + h / 2);
            tri.close();
            c.drawPath(tri, pointer);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
