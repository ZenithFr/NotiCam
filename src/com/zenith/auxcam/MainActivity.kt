package com.zenith.auxcam

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

        controller = CameraController(this, this)

        setContent {
            NotiCamTheme {
                val state by controller.uiState
                CameraScreen(state = state, actions = controller)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.start()
    }

    override fun onDestroy() {
        controller.dispose()
        super.onDestroy()
    }

    // ══════════════════════ NativeBridge → JNI ═══════════════════════════════

    override fun initEngine(surface: Surface, width: Int, height: Int): Boolean =
        nativeInitEngine(surface, width, height)

    override fun renderFrame(
        buffer: HardwareBuffer,
        depth: ByteArray?,
        depthW: Int,
        depthH: Int,
        fStop: Float,
    ): Boolean = nativeRenderFrame(buffer, depth, depthW, depthH, fStop)

    override fun processStill(
        buffer: HardwareBuffer,
        depth: ByteArray?,
        depthW: Int,
        depthH: Int,
        fStop: Float,
        targetFarFieldPx: Float,
        outPath: String,
        timestampsOut: LongArray,
    ): Boolean = nativeProcessStillBokeh(
        buffer,
        depth,
        depthW,
        depthH,
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

    override fun destroyEngine() {
        try {
            nativeDestroyEngine()
        } catch (t: UnsatisfiedLinkError) {
            Log.w(TAG, "destroy bridge unavailable: ${t.message}")
        }
    }

    private external fun nativeInitEngine(surface: Surface, width: Int, height: Int): Boolean

    private external fun nativeRenderFrame(
        buffer: HardwareBuffer,
        depth: ByteArray?,
        depthW: Int,
        depthH: Int,
        fStop: Float,
    ): Boolean

    private external fun nativeProcessStillBokeh(
        buffer: HardwareBuffer,
        depth: ByteArray?,
        depthW: Int,
        depthH: Int,
        fStop: Float,
        targetFarFieldPx: Float,
        outPath: String,
        timestampsOut: LongArray,
    ): Boolean

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
    }
}
