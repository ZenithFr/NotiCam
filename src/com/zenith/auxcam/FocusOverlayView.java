package com.zenith.auxcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class FocusOverlayView extends View {
    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRingRect = new RectF();

    private float mTargetX = -1;
    private float mTargetY = -1;
    private float mScale = 1.0f;
    private boolean mVisible = false;

    private static class HideRunnable implements Runnable {
        private final FocusOverlayView view;
        public HideRunnable(FocusOverlayView view) { this.view = view; }
        @Override
        public void run() {
            view.mVisible = false;
            view.invalidate();
        }
    }

    private final HideRunnable mHideRunnable = new HideRunnable(this);

    public FocusOverlayView(Context context) {
        super(context);
        init();
    }

    public FocusOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FocusOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(2.5f);
        mRingPaint.setColor(0xFFFFE043); // Apple Camera signature yellow

        mCenterPaint.setStyle(Paint.Style.FILL);
        mCenterPaint.setColor(0xFFFFE043);
    }

    private static class ScaleEndRunnable implements Runnable {
        private final FocusOverlayView view;
        public ScaleEndRunnable(FocusOverlayView view) { this.view = view; }
        @Override
        public void run() {
            view.mScale = 1.0f;
            view.invalidate();
            view.postDelayed(view.mHideRunnable, 2000);
        }
    }

    private final ScaleEndRunnable mScaleEndRunnable = new ScaleEndRunnable(this);

    public void showFocusAt(float x, float y) {
        mTargetX = x;
        mTargetY = y;
        mVisible = true;
        mScale = 1.35f;

        removeCallbacks(mHideRunnable);
        invalidate();

        animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(220)
            .withEndAction(mScaleEndRunnable)
            .start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mVisible || mTargetX < 0 || mTargetY < 0) return;

        float radius = 55.0f * mScale;
        mRingRect.set(mTargetX - radius, mTargetY - radius, mTargetX + radius, mTargetY + radius);

        // Draw outer rounded focus box
        canvas.drawRoundRect(mRingRect, 12.0f, 12.0f, mRingPaint);

        // Draw center dot
        canvas.drawCircle(mTargetX, mTargetY, 4.0f, mCenterPaint);
    }
}
