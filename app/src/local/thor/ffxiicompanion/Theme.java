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
    // palette (ARGB). 0.17.0: the game's menu grammar (UI_FFXII_Native_Spec_v2): one smoked band between black bars,
    // light grey text with a dark edge, dim blue-grey small-caps headings, hairlines instead of boxes.
    static final int BG = 0xFF000000, BG2 = 0xFF000000;                  // the black top/bottom bars
    static final int PANEL = 0x66101820, PANEL_EDGE = 0x55A6AFB1;        // small plates (buttons, set tabs)
    static final int TEXT = 0xFFDDDEDA, DIM = 0xFF9BA6AC;                // body text, subdued text
    static final int HEAD = 0xFF9BA6AC, MUTED = 0xFF7C848A;              // section headings, unavailable
    static final int RULE = 0x3BA6AFB1;                                  // hairline separators
    static final int OLIVE = 0xFFB5AE7B;                                 // selection wash
    static final int ON_GOLD = 0xFFE5D3AB;                               // the gambit list's "ON"
    // 0.17.0: a chosen item that is not the cursor (member, filter, option): the olive wash and a gold edge, no glove.
    // Only one glove shows at a time, on the page navigation, as the game has one cursor.
    static final int MARK = 0xFFB5AE7C;
    // 0.17.0: the game's parchment pages (bestiary, clan primer): our own paper tone, ink and accent
    static final int INK = 0xFF302F2B, INK_DIM = 0xFF5E5646, INK_ACCENT = 0xFF6E4A2C;

    /** Our own paper: a warm radial gradient darkening to the edges (no texture, nothing copied). */
    static GradientDrawable paper(float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{0xFFCEBE9C, 0xFFB9A57F, 0xFF8A7B5E});
        g.setGradientRadius(radiusPx);
        g.setGradientCenter(0.45f, 0.4f);
        return g;
    }

    /**
     * 0.17.0: a gambit row capsule as in the game's list: blue for rules about allies/self, warm brown for foes, dark
     * grey when OFF; a vertical gradient (bright lip on top, darker body), fully rounded ends and a thin dark contour.
     */
    static GradientDrawable capsule(int kind, float unitPx) {          // kind 0 ally/self, 1 foe, 2 off/empty
        int[] g = kind == 0 ? new int[]{0xFF6DA2B5, 0xFF547D99, 0xFF3F5872}
                : kind == 1 ? new int[]{0xFFA37F6A, 0xFF8E695D, 0xFF6C4D48}
                : new int[]{0xCC3A3D44, 0xCC2C2F35, 0xCC24262B};
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, g);
        d.setCornerRadius(unitPx * 40f);
        d.setStroke(Math.max(1, Math.round(unitPx * 0.8f)), kind == 2 ? 0xFF5C5F65 : 0xFF14181C);
        return d;
    }
    static final int ACCENT = 0xFF9CC8FF, GOLD = 0xFFE6C36E;             // highlight blue, "owned" gold
    static final int HILITE_A = 0xFFD9ECFF, HILITE_B = 0xFF86B6F0;       // selection bar gradient
    static final int ON_HILITE = 0xFF0A1430;                             // text on the selection bar

    static final boolean GAME_LOOK = true;                              // 0.17.0 native-menu look
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

    // 0.17.0 fonts (SIL OFL, bundled in assets/fonts with their licences): Roboto for text, Michroma for big numbers.
    static android.graphics.Typeface body = android.graphics.Typeface.SANS_SERIF, bodyMedium = android.graphics.Typeface.DEFAULT_BOLD,
            numerals = android.graphics.Typeface.SANS_SERIF;

    static void init(android.content.Context c) {
        try {
            body = new android.graphics.Typeface.Builder(c.getAssets(), "fonts/Roboto.ttf")
                    .setFontVariationSettings("'wdth' 92, 'wght' 400").build();
            bodyMedium = new android.graphics.Typeface.Builder(c.getAssets(), "fonts/Roboto.ttf")
                    .setFontVariationSettings("'wdth' 92, 'wght' 500").build();
            numerals = android.graphics.Typeface.createFromAsset(c.getAssets(), "fonts/Michroma.ttf");
        } catch (RuntimeException e) {
            android.util.Log.w("FFXIICompanion", "fonts: " + e);             // system sans instead
        }
    }

    /** A section: no box, just a hairline along its top (the game separates by space and thin rules). */
    static Drawable section(float unitPx) {
        return new Drawable() {
            final Paint p = new Paint();
            @Override public void draw(Canvas c) {
                p.setColor(RULE);
                android.graphics.Rect b = getBounds();
                c.drawRect(b.left, b.top, b.right, b.top + Math.max(1f, unitPx * 0.5f), p);
            }
            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(ColorFilter cf) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    /** Plate: a small dark translucent plate with a thin pale edge (buttons, set tabs); sections use section(). */
    static GradientDrawable panel(float radiusPx, float edgePx) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x80202A33, PANEL});
        g.setCornerRadius(Math.min(radiusPx, edgePx * 2.5f));
        g.setStroke(Math.max(1, Math.round(edgePx)), PANEL_EDGE);
        return g;
    }

    /** The selection highlight: pale gradient bar, a pointer that bobs at the left edge and a sheen that sweeps across. */
    static Drawable selected(float radiusPx, float unitPx) {
        Selected d = new Selected(radiusPx, unitPx);
        live.add(d);
        return d;
    }

    /** Rounded background in the app's style: PANEL gets the panel, ACCENT the animated highlight, anything else a flat fill. */
    static Drawable fill(int color, float radiusPx, float unitPx) {
        if (color == PANEL) return panel(radiusPx, unitPx);
        if (color == ACCENT) return selected(radiusPx, unitPx);
        if (color == MARK) {
            Selected d = new Selected(radiusPx, unitPx);
            d.glove = false;
            return d;
        }
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static android.widget.TextView label(android.content.Context c, String s, int sp, int color, boolean bold) {
        android.widget.TextView t = new android.widget.TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(bold ? bodyMedium : body);
        t.setShadowLayer(1.1f, 0f, 0.8f, 0xE6000000);                   // a dark edge so text reads over the smoke
        return t;
    }

    private static final Path GLOVE = new Path(), FOLDS = new Path();
    private static final Paint GLOVE_FILL = new Paint(Paint.ANTI_ALIAS_FLAG), GLOVE_EDGE = new Paint(Paint.ANTI_ALIAS_FLAG),
            GLOVE_FOLD = new Paint(Paint.ANTI_ALIAS_FLAG), GLOVE_SHADOW = new Paint(Paint.ANTI_ALIAS_FLAG);
    static {
        // 0.17.0: an original pointing glove, drawn for this app on a 60 x 44 grid (cuff left, index finger right).
        // It follows the common "pointing hand" cursor convention; its shape is our own, not traced from any game.
        GLOVE.moveTo(4, 12);                                   // cuff
        GLOVE.lineTo(12, 10);
        GLOVE.cubicTo(16, 6, 22, 4, 28, 6);                    // back of the hand up to the thumb
        GLOVE.cubicTo(32, 7, 33, 11, 30, 13);                  // thumb tip folding in
        GLOVE.lineTo(52, 13);                                  // top of the index finger
        GLOVE.cubicTo(58, 13, 58, 22, 52, 22);                 // finger tip
        GLOVE.lineTo(36, 22);                                  // underside of the index finger
        GLOVE.cubicTo(39, 23, 39, 28, 35, 28);                 // middle finger knuckle
        GLOVE.cubicTo(38, 29, 38, 34, 34, 34);                 // ring finger
        GLOVE.cubicTo(37, 35, 36, 40, 31, 39);                 // little finger
        GLOVE.lineTo(16, 38);
        GLOVE.lineTo(12, 36);
        GLOVE.lineTo(4, 34);
        GLOVE.close();
        FOLDS.moveTo(30, 22); FOLDS.lineTo(35, 22);
        FOLDS.moveTo(29, 28); FOLDS.lineTo(34, 28);
        FOLDS.moveTo(27, 34); FOLDS.lineTo(32, 34);
        FOLDS.moveTo(12, 11); FOLDS.lineTo(12, 36);            // cuff seam
        GLOVE_FILL.setColor(0xFFF4F3EE);
        GLOVE_EDGE.setStyle(Paint.Style.STROKE);
        GLOVE_EDGE.setStrokeWidth(2.2f);
        GLOVE_EDGE.setStrokeJoin(Paint.Join.ROUND);
        GLOVE_EDGE.setColor(0xFF15171A);
        GLOVE_FOLD.setStyle(Paint.Style.STROKE);
        GLOVE_FOLD.setStrokeWidth(1.6f);
        GLOVE_FOLD.setStrokeCap(Paint.Cap.ROUND);
        GLOVE_FOLD.setColor(0xFF8D9096);
        GLOVE_SHADOW.setColor(0x66000000);
    }

    /** Draws the glove with its top-left at (x, y), h tall (the grid is 60 x 44). */
    static void drawGlove(Canvas c, float x, float y, float h) {
        float s = h / 44f;
        c.save();
        c.translate(x + 2f * s, y + 3f * s);
        c.scale(s, s);
        c.drawPath(GLOVE, GLOVE_SHADOW);
        c.restore();
        c.save();
        c.translate(x, y);
        c.scale(s, s);
        c.drawPath(GLOVE, GLOVE_FILL);
        c.drawPath(FOLDS, GLOVE_FOLD);
        c.drawPath(GLOVE, GLOVE_EDGE);
        c.restore();
    }

    static final class Selected extends Drawable {
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG),
                pointer = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float r, u;
        private final RectF box = new RectF();
        private final Path tri = new Path();
        final long born = System.currentTimeMillis();
        boolean glove = true;
        private float shaderW = -1;

        Selected(float radiusPx, float unitPx) {
            r = radiusPx; u = unitPx;
            pointer.setColor(GOLD);
            edge.setColor(0x66B5AE7B);
        }

        @Override public void draw(Canvas c) {
            box.set(getBounds());
            if (box.isEmpty()) return;
            // 0.17.0: the game's selection: a flat olive wash fading out to the right, a thin line underneath
            if (shaderW != box.width()) {                       // only rebuilt when the size changes
                shaderW = box.width();
                bar.setShader(new LinearGradient(0, 0, shaderW, 0, new int[]{0x60B5AE7B, 0x3AB5AE7B, 0x00B5AE7B},
                        new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            }
            c.save();
            c.translate(box.left, 0);
            c.drawRect(0, box.top, box.width(), box.bottom, bar);
            c.restore();
            c.drawRect(box.left, box.bottom - Math.max(1f, u * 0.5f), box.right, box.bottom, edge);
            if (!glove) {                                        // a chosen item, not the cursor: a gold edge on the left
                c.drawRect(box.left, box.top, box.left + Math.max(2f, u), box.bottom, pointer);
                return;
            }
            // 0.17.0: the pointing glove at the left edge, a small idle nudge on a ~0.9 s cycle
            float bob = (float) Math.sin((android.os.SystemClock.uptimeMillis() % 900L) / 900.0 * Math.PI * 2) * u * 0.45f;
            float gh = Math.min(box.height() * 0.66f, u * 15f);
            drawGlove(c, box.left + u * 2f + bob, box.centerY() - gh / 2f, gh);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
