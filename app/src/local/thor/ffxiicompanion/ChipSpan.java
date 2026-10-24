package local.thor.ffxiicompanion;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;

/**
 * 0.16.0: a small rounded chip inside running text (element names on the Targeting card), so a line of chips wraps
 * like words. Filled (weak/half), outlined (absorbs) or struck through (immune): the meaning never rests on colour
 * alone, and the card keeps the words "Weak to" / "Absorbs" / "Immune to" next to the chips.
 */
final class ChipSpan extends ReplacementSpan {
    enum Style { FILL, OUTLINE, STRUCK }

    private final int color, onColor;
    private final Style style;
    private final float padX, radius, stroke;

    ChipSpan(int color, int onColor, Style style, float density) {
        this.color = color;
        this.onColor = onColor;
        this.style = style;
        padX = 7 * density;
        radius = 9 * density;
        stroke = 1.5f * density;
    }

    @Override public int getSize(Paint p, CharSequence t, int start, int end, Paint.FontMetricsInt fm) {
        if (fm != null) {
            Paint.FontMetricsInt m = p.getFontMetricsInt();
            fm.ascent = m.ascent - 2; fm.top = m.top - 2; fm.descent = m.descent + 2; fm.bottom = m.bottom + 2;
        }
        return Math.round(p.measureText(t, start, end) + 2 * padX + padX / 2);   // trailing gap before the next chip
    }

    @Override public void draw(Canvas c, CharSequence t, int start, int end, float x, int top, int y, int bottom, Paint p) {
        float w = p.measureText(t, start, end) + 2 * padX;
        Paint.FontMetrics m = p.getFontMetrics();
        RectF r = new RectF(x, y + m.ascent - 3, x + w, y + m.descent + 3);
        int keepColor = p.getColor();
        Paint.Style keepStyle = p.getStyle();
        float keepStroke = p.getStrokeWidth();
        p.setColor(color);
        if (style == Style.OUTLINE) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            r.inset(stroke / 2, stroke / 2);
        } else {
            p.setStyle(Paint.Style.FILL);
        }
        c.drawRoundRect(r, radius, radius, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(style == Style.OUTLINE ? color : onColor);
        c.drawText(t, start, end, x + padX, y, p);
        if (style == Style.STRUCK) {
            p.setStrokeWidth(stroke);
            float mid = (r.top + r.bottom) / 2;
            c.drawLine(x + padX / 2, mid, x + w - padX / 2, mid, p);
        }
        p.setColor(keepColor);
        p.setStyle(keepStyle);
        p.setStrokeWidth(keepStroke);
    }
}
