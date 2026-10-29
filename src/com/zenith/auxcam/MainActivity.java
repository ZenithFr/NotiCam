package com.zenith.auxcam;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import rikka.shizuku.Shizuku;

/**
 * NotiCam — Apple Camera-style capture surface for the AuxCam Vulkan bokeh engine.
 *
 * Interaction model (matches iOS Camera):
 *   • tap the viewfinder → yellow AE/AF reticle + hardware focus scan
 *   • pinch (or the lens chips, or the radial dial) → digital zoom
 *   • flash button cycles off → auto → on, applied to the still request
 *   • mode carousel drives the pipeline: PORTRAIT exposes the ƒ dial, PRO
 *     captures with high-quality noise reduction
 *   • shutter compresses, fires a haptic and a preview bloom, then flies the
 *     real captured frame into the gallery thumbnail
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "AuxCamActivity";
    private static final int SHIZUKU_REQUEST_CODE = 100;

    /** Logical camera owned by the privileged user service. */
    private static final String CAMERA_ID = "5";

    private static final int MODE_PHOTO = 0;
    private static final int MODE_PORTRAIT = 1;
    private static final int MODE_HQ = 2;

    private static final int FLASH_OFF = 0;
    private static final int FLASH_AUTO = 1;
    private static final int FLASH_ON = 2;

    /** Apple's Portrait aperture ladder: f/1.4 → f/16 in whole stops. */
    private static final float[] FSTOPS = {
            1.4f, 1.6f, 1.8f, 2.0f, 2.2f, 2.5f, 2.8f, 3.2f, 3.5f, 4.0f, 4.5f,
            5.0f, 5.6f, 6.3f, 7.1f, 8.0f, 9.0f, 10f, 11f, 13f, 14f, 16f
    };
    private static final float ZOOM_MIN = 1.0f;
    private static final float ZOOM_MAX = 5.0f;

    // ── Apple Camera chrome ─────────────────────────────────────────────────
    private View mRoot;
    private View mViewfinder;
    private AutoFitSurfaceView mPreviewView;
    private FocusOverlayView mFocusOverlay;
    private View mCaptureFlash;
    private View mTopBar;
    private LinearLayout mBottomControls;
    private ImageButton mFlashButton;
    private ImageButton mProButton;
    private ImageButton mSwitchCameraButton;
    private TextView mStatusText;
    private TextView mTelemetryText;
    private LinearLayout mProSheet;
    private View mRowAperture;
    private View mRowApertureQuick;
    private SeekBar mApertureBar;
    private TextView mApertureValue;
    private SeekBar mApertureBarQuick;
    private TextView mApertureValueQuick;
    private SeekBar mEvBar;
    private TextView mEvValue;
    private Button mGridButton;
    private Button mRawButton;
    private Button mTimerButton;
    private TextView mZoom1;
    private TextView mZoom2;
    private TextView mZoom5;
    private TextView mModePhoto;
    private TextView mModePortrait;
    private TextView mModeHq;
    private ShutterButton mShutterButton;
    private View mGalleryButton;
    private ImageView mGalleryThumb;
    private ImageView mGalleryPlaceholder;
    private ImageView mCaptureFlyer;
    private ZoomDialView mZoomDial;

    // ── UI state ────────────────────────────────────────────────────────────
    private int mMode = MODE_PHOTO;
    private int mFlashMode = FLASH_OFF;
    private float mZoomRatio = ZOOM_MIN;
    /** Last ratio pushed to the engine — keeps pinch spam off the camera thread. */
    private float mLastSentZoom = ZOOM_MIN;
    private int mApertureIndex = 2;
    private float mFStop = 1.8f;
    private int mEvSteps = 0;
    private int mTimerSeconds = 0;
    private boolean mGridEnabled = false;
    private boolean mRawEnabled = false;
    private boolean mProSheetOpen = false;
    private boolean mSessionReady = false;
    private int mTopBarSidePadding = 0;
    private int mBottomSidePadding = 0;
    private boolean mLensSwitchPending = false;

    private final List<String> mPhysicalLenses = new ArrayList<>();
    private int mLensIndex = 0;
    /** Physical sensor pinned on the colour stream; "0" is the historical default. */
    private String mCurrentLens = "0";
    private Bitmap mLastThumbnail;

    // ── Camera plumbing ─────────────────────────────────────────────────────
    private SurfaceHolder mSurfaceHolder;
    private IAuxCamService mService;

    private final ServiceConnection mServiceConnection = new AuxServiceConnection(this);
    private final Shizuku.OnRequestPermissionResultListener mPermissionListener = new PermResultListener(this);
    private final Shizuku.OnBinderReceivedListener mBinderReceivedListener = new BinderRecvListener(this);
    private final Shizuku.OnBinderDeadListener mBinderDeadListener = new BinderDeadListener(this);

    private ImageReader mColorReader;
    private ImageReader mDepthReader;
    private ImageReader mStillReader;

    private HandlerThread mReaderThread;
    private Handler mReaderHandler;
    private final Handler mUiHandler = new Handler();

    private long mTotalColorFrames = 0;
    private long mTotalDepthFrames = 0;
    byte[] mLatestDepth = null;

    long mShutterClickNs = 0;
    private java.util.concurrent.ExecutorService mIoExecutor;

    private PowerManager mPowerManager;
    private AuxThermalListener mThermalListener;
    private CaptureBroadcastReceiver mCaptureReceiver;

    static {
        try {
            System.loadLibrary("auxcam_engine");
        } catch (Throwable t) {
            Log.w(TAG, "Native auxcam_engine library note: " + t.getMessage());
        }
    }

    public static native void nativeSetThermalThrottled(boolean throttled);

    public native boolean nativeInitEngine(Surface surface, int width, int height);

    public native boolean nativeRenderFrame(
            HardwareBuffer buffer, byte[] depth, int depthW, int depthH, float fStop);

    public native boolean nativeProcessStillBokeh(
            HardwareBuffer buffer, byte[] depth, int depthW, int depthH, float fStop,
            float targetFarFieldPx, String outPath, long[] timestampsOut);

    // ═══════════════════════════════ LIFE CYCLE ══════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Preview must stay awake: the APU/GPU clocks drop otherwise.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mIoExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

        setContentView(R.layout.activity_main);
        bindViews();
        applyImmersiveChrome();
        applyWindowInsets();
        wireTopBar();
        wireProSheet();
        wireLensChips();
        wireModeCarousel();
        wireShutter();
        wireViewfinderGestures();
        syncApertureFromSlider();
        applyModeVisuals(false);

        if (mPreviewView != null) {
            mPreviewView.setAspectRatio(1440, 1080);
            mPreviewView.getHolder().addCallback(this);
        }

        discoverLenses();
        startReaderThread();
        setupImageReaders();

        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (mPowerManager != null
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            mThermalListener = new AuxThermalListener(this);
            mPowerManager.addThermalStatusListener(mThermalListener);
        }

        mCaptureReceiver = new CaptureBroadcastReceiver(this);
        android.content.IntentFilter captureFilter =
                new android.content.IntentFilter("com.zenith.auxcam.CAPTURE_STILL");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mCaptureReceiver, captureFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mCaptureReceiver, captureFilter);
        }

        Shizuku.addRequestPermissionResultListener(mPermissionListener);
        Shizuku.addBinderReceivedListener(mBinderReceivedListener);
        Shizuku.addBinderDeadListener(mBinderDeadListener);

        if (Shizuku.pingBinder()) {
            checkPermissionAndBind();
        } else {
            updateStatus(getString(R.string.status_waiting_shizuku));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopReaderThread();
        mUiHandler.removeCallbacksAndMessages(null);

        if (mCaptureReceiver != null) {
            try {
                unregisterReceiver(mCaptureReceiver);
            } catch (Throwable t) {
                // already gone
            }
            mCaptureReceiver = null;
        }

        if (mPowerManager != null && mThermalListener != null
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                mPowerManager.removeThermalStatusListener(mThermalListener);
            } catch (Throwable t) {
                // ignore
            }
            mThermalListener = null;
        }

        if (mIoExecutor != null) {
            mIoExecutor.shutdown();
        }
        if (mColorReader != null) mColorReader.close();
        if (mDepthReader != null) mDepthReader.close();
        if (mStillReader != null) mStillReader.close();

        if (mService != null) {
            try {
                mService.closeSession();
            } catch (RemoteException e) {
                // service already gone
            }
        }

        Shizuku.removeRequestPermissionResultListener(mPermissionListener);
        Shizuku.removeBinderReceivedListener(mBinderReceivedListener);
        Shizuku.removeBinderDeadListener(mBinderDeadListener);
    }

    // ═══════════════════════════ VIEW WIRING ═════════════════════════════════

    private void bindViews() {
        mRoot = findViewById(R.id.root);
        mViewfinder = findViewById(R.id.viewfinder_container);
        mPreviewView = findViewById(R.id.surface_view);
        mFocusOverlay = findViewById(R.id.focus_overlay);
        mCaptureFlash = findViewById(R.id.capture_flash);
        mTopBar = findViewById(R.id.top_bar);
        mBottomControls = findViewById(R.id.bottom_controls);

        mFlashButton = findViewById(R.id.btn_flash);
        mProButton = findViewById(R.id.btn_pro);
        mSwitchCameraButton = findViewById(R.id.btn_switch_cam);
        mStatusText = findViewById(R.id.tv_status);
        mTelemetryText = findViewById(R.id.tv_telemetry);

        mProSheet = findViewById(R.id.drawer_pro_controls);
        mRowAperture = findViewById(R.id.row_aperture);
        mRowApertureQuick = findViewById(R.id.row_aperture_quick);
        mApertureBar = findViewById(R.id.sb_aperture);
        mApertureValue = findViewById(R.id.tv_aperture_value);
        mApertureBarQuick = findViewById(R.id.sb_aperture_quick);
        mApertureValueQuick = findViewById(R.id.tv_aperture_quick);
        mEvBar = findViewById(R.id.sb_ev);
        mEvValue = findViewById(R.id.tv_ev_label);
        mGridButton = findViewById(R.id.btn_grid);
        mRawButton = findViewById(R.id.btn_raw);
        mTimerButton = findViewById(R.id.btn_hq);

        mZoom1 = findViewById(R.id.btn_zoom_1);
        mZoom2 = findViewById(R.id.btn_zoom_2);
        mZoom5 = findViewById(R.id.btn_zoom_5);

        mModePhoto = findViewById(R.id.mode_photo);
        mModePortrait = findViewById(R.id.mode_portrait);
        mModeHq = findViewById(R.id.mode_hq);

        mShutterButton = findViewById(R.id.btn_shutter);
        mGalleryButton = findViewById(R.id.gallery_button);
        mGalleryThumb = findViewById(R.id.iv_gallery_thumb);
        mGalleryPlaceholder = findViewById(R.id.iv_gallery_placeholder);
        mCaptureFlyer = findViewById(R.id.iv_capture_flyer);
        mZoomDial = findViewById(R.id.zoom_dial);

        // Preserve the design-system gutters declared in XML; insets only add to them.
        if (mTopBar != null) {
            mTopBarSidePadding = mTopBar.getPaddingLeft();
        }
        if (mBottomControls != null) {
            mBottomSidePadding = mBottomControls.getPaddingLeft();
        }
    }

    private void applyImmersiveChrome() {
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x00000000);
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        decor.setSystemUiVisibility(flags);
    }

    private void applyWindowInsets() {
        if (mRoot == null) return;
        mRoot.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();

                if (mTopBar != null) {
                    mTopBar.setPadding(mTopBarSidePadding, top + Math.round(dp(6)),
                            mTopBarSidePadding, Math.round(dp(6)));
                }
                if (mBottomControls != null) {
                    mBottomControls.setPadding(mBottomSidePadding, 0, mBottomSidePadding,
                            bottom + Math.round(dp(10)));
                }
                return insets;
            }
        });
    }

    private void wireTopBar() {
        if (mFlashButton != null) {
            mFlashButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cycleFlash();
                }
            });
        }
        if (mProButton != null) {
            mProButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleProSheet(!mProSheetOpen);
                }
            });
        }
        if (mSwitchCameraButton != null) {
            mSwitchCameraButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cycleLens();
                }
            });
        }
        if (mGalleryButton != null) {
            mGalleryButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    performHaptic();
                    if (mLastThumbnail != null) {
                        Toast.makeText(MainActivity.this,
                                getString(R.string.status_photo_saved), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    private SeekBar.OnSeekBarChangeListener apertureListener() {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                applyApertureIndex(progress, seekBar);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                performHaptic();
            }
        };
    }

    /** Single source of truth for the f-stop: keeps the sheet and the quick row in sync. */
    private void applyApertureIndex(int index, SeekBar source) {
        mApertureIndex = Math.max(0, Math.min(FSTOPS.length - 1, index));
        mFStop = FSTOPS[mApertureIndex];
        String label = "ƒ " + formatFStop(mFStop);

        if (mApertureValue != null) {
            mApertureValue.setText(label);
        }
        if (mApertureValueQuick != null) {
            mApertureValueQuick.setText(label);
        }
        if (source != mApertureBar && mApertureBar != null
                && mApertureBar.getProgress() != mApertureIndex) {
            mApertureBar.setProgress(mApertureIndex);
        }
        if (source != mApertureBarQuick && mApertureBarQuick != null
                && mApertureBarQuick.getProgress() != mApertureIndex) {
            mApertureBarQuick.setProgress(mApertureIndex);
        }
    }

    private void wireProSheet() {
        if (mApertureBar != null) {
            mApertureBar.setOnSeekBarChangeListener(apertureListener());
        }
        if (mApertureBarQuick != null) {
            mApertureBarQuick.setOnSeekBarChangeListener(apertureListener());
        }

        if (mEvBar != null) {
            mEvBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    mEvSteps = progress - 12;
                    if (mEvValue != null) {
                        mEvValue.setText(formatEv(mEvSteps));
                    }
                    if (fromUser) {
                        sendEvToEngine();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    performHaptic();
                }
            });
        }

        if (mGridButton != null) {
            mGridButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mGridEnabled = !mGridEnabled;
                    if (mFocusOverlay != null) {
                        mFocusOverlay.setGridEnabled(mGridEnabled);
                    }
                    styleToggle(mGridButton, mGridEnabled);
                    performHaptic();
                }
            });
        }

        if (mRawButton != null) {
            mRawButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mRawEnabled = !mRawEnabled;
                    styleToggle(mRawButton, mRawEnabled);
                    performHaptic();
                }
            });
        }

        if (mTimerButton != null) {
            mTimerButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mTimerSeconds = (mTimerSeconds == 0) ? 3 : (mTimerSeconds == 3 ? 10 : 0);
                    mTimerButton.setText(mTimerSeconds == 0 ? "Timer" : (mTimerSeconds + "s"));
                    styleToggle(mTimerButton, mTimerSeconds > 0);
                    performHaptic();
                }
            });
        }

        View done = findViewById(R.id.btn_pro_done);
        if (done != null) {
            done.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleProSheet(false);
                }
            });
        }
    }

    private void wireLensChips() {
        View.OnClickListener lensClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                float target = ZOOM_MIN;
                if (v == mZoom2) target = 2.0f;
                else if (v == mZoom5) target = 5.0f;
                setZoom(target, true);
            }
        };
        if (mZoom1 != null) mZoom1.setOnClickListener(lensClick);
        if (mZoom2 != null) mZoom2.setOnClickListener(lensClick);
        if (mZoom5 != null) mZoom5.setOnClickListener(lensClick);

        if (mZoomDial != null) {
            mZoomDial.setZoomCallback(new ZoomDialView.ZoomCallback() {
                @Override
                public void onZoomChanged(float zoomRatio) {
                    applyZoom(zoomRatio);
                }
            });
        }
    }

    private void wireModeCarousel() {
        View.OnClickListener modeClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (v == mModePortrait) {
                    setMode(MODE_PORTRAIT);
                } else if (v == mModeHq) {
                    setMode(MODE_HQ);
                } else {
                    setMode(MODE_PHOTO);
                }
            }
        };
        if (mModePhoto != null) mModePhoto.setOnClickListener(modeClick);
        if (mModePortrait != null) mModePortrait.setOnClickListener(modeClick);
        if (mModeHq != null) mModeHq.setOnClickListener(modeClick);
    }

    private void wireShutter() {
        if (mShutterButton != null) {
            mShutterButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onShutterPressed();
                }
            });
        }
    }

    private void wireViewfinderGestures() {
        if (mViewfinder == null) return;

        final GestureDetector tapDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        handleViewfinderTap(e.getX(), e.getY());
                        return true;
                    }
                });

        final ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        applyZoom(mZoomRatio * detector.getScaleFactor());
                        return true;
                    }
                });

        mViewfinder.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                scaleDetector.onTouchEvent(event);
                if (!scaleDetector.isInProgress()) {
                    tapDetector.onTouchEvent(event);
                }
                return true;
            }
        });
    }

    // ═══════════════════════════ UI BEHAVIOUR ════════════════════════════════

    private void handleViewfinderTap(float x, float y) {
        if (mFocusOverlay != null) {
            mFocusOverlay.showFocusAt(x, y);
        }
        performHaptic();

        if (mService == null || mPreviewView == null) return;

        float w = Math.max(1f, mPreviewView.getWidth());
        float h = Math.max(1f, mPreviewView.getHeight());
        float u = Math.max(0f, Math.min(1f, x / w));
        float v = Math.max(0f, Math.min(1f, y / h));

        // The Vulkan presenter rotates the sensor by 90°: sensor_u = 1 - v, sensor_v = u
        try {
            mService.focusAt(1.0f - v, u);
        } catch (RemoteException e) {
            Log.w(TAG, "focusAt failed: " + e.getMessage());
        }
    }

    private void cycleFlash() {
        mFlashMode = (mFlashMode + 1) % 3;
        switch (mFlashMode) {
            case FLASH_AUTO:
                mFlashButton.setImageResource(R.drawable.ic_flash_auto);
                mFlashButton.setContentDescription(getString(R.string.flash_auto));
                updateStatus(getString(R.string.flash_state_auto));
                break;
            case FLASH_ON:
                mFlashButton.setImageResource(R.drawable.ic_flash_on);
                mFlashButton.setContentDescription(getString(R.string.flash_on));
                updateStatus(getString(R.string.flash_state_on));
                break;
            default:
                mFlashButton.setImageResource(R.drawable.ic_flash_off);
                mFlashButton.setContentDescription(getString(R.string.flash_off));
                updateStatus(getString(R.string.flash_state_off));
                break;
        }
        performHaptic();
        animateBump(mFlashButton);
        if (mService != null) {
            try {
                mService.setFlashMode(mFlashMode);
            } catch (RemoteException e) {
                Log.w(TAG, "setFlashMode failed: " + e.getMessage());
            }
        }
    }

    private void toggleProSheet(boolean open) {
        if (mProSheet == null) return;
        if (open == mProSheetOpen) return;
        mProSheetOpen = open;

        if (mProButton != null) {
            mProButton.setImageResource(open ? R.drawable.ic_close : R.drawable.ic_sliders);
            mProButton.setBackgroundResource(open
                    ? R.drawable.ios_circle_white_ripple
                    : R.drawable.ios_circle_ripple);
            mProButton.setColorFilter(open ? 0xFF000000 : 0xFFFFFFFF);
            animateBump(mProButton);
        }
        performHaptic();

        updateQuickApertureVisibility();

        if (open) {
            mProSheet.setVisibility(View.VISIBLE);
            mProSheet.setAlpha(0f);
            mProSheet.setTranslationY(dp(24));
            mProSheet.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(260)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            mProSheet.animate()
                    .alpha(0f)
                    .translationY(dp(24))
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (mProSheet != null) mProSheet.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    private void setMode(int mode) {
        if (mode == mMode) return;
        mMode = mode;
        applyModeVisuals(true);
        performHaptic();
    }

    private void applyModeVisuals(boolean animate) {
        styleModeLabel(mModePhoto, mMode == MODE_PHOTO, animate);
        styleModeLabel(mModePortrait, mMode == MODE_PORTRAIT, animate);
        styleModeLabel(mModeHq, mMode == MODE_HQ, animate);

        if (mShutterButton != null) {
            mShutterButton.setMode(mMode == MODE_PORTRAIT
                    ? ShutterButton.MODE_PORTRAIT
                    : ShutterButton.MODE_PHOTO);
        }
        if (mRowAperture != null) {
            mRowAperture.setVisibility(mMode == MODE_PORTRAIT ? View.VISIBLE : View.GONE);
        }
        updateQuickApertureVisibility();
        styleToggle(mRawButton, mRawEnabled);
        styleToggle(mGridButton, mGridEnabled);
        styleToggle(mTimerButton, mTimerSeconds > 0);
    }

    private void updateQuickApertureVisibility() {
        if (mRowApertureQuick == null) return;
        boolean show = (mMode == MODE_PORTRAIT) && !mProSheetOpen;
        mRowApertureQuick.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void styleModeLabel(TextView view, boolean selected, boolean animate) {
        if (view == null) return;
        view.setTextColor(selected ? 0xFFFFD60A : 0x66FFFFFF);
        if (animate) {
            view.animate().cancel();
            view.setScaleX(selected ? 0.92f : 1f);
            view.setScaleY(selected ? 0.92f : 1f);
            view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private void styleToggle(Button button, boolean active) {
        if (button == null) return;
        button.setTextColor(active ? 0xFF000000 : 0xFFFFFFFF);
        button.setBackgroundResource(active
                ? R.drawable.ios_pill_white_ripple
                : R.drawable.ios_capsule_ripple);
    }

    private String formatFStop(float fStop) {
        if (fStop >= 9.95f) {
            return String.format(Locale.US, "%.0f", fStop);
        }
        return String.format(Locale.US, "%.1f", fStop);
    }

    private String formatEv(int steps) {
        float ev = steps / 3f;
        if (Math.abs(ev) < 0.05f) return "0 EV";
        return String.format(Locale.US, "%+.1f EV", ev);
    }

    private void syncApertureFromSlider() {
        applyApertureIndex(mApertureIndex, null);
        if (mEvValue != null) {
            mEvValue.setText(formatEv(mEvSteps));
        }
        styleToggle(mGridButton, mGridEnabled);
        styleToggle(mRawButton, mRawEnabled);
        styleToggle(mTimerButton, mTimerSeconds > 0);
    }

    private void sendEvToEngine() {
        if (mService == null) return;
        try {
            mService.setEvCompensation(mEvSteps);
        } catch (RemoteException e) {
            Log.w(TAG, "setEvCompensation failed: " + e.getMessage());
        }
    }

    /** Updates the chips + radial dial for a new zoom ratio and tells the engine. */
    private void applyZoom(float ratio) {
        float z = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, ratio));
        if (Math.abs(z - mZoomRatio) < 0.005f) return;
        mZoomRatio = z;

        updateZoomChips();

        if (mZoomDial != null) {
            if (mZoomDial.getVisibility() != View.VISIBLE) {
                mZoomDial.show(mZoomRatio, ZOOM_MIN, ZOOM_MAX, CAMERA_ID);
            } else {
                mZoomDial.updateZoom(mZoomRatio);
            }
        }

        // Pinch generates dozens of events per gesture; only forward real changes.
        if (Math.abs(mZoomRatio - mLastSentZoom) > 0.02f) {
            sendZoomToEngine();
        }
    }

    private void sendZoomToEngine() {
        mLastSentZoom = mZoomRatio;
        if (mService == null) return;
        try {
            mService.setZoom(mZoomRatio);
        } catch (RemoteException e) {
            Log.w(TAG, "setZoom failed: " + e.getMessage());
        }
    }

    private void setZoom(float ratio, boolean haptic) {
        applyZoom(ratio);
        sendZoomToEngine();
        if (haptic) performHaptic();
    }

    private void updateZoomChips() {
        applyChipState(mZoom1, Math.abs(mZoomRatio - 1.0f) < 0.08f);
        applyChipState(mZoom2, Math.abs(mZoomRatio - 2.0f) < 0.12f);
        applyChipState(mZoom5, Math.abs(mZoomRatio - 5.0f) < 0.3f);
    }

    private void applyChipState(TextView chip, boolean selected) {
        if (chip == null) return;
        chip.setBackgroundResource(selected
                ? R.drawable.ios_circle_white_ripple
                : R.drawable.ios_circle_ripple);
        chip.setTextColor(selected ? 0xFF000000 : 0xFFFFFFFF);
    }

    // ── Lens / camera switching ─────────────────────────────────────────────

    String currentLensId() {
        if (mPhysicalLenses.isEmpty()) return "0";
        if (mPhysicalLenses.contains(mCurrentLens)) return mCurrentLens;
        return mPhysicalLenses.get(0);
    }

    private void discoverLenses() {
        mPhysicalLenses.clear();
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm != null) {
                CameraCharacteristics chars = cm.getCameraCharacteristics(CAMERA_ID);
                Set<String> ids = chars.getPhysicalCameraIds();
                if (ids != null) {
                    mPhysicalLenses.addAll(ids);
                    Collections.sort(mPhysicalLenses);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "discoverLenses: " + t);
        }

        int defaultIndex = mPhysicalLenses.indexOf("0");
        mLensIndex = defaultIndex >= 0 ? defaultIndex : 0;

        if (mSwitchCameraButton != null) {
            mSwitchCameraButton.setVisibility(mPhysicalLenses.size() > 1
                    ? View.VISIBLE : View.INVISIBLE);
        }
        Log.i(TAG, "Physical lenses for camera " + CAMERA_ID + ": " + mPhysicalLenses);
    }

    private void cycleLens() {
        if (mPhysicalLenses.size() < 2) return;
        mLensIndex = (mLensIndex + 1) % mPhysicalLenses.size();
        String lens = mPhysicalLenses.get(mLensIndex);
        mCurrentLens = lens;
        mLensSwitchPending = true;
        applyZoom(1.0f);
        // The service resets its crop region on lens restart — mirror that here.
        mLastSentZoom = ZOOM_MIN;
        updateStatus("Lens " + (mLensIndex + 1) + "/" + mPhysicalLenses.size());
        animateBump(mSwitchCameraButton);
        performHaptic();
        if (mService != null) {
            try {
                mService.setPhysicalLens(lens);
            } catch (RemoteException e) {
                Log.w(TAG, "setPhysicalLens failed: " + e.getMessage());
            }
        }
    }

    /** A failed lens switch must never leave the user with a dead viewfinder. */
    private void revertLensAfterFailure() {
        if (!mLensSwitchPending) return;
        mLensSwitchPending = false;
        mCurrentLens = "0";
        int defaultIndex = mPhysicalLenses.indexOf("0");
        mLensIndex = defaultIndex >= 0 ? defaultIndex : 0;
        if (mService != null) {
            try {
                mService.setPhysicalLens("");
            } catch (RemoteException e) {
                Log.w(TAG, "lens revert failed: " + e.getMessage());
            }
        }
        updateStatus(getString(R.string.status_ready));
    }

    // ── Capture ─────────────────────────────────────────────────────────────

    private void onShutterPressed() {
        if (mShutterButton != null) {
            mShutterButton.playCaptureFeedback();
        }
        playCaptureFlash();

        if (mTimerSeconds > 0) {
            startTimerCapture(mTimerSeconds);
            return;
        }
        fireCapture();
    }

    private void startTimerCapture(final int secondsRemaining) {
        if (secondsRemaining <= 0) {
            fireCapture();
            return;
        }
        updateStatus(secondsRemaining + "s");
        performHaptic();
        mUiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startTimerCapture(secondsRemaining - 1);
            }
        }, 1000L);
    }

    private void fireCapture() {
        if (mService == null) {
            updateStatus("Camera not ready");
            Toast.makeText(this, R.string.status_connecting, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean highQuality = (mMode == MODE_HQ);
        triggerCapture(highQuality, true);
    }

    private void playCaptureFlash() {
        if (mCaptureFlash == null) return;
        mCaptureFlash.animate().cancel();
        mCaptureFlash.setAlpha(0f);
        mCaptureFlash.animate()
                .alpha(0.9f)
                .setDuration(55)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        mCaptureFlash.animate().alpha(0f).setDuration(240).start();
                    }
                })
                .start();
    }

    private void performHaptic() {
        try {
            mRoot.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } catch (Throwable t) {
            // device without a vibrator
        }
    }

    private void animateBump(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setScaleX(0.86f);
        view.setScaleY(0.86f);
        view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    // ── Gallery thumbnail ───────────────────────────────────────────────────

    private void setGalleryThumbnail(Bitmap bitmap) {
        mLastThumbnail = bitmap;
        if (mGalleryThumb == null) return;
        if (bitmap != null) {
            mGalleryThumb.setImageBitmap(bitmap);
            mGalleryThumb.setVisibility(View.VISIBLE);
            if (mGalleryPlaceholder != null) mGalleryPlaceholder.setVisibility(View.GONE);
        } else {
            mGalleryThumb.setVisibility(View.GONE);
            if (mGalleryPlaceholder != null) mGalleryPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    private void pulseGallery() {
        if (mGalleryButton == null) return;
        mGalleryButton.animate().cancel();
        mGalleryButton.setScaleX(0.82f);
        mGalleryButton.setScaleY(0.82f);
        mGalleryButton.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(320)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** Builds a small colour thumbnail straight out of the raw RGBA bokeh dump. */
    private Bitmap buildThumbnailFromRaw(String rgbaPath, int srcW, int srcH) {
        final int tw = 168;
        final int th = 126;
        RandomAccessFile raf = null;
        try {
            File file = new File(rgbaPath);
            if (!file.exists() || file.length() < (long) srcW * srcH * 2L) return null;

            raf = new RandomAccessFile(file, "r");
            int[] pixels = new int[tw * th];
            byte[] row = new byte[srcW * 4];

            for (int ty = 0; ty < th; ty++) {
                int sy = (int) ((ty + 0.5f) * srcH / th);
                raf.seek((long) sy * srcW * 4L);
                raf.readFully(row, 0, row.length);
                for (int tx = 0; tx < tw; tx++) {
                    int sx = (int) ((tx + 0.5f) * srcW / tw);
                    int o = sx * 4;
                    int r = row[o] & 0xFF;
                    int g = row[o + 1] & 0xFF;
                    int b = row[o + 2] & 0xFF;
                    pixels[ty * tw + tx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }

            Bitmap landscape = Bitmap.createBitmap(pixels, tw, th, Bitmap.Config.ARGB_8888);
            Matrix rotation = new Matrix();
            rotation.postRotate(90f);
            Bitmap portrait = Bitmap.createBitmap(landscape, 0, 0, tw, th, rotation, true);
            if (portrait != landscape) {
                landscape.recycle();
            }
            return portrait;
        } catch (Throwable t) {
            Log.w(TAG, "Thumbnail decode failed: " + t);
            return null;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Apple's "photo flies into the gallery" hand-off. */
    private void flyThumbnailToGallery(final Bitmap bitmap) {
        if (bitmap == null || mCaptureFlyer == null || mGalleryButton == null
                || mShutterButton == null) {
            setGalleryThumbnail(bitmap);
            return;
        }

        int[] shutterPos = new int[2];
        int[] galleryPos = new int[2];
        int[] rootPos = new int[2];
        mShutterButton.getLocationInWindow(shutterPos);
        mGalleryButton.getLocationInWindow(galleryPos);
        mRoot.getLocationInWindow(rootPos);

        final float startCx = shutterPos[0] - rootPos[0] + mShutterButton.getWidth() / 2f;
        final float startCy = shutterPos[1] - rootPos[1] + mShutterButton.getHeight() / 2f;
        final float endCx = galleryPos[0] - rootPos[0] + mGalleryButton.getWidth() / 2f;
        final float endCy = galleryPos[1] - rootPos[1] + mGalleryButton.getHeight() / 2f;

        final int size = Math.round(dp(96));
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) mCaptureFlyer.getLayoutParams();
        lp.width = size;
        lp.height = size;
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        mCaptureFlyer.setLayoutParams(lp);
        mCaptureFlyer.setImageBitmap(bitmap);
        mCaptureFlyer.setAlpha(0f);
        mCaptureFlyer.setScaleX(0.72f);
        mCaptureFlyer.setScaleY(0.72f);
        mCaptureFlyer.setTranslationX(startCx - size / 2f);
        mCaptureFlyer.setTranslationY(startCy - size / 2f);
        mCaptureFlyer.setVisibility(View.VISIBLE);

        mCaptureFlyer.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(130)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        float endScale = Math.max(0.2f,
                                (float) mGalleryButton.getWidth() / (float) size);
                        mCaptureFlyer.animate()
                                .translationX(endCx - size / 2f)
                                .translationY(endCy - size / 2f)
                                .scaleX(endScale)
                                .scaleY(endScale)
                                .setDuration(330)
                                .setInterpolator(new DecelerateInterpolator(1.4f))
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        mCaptureFlyer.setVisibility(View.INVISIBLE);
                                        setGalleryThumbnail(bitmap);
                                        pulseGallery();
                                    }
                                })
                                .start();
                    }
                })
                .start();
    }

    private void onStillFileReady(final String outPath) {
        if (outPath == null || mIoExecutor == null) return;
        mIoExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap thumb = buildThumbnailFromRaw(outPath, 4080, 3072);
                if (thumb == null) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        flyThumbnailToGallery(thumb);
                    }
                });
            }
        });
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    // ═══════════════════════════ STATUS / TELEMETRY ══════════════════════════

    void updateStatus(String status) {
        Log.i(TAG, "Status: " + status);
        if (mStatusText != null) {
            mStatusText.setText(status);
            mStatusText.setAlpha(0f);
            mStatusText.animate().alpha(1f).setDuration(160).start();
        }
    }

    // ═══════════════════════════ CAMERA PLUMBING ═════════════════════════════

    static class AuxThermalListener implements PowerManager.OnThermalStatusChangedListener {
        private final MainActivity activity;

        AuxThermalListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onThermalStatusChanged(int status) {
            activity.onThermalChanged(status);
        }
    }

    static class CaptureBroadcastReceiver extends android.content.BroadcastReceiver {
        private final MainActivity activity;

        CaptureBroadcastReceiver(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            boolean hq = intent.getBooleanExtra("high_quality", false);
            boolean zsl = intent.getBooleanExtra("enable_zsl", true);
            activity.triggerCapture(hq, zsl);
        }
    }

    void onThermalChanged(int status) {
        Log.i(TAG, "Thermal status changed: " + status);
        boolean severe = (status >= PowerManager.THERMAL_STATUS_SEVERE);
        try {
            nativeSetThermalThrottled(severe);
        } catch (UnsatisfiedLinkError e) {
            // bridge not loaded
        }
        if (mTelemetryText != null) {
            mTelemetryText.setText("THERMAL · " + getThermalStatusName(status)
                    + (severe ? " · throttled" : ""));
        }
    }

    String getThermalAndBatteryInfo() {
        int thermalStatus = -1;
        if (mPowerManager != null
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            thermalStatus = mPowerManager.getCurrentThermalStatus();
        }
        float batteryTempC = -1.0f;
        try {
            Intent intent = registerReceiver(null,
                    new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                int temp = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0);
                batteryTempC = temp / 10.0f;
            }
        } catch (Throwable t) {
            // ignore
        }
        return "thermalStatus=" + thermalStatus + " (" + getThermalStatusName(thermalStatus)
                + "), batteryTemp=" + batteryTempC + "°C";
    }

    private static String getThermalStatusName(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE: return "NONE";
            case PowerManager.THERMAL_STATUS_LIGHT: return "LIGHT";
            case PowerManager.THERMAL_STATUS_MODERATE: return "MODERATE";
            case PowerManager.THERMAL_STATUS_SEVERE: return "SEVERE";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "CRITICAL";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "EMERGENCY";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "SHUTDOWN";
            default: return "UNKNOWN";
        }
    }

    static class PermResultListener implements Shizuku.OnRequestPermissionResultListener {
        private final MainActivity activity;

        PermResultListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                activity.updateStatus("Connecting…");
                activity.bindUserService();
            } else {
                activity.updateStatus("Shizuku denied");
            }
        }
    }

    static class BinderRecvListener implements Shizuku.OnBinderReceivedListener {
        private final MainActivity activity;

        BinderRecvListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onBinderReceived() {
            activity.checkPermissionAndBind();
        }
    }

    static class BinderDeadListener implements Shizuku.OnBinderDeadListener {
        private final MainActivity activity;

        BinderDeadListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onBinderDead() {
            activity.mService = null;
            activity.mSessionReady = false;
            activity.updateStatus("Reconnecting…");
        }
    }

    static class AuxServiceConnection implements ServiceConnection {
        private final MainActivity activity;

        AuxServiceConnection(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            activity.mService = IAuxCamService.Stub.asInterface(binder);
            activity.updateStatus("Opening session…");
            activity.startStereoSession();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            activity.mService = null;
            activity.mSessionReady = false;
            activity.updateStatus("Disconnected");
        }
    }

    static class AuxCamCallbackImpl extends IAuxCamCallback.Stub {
        private final MainActivity activity;

        AuxCamCallbackImpl(MainActivity a) {
            this.activity = a;
        }

        @Override
        public void onSessionReady() {
            activity.runOnUiThread(new SessionReadyRunnable(activity));
        }

        @Override
        public void onSessionError(String msg) {
            activity.runOnUiThread(new ErrorRunnable(activity, msg));
        }

        @Override
        public void onStillCaptured() {
            activity.runOnUiThread(new StillCapturedRunnable(activity));
        }

        @Override
        public void onStats(float colorFps, float depthFps, float dt) {
            activity.runOnUiThread(new StatsRunnable(activity, colorFps, depthFps, dt));
        }
    }

    static class SessionReadyRunnable implements Runnable {
        private final MainActivity activity;

        SessionReadyRunnable(MainActivity a) {
            activity = a;
        }

        @Override
        public void run() {
            activity.mSessionReady = true;
            activity.mLensSwitchPending = false;
            activity.mCurrentLens = activity.currentLensId();
            activity.updateStatus(activity.getString(R.string.status_ready));
        }
    }

    static class ErrorRunnable implements Runnable {
        private final MainActivity activity;
        private final String msg;

        ErrorRunnable(MainActivity a, String m) {
            activity = a;
            msg = m;
        }

        @Override
        public void run() {
            activity.updateStatus("Session error");
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
            activity.revertLensAfterFailure();
        }
    }

    static class StillCapturedRunnable implements Runnable {
        private final MainActivity activity;

        StillCapturedRunnable(MainActivity a) {
            activity = a;
        }

        @Override
        public void run() {
            activity.updateStatus(activity.getString(R.string.status_photo_saved));
        }
    }

    static class StatsRunnable implements Runnable {
        private final MainActivity activity;
        private final float colorFps;
        private final float depthFps;
        private final float dt;

        StatsRunnable(MainActivity a, float c, float d, float t) {
            activity = a;
            colorFps = c;
            depthFps = d;
            dt = t;
        }

        @Override
        public void run() {
            if (activity.mTelemetryText != null) {
                activity.mTelemetryText.setText(String.format(Locale.US,
                        "VIEWFINDER · %.0f FPS  ·  DEPTH · %.0f FPS  ·  Δt %.1f ms",
                        colorFps, depthFps, dt));
            }
        }
    }

    private void startReaderThread() {
        mReaderThread = new HandlerThread("ImageReaderThread");
        mReaderThread.start();
        mReaderHandler = new Handler(mReaderThread.getLooper());
    }

    private void stopReaderThread() {
        if (mReaderThread != null) {
            mReaderThread.quitSafely();
            try {
                mReaderThread.join();
                mReaderThread = null;
                mReaderHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping reader thread", e);
            }
        }
    }

    private void setupImageReaders() {
        // Locked ground truth:
        // Color: 1440x1080 YUV_420_888 USAGE_GPU_SAMPLED_IMAGE
        mColorReader = ImageReader.newInstance(
                1440, 1080, ImageFormat.YUV_420_888, 3,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        mColorReader.setOnImageAvailableListener(new ColorImageListener(this), mReaderHandler);

        // Depth: 448x288 Y8 USAGE_GPU_SAMPLED_IMAGE | USAGE_CPU_READ_OFTEN
        mDepthReader = ImageReader.newInstance(
                448, 288, 0x20203859, 3, // ImageFormat.Y8
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_CPU_READ_OFTEN);
        mDepthReader.setOnImageAvailableListener(new DepthImageListener(this), mReaderHandler);

        // Still: 4080x3072 YUV_420_888 USAGE_GPU_SAMPLED_IMAGE
        mStillReader = ImageReader.newInstance(
                4080, 3072, ImageFormat.YUV_420_888, 2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        mStillReader.setOnImageAvailableListener(new StillImageListener(this), mReaderHandler);
    }

    static class ColorImageListener implements ImageReader.OnImageAvailableListener {
        private final MainActivity activity;

        ColorImageListener(MainActivity a) {
            activity = a;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            long t0 = SystemClock.elapsedRealtimeNanos();
            Image img = reader.acquireLatestImage();
            if (img != null) {
                activity.mTotalColorFrames++;
                HardwareBuffer hb = img.getHardwareBuffer();
                if (hb != null) {
                    try {
                        activity.nativeRenderFrame(hb, activity.mLatestDepth, 448, 288,
                                activity.mFStop);
                    } catch (Throwable t) {
                        Log.e(TAG, "renderFrame error: " + t.getMessage());
                    } finally {
                        hb.close();
                    }
                }
                img.close();
                long t1 = SystemClock.elapsedRealtimeNanos();
                long dtMs = (t1 - t0) / 1_000_000;
                activity.recordPreviewFrameTime(dtMs);
            }
        }
    }

    static class DepthImageListener implements ImageReader.OnImageAvailableListener {
        private final MainActivity activity;

        DepthImageListener(MainActivity a) {
            activity = a;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            Image img = reader.acquireLatestImage();
            if (img != null) {
                activity.mTotalDepthFrames++;
                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                if (activity.mLatestDepth == null
                        || activity.mLatestDepth.length != buf.remaining()) {
                    activity.mLatestDepth = new byte[buf.remaining()];
                }
                buf.get(activity.mLatestDepth);
                img.close();
            }
        }
    }

    static class StillImageListener implements ImageReader.OnImageAvailableListener {
        private final MainActivity activity;

        StillImageListener(MainActivity a) {
            activity = a;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            long t2 = SystemClock.elapsedRealtimeNanos();
            Image img = reader.acquireLatestImage();
            if (img != null) {
                long t3 = SystemClock.elapsedRealtimeNanos();
                HardwareBuffer hb = img.getHardwareBuffer();
                long gpuElapsedNs = 0;
                long t4 = t3;
                long t5 = t3;
                String outPath = null;
                if (hb != null) {
                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                            .format(new Date());
                    File cameraDir = activity.getExternalFilesDir("bokeh_raw");
                    if (cameraDir == null) cameraDir = activity.getFilesDir();
                    if (!cameraDir.exists()) cameraDir.mkdirs();
                    File outFile = new File(cameraDir, "AUXCAM_" + timeStamp + ".rgba");
                    outPath = outFile.getAbsolutePath();

                    // Optional RAW Y-plane + depth dump for calibration work
                    if (activity.mRawEnabled) {
                        try {
                            Image.Plane yPlane = img.getPlanes()[0];
                            ByteBuffer yBuf = yPlane.getBuffer();
                            byte[] yBytes = new byte[yBuf.remaining()];
                            yBuf.get(yBytes);
                            File rawYFile = new File(cameraDir,
                                    "AUXCAM_" + timeStamp + "_raw_y.bin");
                            FileOutputStream yfos = new FileOutputStream(rawYFile);
                            yfos.write(yBytes);
                            yfos.close();

                            if (activity.mLatestDepth != null) {
                                File depthFile = new File(cameraDir,
                                        "AUXCAM_" + timeStamp + "_depth.y8");
                                FileOutputStream dfos = new FileOutputStream(depthFile);
                                dfos.write(activity.mLatestDepth);
                                dfos.close();
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "Raw save note: " + t.getMessage());
                        }
                    }

                    long[] timestampsOut = new long[3];
                    boolean ok = activity.nativeProcessStillBokeh(hb, activity.mLatestDepth,
                            448, 288, activity.mFStop, 15.0f, outPath, timestampsOut);
                    t4 = SystemClock.elapsedRealtimeNanos();
                    gpuElapsedNs = timestampsOut[0];
                    t5 = t4;
                    hb.close();
                }
                img.close();
                activity.dispatchStillComplete(activity.mShutterClickNs, t2, t3, t4, t5,
                        gpuElapsedNs, outPath, activity.mMode == MODE_HQ, true);
            }
        }
    }

    void dispatchStillComplete(long t0, long t2, long t3, long t4, long t5, long gpuElapsedNs,
                               String outPath, boolean highQualityNr, boolean zslEnabled) {
        long d_shutter_to_yuv = (t2 - t0) / 1_000_000;
        long d_gpu_bokeh = (t4 - t3) / 1_000_000;
        long total_shutter_to_buffer = (t4 - t0) / 1_000_000;
        long total_shutter_to_file = (t5 - t0) / 1_000_000;
        long gpu_internal_ms = gpuElapsedNs / 1_000_000;
        long fileSizeBytes = 0;
        if (outPath != null) {
            File f = new File(outPath);
            if (f.exists()) fileSizeBytes = f.length();
        }

        Log.i(TAG, "================ STILL PATH END-TO-END VERIFICATION ================");
        Log.i(TAG, "Environment: " + getThermalAndBatteryInfo());
        Log.i(TAG, "Mode: " + (highQualityNr ? "HIGH_QUALITY_NR" : "FAST_NR")
                + " | ZSL: " + (zslEnabled ? "ENABLED" : "DISABLED")
                + " | f/" + formatFStop(mFStop) + " | zoom=" + mZoomRatio + "x");
        Log.i(TAG, "t0 (shutter_command):           " + t0 + " ns");
        Log.i(TAG, "t2 (HAL YUV buffer available):   " + t2 + " ns");
        Log.i(TAG, "t3 (GPU bokeh pass start):       " + t3 + " ns");
        Log.i(TAG, "t4 (COMPOSITE BUFFER COMPLETE):  " + t4 + " ns");
        Log.i(TAG, "t5 (FILE CLOSED / SAVED):        " + t5 + " ns");
        Log.i(TAG, "Delta 1+2 (Shutter -> HAL YUV Buffer): " + d_shutter_to_yuv + " ms");
        Log.i(TAG, "Delta GPU (32-tap Gather + Composite):  " + d_gpu_bokeh
                + " ms (GPU submit-to-fence: " + gpu_internal_ms + " ms)");
        Log.i(TAG, "TOTAL SHUTTER -> COMPOSITE COMPLETE:    " + total_shutter_to_buffer
                + " ms (Gate: <= 500 ms) -> " + (total_shutter_to_buffer <= 500 ? "PASS" : "FAIL"));
        Log.i(TAG, "TOTAL SHUTTER -> FILE CLOSED: " + total_shutter_to_file
                + " ms | size=" + fileSizeBytes + " bytes");
        Log.i(TAG, "Saved File: " + outPath);
        Log.i(TAG, "====================================================================");

        if (outPath != null) {
            onStillFileReady(outPath);
        }
    }

    void startStereoSession() {
        if (mService == null || mDepthReader == null || mStillReader == null
                || mColorReader == null) {
            return;
        }

        Surface previewSurface = mColorReader.getSurface();
        if (previewSurface == null) return;

        try {
            mService.setCallback(new AuxCamCallbackImpl(this));
            boolean ok = mService.openStereoSession(
                    previewSurface, 1440, 1080,
                    mDepthReader.getSurface(), 448, 288,
                    mStillReader.getSurface(), 4080, 3072,
                    mCurrentLens);
            if (!ok) {
                updateStatus("Session failed");
            } else {
                updateStatus("Live bokeh engine");
            }
        } catch (RemoteException e) {
            updateStatus("Remote error");
        }
    }

    void checkPermissionAndBind() {
        if (Shizuku.getVersion() < 11) {
            updateStatus("Shizuku v11+ required");
            return;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindUserService();
        } else {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        }
    }

    void bindUserService() {
        updateStatus("Binding service…");
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(getPackageName(), CameraUserService.class.getName()))
                .daemon(false)
                .version(2)
                .processNameSuffix("auxcam_privileged");

        Shizuku.bindUserService(args, mServiceConnection);
    }

    void triggerCapture(boolean highQualityNr) {
        triggerCapture(highQualityNr, true);
    }

    void triggerCapture(boolean highQualityNr, boolean enableZsl) {
        if (mService != null) {
            mShutterClickNs = SystemClock.elapsedRealtimeNanos();
            updateStatus("Capturing…");
            try {
                mService.triggerStillWithZsl(mShutterClickNs, highQualityNr, enableZsl);
            } catch (RemoteException e) {
                Log.e(TAG, "triggerStill remote exception", e);
            }
        }
    }

    private final List<Long> mPreviewFrameTimes = new ArrayList<>();

    void recordPreviewFrameTime(long dtMs) {
        synchronized (mPreviewFrameTimes) {
            mPreviewFrameTimes.add(dtMs);
            if (mPreviewFrameTimes.size() % 60 == 0) {
                List<Long> sorted = new ArrayList<>(mPreviewFrameTimes);
                Collections.sort(sorted);
                int n = sorted.size();
                long p50 = sorted.get(n / 2);
                long p95 = sorted.get((int) (n * 0.95));
                Log.i(TAG, "PREVIEW FRAME-TIME (N=" + n + ") p50=" + p50 + " ms, p95=" + p95
                        + " ms, min=" + sorted.get(0) + " ms, max=" + sorted.get(n - 1) + " ms");
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurfaceHolder = holder;
        try {
            boolean ok = nativeInitEngine(holder.getSurface(), 1440, 1080);
            Log.i(TAG, "nativeInitEngine result: " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "nativeInitEngine exception: " + t.getMessage());
        }
        if (mService != null) {
            startStereoSession();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mSurfaceHolder = holder;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSurfaceHolder = null;
    }
}
