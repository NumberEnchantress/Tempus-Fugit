package com.tempusfugit.solarclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * Draws the analog face (6.1) and the readouts with android.graphics.Canvas.
 * The one drawing function for the app and the widget; the caller only picks
 * the rectangle. Paints are allocated once.
 */
final class FaceRenderer {
    final int ink, dim, ghost, face, fast, slow, halo;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);

    FaceRenderer(Context context) {
        ink = context.getColor(R.color.tf_ink);
        dim = context.getColor(R.color.tf_dim);
        ghost = context.getColor(R.color.tf_ghost);
        face = context.getColor(R.color.tf_face);
        fast = context.getColor(R.color.tf_fast);
        slow = context.getColor(R.color.tf_slow);
        halo = context.getColor(R.color.tf_halo);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFontFeatureSettings("tnum");
    }

    /** The analog face in the circle (cx, cy, r). secondHand only at seconds precision. */
    void drawAnalog(Canvas c, float cx, float cy, float r, ClockFrame f, boolean secondHand) {
        fill.setColor(face);
        c.drawCircle(cx, cy, r, fill);
        line.setColor(dim);
        line.setStrokeWidth(r * 0.015f);
        c.drawCircle(cx, cy, r * 0.97f, line);

        for (int i = 0; i < 12; i++) {
            double a = i * 30;
            line.setColor(i == 0 ? ink : dim);
            line.setStrokeWidth(r * (i % 3 == 0 ? 0.035f : 0.022f));
            float inner = r * (i == 0 ? FaceGeometry.TICK_INNER - 0.04f : FaceGeometry.TICK_INNER);
            c.drawLine(FaceGeometry.x(cx, inner, a), FaceGeometry.y(cy, inner, a),
                    FaceGeometry.x(cx, r * FaceGeometry.TICK_OUTER, a), FaceGeometry.y(cy, r * FaceGeometry.TICK_OUTER, a), line);
        }

        if (!f.hasFix) {
            text.setColor(dim);
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(r * 0.09f);
            c.drawText("no fix yet", cx, cy + r * FaceGeometry.LINE_OFFSETS[0], text);
            hub(c, cx, cy, r);
            return;
        }

        // Speed arc on the rim, from 12 o'clock: clockwise when fast, anticlockwise when slow.
        float sweep = FaceGeometry.arcSweep(f.arc);
        if (Math.abs(sweep) >= 0.5f) {
            line.setColor(sweep > 0 ? fast : slow);
            line.setStrokeWidth(r * 0.03f);
            float ar = r * FaceGeometry.ARC_RADIUS - r * 0.015f;
            oval.set(cx - ar, cy - ar, cx + ar, cy + ar);
            c.drawArc(oval, -90, sweep, false, line);
        }

        hands(c, cx, cy, r, FaceGeometry.handAngles(f.targetMs, f.seconds), ghost, secondHand);
        hands(c, cx, cy, r, FaceGeometry.handAngles(f.shownMs, f.seconds), ink, secondHand);

        // Indicators under the hub, over the hands with a halo so they stay legible.
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(r * 0.075f);
        indicator(c, f.speedLine, cx, cy + r * FaceGeometry.LINE_OFFSETS[0], r);
        indicator(c, f.gapLine, cx, cy + r * FaceGeometry.LINE_OFFSETS[1], r);
        if (f.settleLine != null) indicator(c, f.settleLine, cx, cy + r * FaceGeometry.LINE_OFFSETS[2], r);
        hub(c, cx, cy, r);
    }

    private void indicator(Canvas c, String s, float x, float y, float r) {
        text.setStyle(Paint.Style.STROKE);
        text.setStrokeWidth(r * 0.03f);
        text.setColor(halo);
        c.drawText(s, x, y, text);
        text.setStyle(Paint.Style.FILL);
        text.setColor(ink);
        c.drawText(s, x, y, text);
    }

    private void hands(Canvas c, float cx, float cy, float r, double[] a, int color, boolean secondHand) {
        line.setColor(color);
        line.setStrokeWidth(r * 0.05f);
        hand(c, cx, cy, r * FaceGeometry.HOUR_LENGTH, a[0]);
        line.setStrokeWidth(r * 0.032f);
        hand(c, cx, cy, r * FaceGeometry.MINUTE_LENGTH, a[1]);
        if (secondHand) {
            line.setStrokeWidth(r * 0.012f);
            hand(c, cx, cy, r * FaceGeometry.SECOND_LENGTH, a[2]);
        }
    }

    private void hand(Canvas c, float cx, float cy, float length, double angle) {
        c.drawLine(cx, cy, FaceGeometry.x(cx, length, angle), FaceGeometry.y(cy, length, angle), line);
    }

    private void hub(Canvas c, float cx, float cy, float r) {
        fill.setColor(ink);
        c.drawCircle(cx, cy, r * 0.035f, fill);
    }

    /**
     * The three readouts stacked in a box, tempus fugit largest. Text shrinks to
     * fit the width.
     */
    void drawReadouts(Canvas c, float left, float top, float width, float height, ClockFrame f) {
        float unit = height / 2.6f;
        float big = fit(f.tempus, unit * 1.05f, width, light);
        float small = Math.min(big * 0.62f, fit(f.local, unit * 0.62f, width, Typeface.DEFAULT));
        float total = big + small * 2 + small * 0.6f;
        float y = top + (height - total) / 2;
        float cx = left + width / 2;

        text.setTypeface(light);
        text.setTextSize(big);
        text.setColor(ink);
        y += big * 0.95f;
        c.drawText(f.tempus, cx, y, text);

        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(small);
        text.setColor(ink);
        text.setAlpha(200);
        y += small * 1.3f;
        c.drawText(f.local, cx, y, text);
        text.setColor(dim);
        y += small * 1.25f;
        c.drawText(f.system, cx, y, text);
    }

    private float fit(String s, float size, float width, Typeface tf) {
        text.setTypeface(tf);
        text.setTextSize(size);
        float w = text.measureText(s);
        return w > width * 0.96f ? size * width * 0.96f / w : size;
    }
}
