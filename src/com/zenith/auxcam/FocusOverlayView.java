package com.zenith.auxcam;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Viewfinder overlay that draws the two pieces of iOS camera chrome that live
 * on top of the preview itself:
 *
 *   1. The rule-of-thirds grid (very low contrast, 0.5dp hairlines).
 *   2. The yellow focus reticle: a rounded square that springs in at the tap
 *      point, settles, then fades away — exactly like iPhone focus/exposure.
 */
public class FocusOverlayView extends View {

    private final Paint mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mReticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSunPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    private float mTargetX = -1f;
    private float mTargetY = -1f;

    private boolean mGridEnabled = false;

    /** 0..1 reticle appear progress (drives spring-in scale + fade-in). */
    private float mAppear = 0f;
    /** 0..1 reticle fade-out progress. */
    private float mFade = 1f;

    private ValueAnimator mAppearAnimator;
    private ValueAnimator mFadeAnimator;

    private final Runnable mFadeOutRunnable = new Runnable() {
        @Override
        public void run() {
            fadeOut();
        }
    };

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
        setWillNotDraw(false);

        mGridPaint.setStyle(Paint.Style.STROKE);
        mGridPaint.setStrokeWidth(dp(0.5f));
        mGridPaint.setColor(0x40FFFFFF);

        mReticlePaint.setStyle(Paint.Style.STROKE);
        mReticlePaint.setStrokeWidth(dp(1.6f));
        mReticlePaint.setColor(0xFFFFD60A); // iOS systemYellow

        mSunPaint.setStyle(Paint.Style.FILL);
        mSunPaint.setColor(0xFFFFD60A);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    public void setGridEnabled(boolean enabled) {
        if (mGridEnabled == enabled) return;
        mGridEnabled = enabled;
        invalidate();
    }

    public boolean isGridEnabled() {
        return mGridEnabled;
    }

    /**
     * Show the yellow reticle at (x, y) in view coordinates and schedule the
     * automatic fade-out.
     */
    public void showFocusAt(float x, float y) {
        mTargetX = x;
        mTargetY = y;

        if (mAppearAnimator != null) mAppearAnimator.cancel();
        if (mFadeAnimator != null) mFadeAnimator.cancel();

        mAppear = 0f;
        mFade = 1f;

        mAppearAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAppearAnimator.setDuration(260);
        mAppearAnimator.setInterpolator(new OvershootInterpolator(1.4f));
        mAppearAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mAppear = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        mAppearAnimator.start();

        removeCallbacks(mFadeOutRunnable);
        postDelayed(mFadeOutRunnable, 1400);
    }

    private void fadeOut() {
        if (mFadeAnimator != null) mFadeAnimator.cancel();
        mFadeAnimator = ValueAnimator.ofFloat(1f, 0f);
        mFadeAnimator.setDuration(220);
        mFadeAnimator.setInterpolator(new DecelerateInterpolator());
        mFadeAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mFade = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        mFadeAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // ── Rule-of-thirds grid ─────────────────────────────────────────────
        if (mGridEnabled) {
            float w = getWidth();
            float h = getHeight();
            canvas.drawLine(w / 3f, 0, w / 3f, h, mGridPaint);
            canvas.drawLine(2f * w / 3f, 0, 2f * w / 3f, h, mGridPaint);
            canvas.drawLine(0, h / 3f, w, h / 3f, mGridPaint);
            canvas.drawLine(0, 2f * h / 3f, w, 2f * h / 3f, mGridPaint);
        }

        // ── Focus reticle ──────────────────────────────────────────────────
        if (mTargetX < 0 || mTargetY < 0 || mFade <= 0f) return;

        float alpha = mFade * Math.min(1f, mAppear * 2f);
        float scale = 0.7f + 0.3f * mAppear;
        float half = dp(36f) * scale;

        mReticlePaint.setAlpha((int) (255 * alpha));
        mSunPaint.setAlpha((int) (255 * alpha));

        mRect.set(mTargetX - half, mTargetY - half, mTargetX + half, mTargetY + half);
        canvas.drawRoundRect(mRect, dp(4f), dp(4f), mReticlePaint);

        // Small centered cross-hair dot, like the iPhone AE/AF reticle.
        canvas.drawCircle(mTargetX, mTargetY, dp(2.2f) * scale, mSunPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(mFadeOutRunnable);
        if (mAppearAnimator != null) mAppearAnimator.cancel();
        if (mFadeAnimator != null) mFadeAnimator.cancel();
    }
}
