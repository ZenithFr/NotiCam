package com.zenith.auxcam;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

public class ZoomDialView extends View {

    public interface ZoomCallback {
        void onZoomChanged(float zoomRatio);
    }

    static class FadeRunnable implements Runnable {
        private final ZoomDialView view;
        public FadeRunnable(ZoomDialView view) {
            this.view = view;
        }
        @Override
        public void run() {
            if (view != null && view.getVisibility() == View.VISIBLE) {
                view.animate()
                    .alpha(0.0f)
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(240)
                    .setListener(new HideEndListener(view))
                    .start();
            }
        }
    }

    static class HideEndListener extends AnimatorListenerAdapter {
        private final ZoomDialView view;
        public HideEndListener(ZoomDialView view) {
            this.view = view;
        }
        @Override
        public void onAnimationEnd(Animator animation) {
            if (view != null) {
                view.animate().setListener(null);
                view.setVisibility(View.INVISIBLE);
                view.setAlpha(1.0f);
                view.setScaleX(1.0f);
                view.setScaleY(1.0f);
            }
        }
    }

    private Paint mBgPaint;
    private Paint mBorderPaint;
    private Paint mTickNormalPaint;
    private Paint mTickBoldPaint;
    private Paint mNeedlePaint;
    private Paint mTextLargePaint;
    private Paint mTextSubPaint;

    private float mZoomRatio = 1.0f;
    private float mMinZoom = 1.0f;
    private float mMaxZoom = 10.0f;
    private String mCameraId = "0";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final FadeRunnable mFadeRunnable = new FadeRunnable(this);
    private ZoomCallback mZoomCallback;

    private float mLastTouchAngle = 0.0f;
    private boolean mIsDragging = false;
    private float mLastHapticZoom = 1.0f;
    private Vibrator mVibrator;

    public ZoomDialView(Context context) {
        super(context);
        init();
    }

    public ZoomDialView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ZoomDialView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;

        mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBgPaint.setColor(0xE6141416); // iOS dark glass (90% #1C1C1E)
        mBgPaint.setStyle(Paint.Style.FILL);

        mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBorderPaint.setColor(0x1FFFFFFF);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(1.2f * density);

        mTickNormalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTickNormalPaint.setColor(0x3DFFFFFF);
        mTickNormalPaint.setStyle(Paint.Style.STROKE);
        mTickNormalPaint.setStrokeWidth(1.5f * density);
        mTickNormalPaint.setStrokeCap(Paint.Cap.ROUND);

        mTickBoldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTickBoldPaint.setColor(0xE6FFFFFF);
        mTickBoldPaint.setStyle(Paint.Style.STROKE);
        mTickBoldPaint.setStrokeWidth(2.5f * density);
        mTickBoldPaint.setStrokeCap(Paint.Cap.ROUND);

        mNeedlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mNeedlePaint.setColor(0xFFFFD60A); // iOS systemYellow selection tick
        mNeedlePaint.setStyle(Paint.Style.STROKE);
        mNeedlePaint.setStrokeWidth(3.0f * density);
        mNeedlePaint.setStrokeCap(Paint.Cap.ROUND);

        mTextLargePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextLargePaint.setColor(0xFFFFFFFF);
        mTextLargePaint.setTextAlign(Paint.Align.CENTER);
        mTextLargePaint.setTextSize(34.0f * density);
        mTextLargePaint.setFakeBoldText(false);
        mTextLargePaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        mTextLargePaint.setLetterSpacing(0.02f);

        mTextSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextSubPaint.setColor(0x99FFFFFF);
        mTextSubPaint.setTextAlign(Paint.Align.CENTER);
        mTextSubPaint.setTextSize(11.0f * density);
        mTextSubPaint.setLetterSpacing(0.06f);

        try {
            mVibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Throwable t) {}
    }

    public void setZoomCallback(ZoomCallback callback) {
        this.mZoomCallback = callback;
    }

    public void show(float zoom, float minZoom, float maxZoom, String cameraId) {
        this.mZoomRatio = zoom;
        this.mMinZoom = minZoom;
        this.mMaxZoom = maxZoom;
        this.mCameraId = cameraId;
        this.mLastHapticZoom = zoom;

        mHandler.removeCallbacks(mFadeRunnable);
        animate().setListener(null);
        animate().cancel();
        setAlpha(1.0f);
        setScaleX(1.0f);
        setScaleY(1.0f);
        setVisibility(View.VISIBLE);
        bringToFront();
        invalidate();

        scheduleFade();
    }

    public void updateZoom(float zoom) {
        this.mZoomRatio = Math.max(mMinZoom, Math.min(mMaxZoom, zoom));
        invalidate();
        scheduleFade();
    }

    private void scheduleFade() {
        mHandler.removeCallbacks(mFadeRunnable);
        mHandler.postDelayed(mFadeRunnable, 2500);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2.0f;
        float cy = h / 2.0f;
        float radius = Math.min(cx, cy) - (4.0f * getResources().getDisplayMetrics().density);
        float density = getResources().getDisplayMetrics().density;

        // 1. Draw Frosted Acrylic Disk
        canvas.drawCircle(cx, cy, radius, mBgPaint);
        canvas.drawCircle(cx, cy, radius, mBorderPaint);

        // 2. Draw 48 Notched Ticks around the perimeter
        int totalTicks = 48;
        float angleStep = 360.0f / totalTicks;
        float outerR = radius - (4.0f * density);

        for (int i = 0; i < totalTicks; i++) {
            float angleDeg = i * angleStep;
            float angleRad = (float) Math.toRadians(angleDeg);

            boolean isCardinal = (i % 12 == 0);
            boolean isMajor = (i % 6 == 0);
            float tickLen = isCardinal ? (10.0f * density) : (isMajor ? (7.0f * density) : (4.5f * density));
            float innerR = outerR - tickLen;

            float x1 = cx + (float) (Math.cos(angleRad) * innerR);
            float y1 = cy + (float) (Math.sin(angleRad) * innerR);
            float x2 = cx + (float) (Math.cos(angleRad) * outerR);
            float y2 = cy + (float) (Math.sin(angleRad) * outerR);

            canvas.drawLine(x1, y1, x2, y2, isMajor ? mTickBoldPaint : mTickNormalPaint);
        }

        // 3. Draw the yellow zoom pointer over the tick ring
        // Map zoom ratio across top arc (-90 deg center, sweeping -150 to -30)
        float zoomNorm = (mMaxZoom > mMinZoom) ? ((mZoomRatio - mMinZoom) / (mMaxZoom - mMinZoom)) : 0.0f;
        float needleAngleDeg = 270.0f + ((zoomNorm - 0.5f) * 160.0f);
        float needleRad = (float) Math.toRadians(needleAngleDeg);

        float needleInnerR = radius - (16.0f * density);
        float needleOuterR = radius - (2.0f * density);
        float nx1 = cx + (float) (Math.cos(needleRad) * needleInnerR);
        float ny1 = cy + (float) (Math.sin(needleRad) * needleInnerR);
        float nx2 = cx + (float) (Math.cos(needleRad) * needleOuterR);
        float ny2 = cy + (float) (Math.sin(needleRad) * needleOuterR);

        canvas.drawLine(nx1, ny1, nx2, ny2, mNeedlePaint);

        // 4. Central Large Zoom Text (e.g. "2.0x")
        float displayMultiplier = 1.0f;
        if ("2".equals(mCameraId)) {
            displayMultiplier = 0.6f;
        } else if ("3".equals(mCameraId)) {
            displayMultiplier = 2.0f;
        }
        float effectiveZoom = mZoomRatio * displayMultiplier;
        String zoomStr = String.format(Locale.US, "%.1f×", effectiveZoom);

        float textY = cy + (6.0f * density);
        canvas.drawText(zoomStr, cx, textY, mTextLargePaint);

        // 5. Central Subtitle Text (Equivalent 35mm Focal Length)
        String focalStr = getFocalEquivalentString(effectiveZoom);
        canvas.drawText(focalStr, cx, textY + (20.0f * density), mTextSubPaint);
    }

    private String getFocalEquivalentString(float effectiveZoom) {
        if ("2".equals(mCameraId)) {
            int mm = Math.round(15.0f * (effectiveZoom / 0.6f));
            return mm + " MM";
        } else if ("3".equals(mCameraId)) {
            int mm = Math.round(50.0f * (effectiveZoom / 2.0f));
            return mm + " MM · OPTICAL";
        } else if ("1".equals(mCameraId)) {
            return "24 MM · SELFIE";
        } else {
            int mm = Math.round(24.0f * effectiveZoom);
            return mm + " MM";
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float cx = getWidth() / 2.0f;
        float cy = getHeight() / 2.0f;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mIsDragging = true;
                mLastTouchAngle = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
                scheduleFade();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (mIsDragging) {
                    float currentAngle = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
                    float deltaAngle = currentAngle - mLastTouchAngle;

                    // Handle 180 -> -180 wrap
                    if (deltaAngle > 180.0f) deltaAngle -= 360.0f;
                    if (deltaAngle < -180.0f) deltaAngle += 360.0f;

                    // 1 full turn (360 deg) translates to zoom range
                    float zoomDelta = (deltaAngle / 180.0f) * (mMaxZoom - mMinZoom) * 0.4f;
                    float newZoom = Math.max(mMinZoom, Math.min(mMaxZoom, mZoomRatio + zoomDelta));

                    if (Math.abs(newZoom - mZoomRatio) > 0.005f) {
                        mZoomRatio = newZoom;
                        invalidate();

                        if (Math.abs(mZoomRatio - mLastHapticZoom) >= 0.15f) {
                            triggerTickHaptic();
                            mLastHapticZoom = mZoomRatio;
                        }

                        if (mZoomCallback != null) {
                            mZoomCallback.onZoomChanged(mZoomRatio);
                        }
                    }

                    mLastTouchAngle = currentAngle;
                    scheduleFade();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsDragging = false;
                scheduleFade();
                return true;
        }

        return super.onTouchEvent(event);
    }

    private void triggerTickHaptic() {
        if (mVibrator != null && Build.VERSION.SDK_INT >= 29) {
            try {
                mVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
            } catch (Throwable t) {}
        }
    }
}
