package com.zenith.auxcam

import android.content.Intent
import android.graphics.Color
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.zenith.auxcam.ui.CameraScreen
import com.zenith.auxcam.ui.theme.NotiCamTheme

/**
 * Compose host for the AuxCam bokeh camera.
 *
 * The activity keeps only what must live in the Android world:
 *  • window flags (edge-to-edge, keep screen on, transparent bars)
 *  • the four JNI entry points, whose symbol names are part of the native ABI
 *  • wiring the immutable [CameraUiState] into [CameraScreen]
 *
 * All camera logic lives in [CameraController]; all UI lives in Compose.
 */
class MainActivity : ComponentActivity(), NativeBridge {

    private lateinit var controller: CameraController

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: Compose owns the insets (see CameraScreen).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }

        extractAssetsIfNeeded()

        val disableGuidedIntent = intent?.getBooleanExtra("disable_guided", false) ?: false
        if (disableGuidedIntent) {
            DISABLE_GUIDED = true
        }

        val forceFps30Intent = intent?.getBooleanExtra("force_fps_30", false) ?: false
        if (forceFps30Intent) {
            CameraUserService.sForceFps30 = true
        }

        val edgeRefineIntent = intent?.let {
            if (it.hasExtra("edge_refine")) {
                try { it.getIntExtra("edge_refine", -1) } catch (e: Exception) { it.getStringExtra("edge_refine")?.toIntOrNull() ?: -1 }
            } else null
        }
        if (edgeRefineIntent != null && edgeRefineIntent >= 0) {
            setEdgeRefineOverride(edgeRefineIntent)
            Log.i("MainActivity", "Edge refine override set from intent: $edgeRefineIntent")
        }

        val emaIntent = intent?.let {
            if (it.hasExtra("ema_alpha")) {
                try { it.getFloatExtra("ema_alpha", 0.25f) } catch (e: Exception) { it.getStringExtra("ema_alpha")?.toFloatOrNull() ?: 0.25f }
            } else null
        }
        if (emaIntent != null) {
            DualMatteSegmenter.sEmaAlpha = emaIntent
            Log.i("MainActivity", "EMA alpha set from intent: $emaIntent")
        }

        controller = CameraController(this, this)

        setContent {
            NotiCamTheme {
                val state by controller.uiState
                CameraScreen(state = state, actions = controller)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("edge_refine")) {
            val er = try { intent.getIntExtra("edge_refine", -1) } catch (e: Exception) { intent.getStringExtra("edge_refine")?.toIntOrNull() ?: -1 }
            if (er >= 0) {
                setEdgeRefineOverride(er)
                Log.i("MainActivity", "onNewIntent: Edge refine override set to $er")
            }
        }
        if (intent.hasExtra("ema_alpha")) {
            val ema = try { intent.getFloatExtra("ema_alpha", 0.25f) } catch (e: Exception) { intent.getStringExtra("ema_alpha")?.toFloatOrNull() ?: 0.25f }
            DualMatteSegmenter.sEmaAlpha = ema
            Log.i("MainActivity", "onNewIntent: EMA alpha set to $ema")
        }
    }

    override fun onStart() {
        super.onStart()
        controller.start()
    }

    override fun onStop() {
        controller.pause()
        super.onStop()
    }

    override fun onDestroy() {
        controller.dispose()
        super.onDestroy()
    }

    // ══════════════════════ NativeBridge → JNI ═══════════════════════════════

    override fun initEngine(surface: Surface, width: Int, height: Int): Boolean =
        try { nativeInitEngine(surface, width, height) }
        catch (e: UnsatisfiedLinkError) { Log.e(TAG, "Native engine unavailable", e); false }

    override fun renderFrame(
        buffer: HardwareBuffer,
        depth: ByteArray?,
        depthW: Int,
        depthH: Int,
        alphaMatte: ByteArray?,
        alphaW: Int,
        alphaH: Int,
        fStop: Float,
        targetFarFieldPx: Float,
    ): Boolean = nativeRenderFrame(
        buffer,
        depth,
        depthW,
        depthH,
        alphaMatte,
        alphaW,
        alphaH,
        fStop,
        targetFarFieldPx,
    )

    override fun setCameraOpticalParams(focalMm: Float, sensorWidthMm: Float, baseFStop: Float) {
        try {
            nativeSetCameraOpticalParams(focalMm, sensorWidthMm, baseFStop)
        } catch (t: UnsatisfiedLinkError) {
            Log.w(TAG, "optical params unavailable: ${t.message}")
        }
    }

    override fun processStill(
        buffer: HardwareBuffer,
        depth: ByteArray?,
        depthW: Int,
        depthH: Int,
        alphaMatte: ByteArray?,
        alphaW: Int,
        alphaH: Int,
        fStop: Float,
        targetFarFieldPx: Float,
        outPath: String,
        timestampsOut: LongArray,
    ): Boolean = nativeProcessStillBokeh(
        buffer,
        depth,
        depthW,
        depthH,
        alphaMatte,
        alphaW,
        alphaH,
        fStop,
        targetFarFieldPx,
        outPath,
        timestampsOut,
    )

    override fun setThermalThrottled(throttled: Boolean) {
        try {
            nativeSetThermalThrottled(throttled)
        } catch (t: UnsatisfiedLinkError) {
            Log.w(TAG, "thermal bridge unavailable: ${t.message}")
        }
    }

    override fun setDisableGuided(disable: Boolean) {
        DISABLE_GUIDED = disable
    }

    override fun destroyEngine() {
        try {
            nativeDestroyEngine()
        } catch (t: UnsatisfiedLinkError) {
            Log.w(TAG, "destroy bridge unavailable: ${t.message}")
        }
    }

    override fun acquireHardwareBuffer(buffer: HardwareBuffer) {
        try {
            nativeAcquireHardwareBuffer(buffer)
        } catch (t: Throwable) {
            Log.w(TAG, "acquireHardwareBuffer error: ${t.message}")
        }
    }

    override fun releaseHardwareBuffer(buffer: HardwareBuffer) {
        try {
            nativeReleaseHardwareBuffer(buffer)
        } catch (t: Throwable) {
            Log.w(TAG, "releaseHardwareBuffer error: ${t.message}")
        }
    }

    override fun notifyMatteDrop() {
        try {
            nativeNotifyMatteDrop()
        } catch (t: Throwable) {
            Log.w(TAG, "notifyMatteDrop error: ${t.message}")
        }
    }

    override fun getAhbRefcount(): Int {
        return try {
            nativeGetAhbRefcount()
        } catch (t: Throwable) {
            0
        }
    }

    private external fun nativeInitEngine(surface: Surface, width: Int, height: Int): Boolean

    private external fun nativeRenderFrame(
        buffer: HardwareBuffer,
        depth: ByteArray?,
        depthW: Int,
        depthH: Int,
        alphaMatte: ByteArray?,
        alphaW: Int,
        alphaH: Int,
        fStop: Float,
        targetFarFieldPx: Float,
    ): Boolean

    private external fun nativeSetCameraOpticalParams(
        focalMm: Float,
        sensorWidthMm: Float,
        baseFStop: Float,
    )

    private external fun nativeProcessStillBokeh(
        buffer: HardwareBuffer,
        depth: ByteArray?,
        depthW: Int,
        depthH: Int,
        alphaMatte: ByteArray?,
        alphaW: Int,
        alphaH: Int,
        fStop: Float,
        targetFarFieldPx: Float,
        outPath: String,
        timestampsOut: LongArray,
    ): Boolean

    private external fun nativeAcquireHardwareBuffer(buffer: HardwareBuffer)
    private external fun nativeReleaseHardwareBuffer(buffer: HardwareBuffer)
    private external fun nativeNotifyMatteDrop()
    private external fun nativeGetAhbRefcount(): Int

    private fun extractAssetsIfNeeded() {
        try {
            val shaderDir = java.io.File(filesDir, "shaders")
            if (!shaderDir.exists()) shaderDir.mkdirs()
            val shaderList = assets.list("shaders") ?: return
            for (name in shaderList) {
                val outFile = java.io.File(shaderDir, name)
                // Length is not a content hash: shader edits often preserve
                // byte count. Always refresh atomically from this installed APK.
                val temporary = java.io.File(shaderDir, "$name.tmp")
                assets.open("shaders/$name").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                check(temporary.renameTo(outFile)) { "Cannot replace shader $name" }
            }

            val modelDir = java.io.File(filesDir, "models")
            if (!modelDir.exists()) modelDir.mkdirs()
            val modelList = assets.list("models") ?: return
            for (name in modelList) {
                val outFile = java.io.File(modelDir, name)
                if (!outFile.exists() || outFile.length() == 0L) {
                    assets.open("models/$name").use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Extracted asset model: $name (${outFile.length()} bytes)")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to extract assets: ${t.message}")
        }
    }

    private external fun nativeDestroyEngine()

    companion object {
        private const val TAG = "NotiCam/Activity"

        init {
            try {
                System.loadLibrary("auxcam_engine")
            } catch (t: Throwable) {
                Log.w(TAG, "native auxcam_engine unavailable: ${t.message}")
            }
        }

        /** Static on the Java side (`jclass`), so it must stay in the companion. */
        @JvmStatic
        private external fun nativeSetThermalThrottled(throttled: Boolean)

        @JvmStatic
        private external fun nativeSetFocusDistance(s1FocusM: Float)

        var DISABLE_GUIDED: Boolean = false
            set(value) {
                field = value
                try {
                    nativeSetDisableGuided(value)
                } catch (t: Throwable) {
                    Log.w(TAG, "nativeSetDisableGuided failed: ${t.message}")
                }
            }

        @JvmStatic
        private external fun nativeSetDisableGuided(disable: Boolean)

        @JvmStatic
        fun setEdgeRefineOverride(edgeRefine: Int) {
            try {
                nativeSetEdgeRefineOverride(edgeRefine)
            } catch (t: Throwable) {
                Log.w(TAG, "nativeSetEdgeRefineOverride failed: ${t.message}")
            }
        }

        @JvmStatic
        private external fun nativeSetEdgeRefineOverride(edgeRefine: Int)
    }
}
