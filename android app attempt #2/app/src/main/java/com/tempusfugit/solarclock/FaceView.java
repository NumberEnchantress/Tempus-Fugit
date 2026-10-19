package com.tempusfugit.solarclock;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

/** The analog face on the main screen: a square view drawn by FaceRenderer. */
public final class FaceView extends View {
    private final FaceRenderer renderer;
    private ClockFrame frame;
    private boolean secondHand;

    public FaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        renderer = new FaceRenderer(context);
    }

    void show(ClockFrame frame, boolean secondHand) {
        this.frame = frame;
        this.secondHand = secondHand;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(w, w);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (frame == null) return;
        float r = getWidth() / 2f;
        renderer.drawAnalog(canvas, r, r, r * 0.98f, frame, secondHand);
    }
}
