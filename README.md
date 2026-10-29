# AuxCam Portrait Engine (Android)

A production-grade, zero-copy native Vulkan portrait bokeh camera engine engineered for
**one phone: the CMF Phone 2 Pro** (MediaTek Dimensity 7300 Pro, Mali-G615 MC2, 1080 × 2392
@ 120 Hz). The capture UI is **Jetpack Compose**; the AIDL/Shizuku/Vulkan engine is unchanged.

---

## ⚡ Overview

AuxCam unlocks synchronized multi-camera ISP streaming directly through a privileged Camera2 User Service (managed via Shizuku), piping hardware-synchronized color and disparity buffers directly into a custom Vulkan 1.3 compute pipeline with zero-copy `AHardwareBuffer` imports.

### 🌟 Key Capabilities
- **Synchronized Triple-Stream Camera Session**:
  - **Stream 0 (Preview Color)**: $1440 \times 1080$ `YUV_420_888` (`USAGE_GPU_SAMPLED_IMAGE`)
  - **Stream 1 (Hardware Disparity)**: $448 \times 288$ `Y8` stereo depth map (`USAGE_GPU_SAMPLED_IMAGE | USAGE_CPU_READ_OFTEN`)
  - **Stream 2 (Full-Res Still)**: $4080 \times 3072$ `YUV_420_888` 4K Still capture
- **Zero-Copy Vulkan 1.3 Compute Pipeline**:
  - `VkSamplerYcbcrConversion` with immutable sampler for direct YUV $\to$ RGB conversion in shader.
  - **Circle of Confusion (CoC)** Pass: Physically grounded thin-lens optical defocus modeling with near-field suppression.
  - **32-Tap Poisson-Disk Gather** Pass: Hardware-accelerated depth-aware bokeh gather filter.
  - **Composite & Presentation** Pass: Alpha blending and direct swapchain presentation (`vkQueuePresentKHR`) at $120\text{ Hz} / 30\text{ fps}$ display cadence.
- **Ultra-Low Latency Performance**:
  - Viewfinder steady-state $p_{50} = \mathbf{22\text{ ms}}$ ($\le 33.3\text{ ms}$ real-time $30\text{ fps}$ budget).
  - 4K Still Bokeh composite completion in $\mathbf{181\text{ ms}}$ (well within $\le 500\text{ ms}$ UX shutter gate).

---

## 🎛️ Capture UI (Apple Camera language, Jetpack Compose)

The viewfinder chrome is Compose end to end: tokens, components and the full
interaction map live in [`design/DESIGN.md`](design/DESIGN.md); a clickable mock of
the shipping layout lives in [`design/index.html`](design/index.html).

| | Before | After |
|---|---|---|
| **Stack** | `Activity` + `FrameLayout` + hand-written `View` subclasses | `ComponentActivity` → `CameraController` → `CameraScreen`; one immutable `CameraUiState` |
| **Theme** | Nothing OS: full-width `#D00B0B0E` bars, acrylic cards, red accents | iOS dark: floating glass controls, one accent (`#FFD60A`) |
| **Layout** | Absolute chrome painted over the preview | `Column`: top bar → `weight(1f)` frame → chips → ƒ row → bottom bar. **Nothing can overlap the 4:3 framebuffer** |
| **Preview geometry** | 4:3 landscape view (sideways), swapchain frozen at first size → stretched image | `composite.comp` rotates the sensor frame 90° CW → 3:4 portrait, upright; frame measured to `previewAspect`, swapchain recreated on extent change, aspect-fit as a second guarantee |
| **Motion** | `ValueAnimator` + interpolators, several controls clipped mid-animation | One `CameraMotion` vocabulary: crisp press / springy release, slide-in Pro menu, travelling zoom-chip indicator, arc zoom wheel with detents, `clip = false` everywhere it can matter |
| **Haptics** | Single `VIRTUAL_KEY` blip, then full-strength vibrator effects | `CameraHaptics`: soft `performHapticFeedback` vocabulary (segment ticks, context clicks, confirm) with an amplitude-limited one-shot only as a last resort |
| **Icons** | 24 vector drawables + PNG-tinted bitmaps | Nine hand-built `ImageVector`s (24-unit grid, 1.7 stroke) — resolution independent, can never be resampled or cropped |
| **Shutter** | Static layered drawable | `ShutterButton`: ring/gap/disc, spring compression, capture pulse drawn *inside* its own 96 dp box |
| **Flash / Zoom / Aperture** | Inert badges | Flash cycles `CONTROL_AE_MODE`; RAW sits beside the flash; 0.6×/1×/2× chips + pinch (the only zoom input) drive `SCALER_CROP_REGION`; continuous ƒ slider drives the CoC pass |
| **Focus** | Static yellow square | Spring-in AE/AF reticle + hardware focus scan at the tap point |
| **Settings** | Chevron drawer with dead buttons | Sheet sliding down from the top bar: continuous ƒ and ±4 EV sliders, Grid / Timer, live telemetry (no title, no Done — tap outside) |
| **Gallery** | Always-empty tile | Real bokeh frame decoded from the capture, plus a shutter→gallery flight |

Highlights of the implementation:

- **`src/com/zenith/auxcam/MainActivity.kt`** — `ComponentActivity`, edge-to-edge window flags, the four JNI entry points, `setContent { NotiCamTheme { CameraScreen(...) } }`.
- **`CameraController.kt`** — owns the engine, the Shizuku binding, the `ImageReader`s and the single source of truth (`CameraUiState`); implements the `CameraActions` interface the composables call.
- **`ui/theme/Theme.kt`** — `CameraColors`, `CameraSpacing`, `CameraType`.
- **`ui/components/`** — `ShutterButton`, `Viewfinder`, `Controls` (top bar, mode carousel, sliders, Pro sheet, gallery tile, bottom bar), `ZoomChips` (travelling indicator), `ZoomDial` (arc wheel).
- **`ui/feedback/Haptics.kt`** — the soft haptic vocabulary.
- **`ui/effects/MatteGlass.kt`** — frosted-glass sheen + GPU-tiled grain.
- **`ui/theme/Theme.kt` → `CameraMotion`** — every spring and tween in the app.
- **`ui/icons/NotiIcons.kt`** — the icon set.
- **`DeviceProfile.kt`** — every CMF Phone 2 Pro constant (display, sensor streams, lens ladder, camera id).
- **`CameraUserService`** — AIDL surface (`setFlashMode`, `setEvCompensation`, `setZoom`, `focusAt`, `setPhysicalLens`) mapping 1:1 onto Camera2 controls.
- **`tools/verify_ui.py`** — resource graph, brace/string balance, project-import resolution, Compose member cross-checks, composable imports; runs in `build.sh` and in Gradle's `check`.

---

## 🏗️ Architecture

```
Camera HAL (Stream 0 & Stream 1)
   │
   ├── Stream 0 (1440x1080 YUV AHB) ──► VkSamplerYcbcrConversion (Zero-Copy)
   └── Stream 1 (448x288 Y8 AHB)    ──► Host-Visible Staging Buffer
                                                │
                                                ▼
                                    ┌──────────────────────┐
                                    │   Vulkan 1.3 Graph   │
                                    │ 1. CoC Pass (Compute)│
                                    │ 2. 32-tap Gather     │
                                    │ 3. Viewport Composite│
                                    └──────────┬───────────┘
                                               │
                                               ▼
                                      Swapchain Image
                                               │
                                               ▼
                                      SurfaceView (Screen)
```

---

## 📦 Project Structure

```
auxcam-app/
├── settings.gradle.kts        # Gradle + AGP 8.7.3 + Kotlin 2.0.21 + Compose
├── build.gradle.kts           # app module: compileSdk 35, minSdk 28, arm64-v8a only
├── AndroidManifest.xml        # Camera & Shizuku permissions, Vulkan 1.3 feature gate
├── build.sh                   # Fallback shell build (NDK + AAPT2 + D8 + Apksigner)
├── src/                       # Kotlin (Compose UI) + Java (service) + AIDL
│   └── com/zenith/auxcam/
│       ├── MainActivity.kt            # Compose host + JNI entry points
│       ├── CameraScreen.kt            # the column: chrome outside the 4:3 frame
│       ├── CameraController.kt        # engine + state owner (CameraActions)
│       ├── CameraUiState.kt           # @Immutable snapshot
│       ├── DeviceProfile.kt           # CMF Phone 2 Pro constants
│       ├── ui/theme/Theme.kt          # CameraColors / CameraSpacing / CameraType
│       ├── ui/icons/NotiIcons.kt      # nine ImageVectors
│       ├── ui/components/             # ShutterButton, Viewfinder, Controls, ZoomDial
│       ├── CameraUserService.java     # privileged Camera2 user service
│       ├── IAuxCamService.aidl
│       └── IAuxCamCallback.aidl
├── jni/                       # C++ native Vulkan bokeh engine
│   ├── Android.mk
│   ├── Application.mk
│   ├── src/
│   │   ├── VulkanPipeline.cpp
│   │   ├── YuvConverter.cpp
│   │   ├── NativeBokehBridge.cpp
│   │   └── NpuWorker.cpp
│   └── shaders/               # GLSL compute shaders
│       ├── coc.comp
│       ├── gather.comp
│       ├── composite.comp
│       └── convert.comp
├── res/                       # window theme only — everything visual is Compose
│   └── values/                # themes.xml (black, edge-to-edge), strings.xml
├── design/                    # DESIGN.md + clickable UI mock (index.html)
├── tools/verify_ui.py         # resource graph + Kotlin/Compose sanity gate
├── assets/                    # Precompiled SPIR-V shaders & assets
├── libs/                      # JAR dependencies (Shizuku, TensorFlow Lite)
├── lib/                       # Native compiled shared libraries (arm64-v8a)
├── tests/                     # CoC unit test and hazard verification
└── release/                   # Prebuilt, signed production APK
    └── auxcam_aligned.apk
```

---

## 🛠️ Building & Installing

### Prerequisites
- Android SDK (`build-tools 34.0.0+`, `platforms/android-35`)
- Android NDK (`r27b+`) — the Vulkan engine is built by `jni/Android.mk`
- JDK 17+
- `glslc` (Vulkan shader compiler) — `compileShaders` runs before `preBuild`

### Build Command
```bash
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk

./gradlew assembleDebug        # or: ./gradlew installDebug
# shaders only:  ./gradlew compileShaders     (-PskipShaders to bypass)
# verify UI:     python3 tools/verify_ui.py
```

The legacy `./build.sh` (NDK + AAPT2 + D8 + Apksigner, no Gradle) still works and
runs `tools/verify_ui.py` first.

### Installation
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 Device Setup & Permissions
1. Ensure **Shizuku** is running and authorized on the device.
2. Launch **NotiCam**; grant the Shizuku permission prompt.
3. The app connects to the privileged `CameraUserService` running under the shell UID to access physical stereo Camera ID `5` on the CMF Phone 2 Pro (the only device this build is tuned for; anything else gets a visible warning in the top bar).
4. Live viewfinder immediately begins streaming real-time portrait bokeh.
