package com.zenith.auxcam;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Apple Camera shutter.
 *
 * Anatomy at 72dp:
 *   • 4dp white outer ring
 *   • 3.5dp transparent gap
 *   • a solid disc (≈57dp) — white for Photos/Portrait, red for Video, and a
 *     red rounded square once video is actually recording.
 *
 * Pressing compresses the disc and fires a haptic, exactly like the iPhone
 * shutter; every capture also emits a thin expanding ring so the button reads
 * as "fired" even when the screen flash is disabled.
 */
public class ShutterButton extends View {

    public static final int MODE_PHOTO = 0;
    public static final int MODE_PORTRAIT = 1;
    public static final int MODE_VIDEO = 2;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int RED = 0xFFFF453A;

    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDiscPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mVeilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRecordingRect = new RectF();

    private int mMode = MODE_PHOTO;
    private boolean mRecording = false;

    /** 1.0 = idle, < 1.0 = compressed disc. */
    private float mPressScale = 1.0f;
    /** 0..1 expanding capture ring. */
    private float mPulseProgress = 1.0f;

    private ValueAnimator mPressAnimator;
    private ValueAnimator mPulseAnimator;

    public ShutterButton(Context context) {
        super(context);
        init();
    }

    public ShutterButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ShutterButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);

        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(dp(4f));
        mRingPaint.setColor(WHITE);
        mRingPaint.setStrokeCap(Paint.Cap.ROUND);

        mDiscPaint.setStyle(Paint.Style.FILL);
        mDiscPaint.setColor(WHITE);

        mPulsePaint.setStyle(Paint.Style.STROKE);
        mPulsePaint.setStrokeWidth(dp(2f));
        mPulsePaint.setColor(WHITE);

        mVeilPaint.setStyle(Paint.Style.FILL);
        mVeilPaint.setColor(0x66000000);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    public void setMode(int mode) {
        if (mMode == mode) return;
        mMode = mode;
        invalidate();
    }

    public int getMode() {
        return mMode;
    }

    public void setRecording(boolean recording) {
        if (mRecording == recording) return;
        mRecording = recording;
        invalidate();
    }

    public boolean isRecording() {
        return mRecording;
    }

    /** Ring pulse + haptic, invoked for every capture. */
    public void playCaptureFeedback() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);

        if (mPulseAnimator != null) {
            mPulseAnimator.cancel();
        }
        mPulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        mPulseAnimator.setDuration(420);
        mPulseAnimator.setInterpolator(new DecelerateInterpolator());
        mPulseAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mPulseProgress = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        mPulseAnimator.start();
    }

    private void animatePress(final float target) {
        if (mPressAnimator != null) {
            mPressAnimator.cancel();
        }
        mPressAnimator = ValueAnimator.ofFloat(mPressScale, target);
        mPressAnimator.setDuration(target < mPressScale ? 110 : 260);
        mPressAnimator.setInterpolator(target < mPressScale
                ? new DecelerateInterpolator()
                : new OvershootInterpolator(1.6f));
        mPressAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mPressScale = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        mPressAnimator.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animatePress(0.86f);
                return true;
            case MotionEvent.ACTION_UP:
                animatePress(1.0f);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                animatePress(1.0f);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        final float cx = w / 2f;
        final float cy = h / 2f;
        final float ringWidth = dp(4f);
        final float gap = dp(3.5f);
        final float outerRadius = Math.min(cx, cy) - ringWidth / 2f;
        final float discRadius = outerRadius - ringWidth / 2f - gap;

        // 1. Capture pulse ring
        if (mPulseProgress < 1.0f) {
            float pulseRadius = discRadius + dp(14f) * mPulseProgress;
            mPulsePaint.setAlpha((int) (150 * (1f - mPulseProgress)));
            canvas.drawCircle(cx, cy, pulseRadius, mPulsePaint);
        }

        // 2. Outer ring (dims while pressed)
        mRingPaint.setAlpha(mPressScale < 1f ? 190 : 255);
        canvas.drawCircle(cx, cy, outerRadius, mRingPaint);

        // 3. Inner element
        float r = discRadius * mPressScale;
        if (mMode == MODE_VIDEO) {
            mDiscPaint.setColor(RED);
            if (mRecording) {
                float half = r * 0.62f;
                mRecordingRect.set(cx - half, cy - half, cx + half, cy + half);
                canvas.drawRoundRect(mRecordingRect, r * 0.22f, r * 0.22f, mDiscPaint);
            } else {
                canvas.drawCircle(cx, cy, r, mDiscPaint);
            }
        } else {
            mDiscPaint.setColor(WHITE);
            canvas.drawCircle(cx, cy, r, mDiscPaint);
        }

        // 4. Disabled veil
        if (!isEnabled()) {
            canvas.drawCircle(cx, cy, discRadius, mVeilPaint);
        }
    }
}
