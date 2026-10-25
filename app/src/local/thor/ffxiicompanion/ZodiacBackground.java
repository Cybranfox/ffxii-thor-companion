package local.thor.ffxiicompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * 0.21.0 animated background, drawn in code (original to this project; see CREDITS.md): a deep navy-to-indigo gradient,
 * a slow star field that twinkles, faint zodiac constellation lines (real-sky patterns: public-domain astronomy, not game
 * art) that drift very slowly, and two soft mist glows. Redraws at most ~20 fps while visible; cheap enough for the
 * bottom screen while the game runs.
 */
final class ZodiacBackground extends View {
    private static final int STARS = 90;
    // 12 zodiac constellations, simplified stick figures in a 1x1 box (x, y pairs, polyline); public-domain sky shapes
    private static final float[][] SIGNS = {
            {0.1f, 0.6f, 0.35f, 0.4f, 0.6f, 0.45f, 0.85f, 0.3f},                             // Aries
            {0.1f, 0.2f, 0.4f, 0.45f, 0.55f, 0.55f, 0.9f, 0.4f, 0.55f, 0.55f, 0.75f, 0.85f},   // Taurus
            {0.2f, 0.1f, 0.25f, 0.9f, 0.7f, 0.1f, 0.75f, 0.9f},                             // Gemini
            {0.5f, 0.1f, 0.5f, 0.5f, 0.2f, 0.85f, 0.5f, 0.5f, 0.8f, 0.85f},                   // Cancer
            {0.9f, 0.3f, 0.7f, 0.15f, 0.5f, 0.25f, 0.55f, 0.5f, 0.2f, 0.6f, 0.1f, 0.85f},     // Leo
            {0.1f, 0.2f, 0.35f, 0.4f, 0.55f, 0.35f, 0.7f, 0.6f, 0.9f, 0.8f},                 // Virgo
            {0.5f, 0.1f, 0.2f, 0.5f, 0.5f, 0.9f, 0.8f, 0.5f, 0.5f, 0.1f},                     // Libra
            {0.1f, 0.2f, 0.3f, 0.35f, 0.45f, 0.55f, 0.55f, 0.8f, 0.8f, 0.9f, 0.9f, 0.7f},     // Scorpio
            {0.2f, 0.8f, 0.4f, 0.55f, 0.6f, 0.6f, 0.8f, 0.35f, 0.6f, 0.2f},                   // Sagittarius
            {0.1f, 0.3f, 0.45f, 0.75f, 0.9f, 0.4f, 0.1f, 0.3f},                              // Capricorn
            {0.1f, 0.5f, 0.3f, 0.3f, 0.5f, 0.55f, 0.7f, 0.3f, 0.9f, 0.55f},                   // Aquarius
            {0.1f, 0.2f, 0.3f, 0.5f, 0.6f, 0.7f, 0.9f, 0.6f, 0.7f, 0.4f},                     // Pisces
    };

    private final float[] sx = new float[STARS], sy = new float[STARS], ss = new float[STARS], sp = new float[STARS];
    private final Paint bg = new Paint(), star = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            mist = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private long lastFrame;
    private int w, h;
    private boolean calm;          // 0.16.0: still while the game runs (the animation cost ~20% CPU on the shared chip)

    /** 0.16.0 (performance): hold the sky still (true) while FFXII is running; animate again when it's not. */
    void setCalm(boolean c) {
        if (c == calm) return;
        calm = c;
        invalidate();
    }

    ZodiacBackground(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        Random r = new Random(12);                    // fixed seed: the same sky every launch
        for (int i = 0; i < STARS; i++) { sx[i] = r.nextFloat(); sy[i] = r.nextFloat(); ss[i] = 0.6f + r.nextFloat() * 1.4f; sp[i] = r.nextFloat(); }
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(density * 0.8f);
        line.setColor(0x2A9CC8FF);
    }

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw; h = nh;
        smoke = null;
        bg.setShader(new LinearGradient(0, 0, 0, h, Theme.BG, Theme.BG2, Shader.TileMode.CLAMP));
    }

    // 0.17.0: the game's menus sit on a blurred, darkened scene. We can't show the scene, so: a dark slate band with
    // slow-drifting soft smoke (three cached radial glows moved by a matrix) and a faint vignette. It keeps animating
    // lightly on purpose (a still bottom screen hurt FFXII's frame pacing, Perf_Results_2026-10-25.md).
    private RadialGradient[] smoke;
    private RadialGradient vignette;
    private final android.graphics.Matrix m = new android.graphics.Matrix();
    private final Paint smokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawSmoke(Canvas c) {
        if (smoke == null) {
            bg.setShader(new LinearGradient(0, 0, w, 0, new int[]{0xFF0A1824, 0xFF29333C, 0xFF31322B}, null, Shader.TileMode.CLAMP));
            float r = Math.max(w, h) * 0.55f;
            smoke = new RadialGradient[]{
                    new RadialGradient(0, 0, r, 0x2A8FA3B0, 0x00000000, Shader.TileMode.CLAMP),
                    new RadialGradient(0, 0, r * 0.8f, 0x1EB0A890, 0x00000000, Shader.TileMode.CLAMP),
                    new RadialGradient(0, 0, r * 0.7f, 0x22000000, 0x00000000, Shader.TileMode.CLAMP)};
            vignette = new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.75f,
                    new int[]{0x00000000, 0x00000000, 0x99000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        }
        c.drawRect(0, 0, w, h, bg);
        // One 72 s loop; every glow moves at a whole multiple of it, so nothing jumps when the loop restarts.
        double t = (android.os.SystemClock.uptimeMillis() % 72000L) / 72000.0 * Math.PI * 2;
        for (int i = 0; i < smoke.length; i++) {
            float x = i == 0 ? 0.30f + 0.12f * (float) Math.sin(3 * t) : i == 1 ? 0.72f + 0.10f * (float) Math.cos(2 * t)
                    : 0.55f + 0.15f * (float) Math.sin(t + 1);
            float y = i == 0 ? 0.45f + 0.10f * (float) Math.cos(2 * t) : i == 1 ? 0.60f + 0.12f * (float) Math.sin(3 * t)
                    : 0.30f + 0.08f * (float) Math.cos(3 * t);
            m.setTranslate(x * w, y * h);
            smoke[i].setLocalMatrix(m);
            smokePaint.setShader(smoke[i]);
            c.drawRect(0, 0, w, h, smokePaint);
        }
        smokePaint.setShader(vignette);
        c.drawRect(0, 0, w, h, smokePaint);
    }

    @Override protected void onDraw(Canvas c) {
        if (Theme.GAME_LOOK) {
            drawSmoke(c);
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastFrame >= 50) { lastFrame = nowMs; postInvalidateDelayed(50); } else postInvalidateDelayed(50 - (nowMs - lastFrame));
            return;
        }
        float t = Theme.phase;
        long now = System.currentTimeMillis();
        c.drawRect(0, 0, w, h, bg);
        // mist: two soft glows drifting slowly (full cycle ~ 60 s)
        double m = (now % 60000L) / 60000.0 * Math.PI * 2;
        drawMist(c, (float) (w * (0.25 + 0.1 * Math.sin(m))), h * 0.3f, w * 0.55f, 0x22406AC8);
        drawMist(c, (float) (w * (0.75 + 0.1 * Math.cos(m))), h * 0.75f, w * 0.6f, 0x1C7A5AC8);
        // constellations: three at a time in a slow rotation of positions, faint
        int base = (int) ((now / 20000L) % 12);
        for (int k = 0; k < 3; k++) drawSign(c, SIGNS[(base + k * 4) % 12], w * (0.08f + 0.33f * k), h * (k == 1 ? 0.55f : 0.12f), Math.min(w, h) * 0.22f);
        // stars: twinkle by phase offset
        for (int i = 0; i < STARS; i++) {
            float a = 0.35f + 0.65f * (float) Math.abs(Math.sin((t + sp[i]) * Math.PI * 2));
            star.setColor(((int) (a * 200) << 24) | 0xE8F0FF);
            c.drawCircle(sx[i] * w, sy[i] * h, ss[i] * density, star);
        }
        if (calm) return;                                   // drawn once; redrawn only when the view changes
        if (now - lastFrame >= 50) { lastFrame = now; postInvalidateDelayed(50); } else postInvalidateDelayed(50 - (now - lastFrame));
    }

    private void drawMist(Canvas c, float x, float y, float rad, int color) {
        mist.setShader(new RadialGradient(x, y, rad, color, 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, rad, mist);
    }

    private void drawSign(Canvas c, float[] p, float ox, float oy, float size) {
        for (int i = 0; i + 3 < p.length; i += 2) c.drawLine(ox + p[i] * size, oy + p[i + 1] * size, ox + p[i + 2] * size, oy + p[i + 3] * size, line);
        star.setColor(0x88E8F0FF);
        for (int i = 0; i + 1 < p.length; i += 2) c.drawCircle(ox + p[i] * size, oy + p[i + 1] * size, density * 1.6f, star);
    }
}
