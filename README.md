# AuxCam Portrait Engine (Android)

A production-grade, zero-copy native Vulkan portrait bokeh camera engine engineered for the MediaTek Dimensity 7350 Pro (`GalagaIND` / `A001`, Mali-G615 MC2, APU 655).

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

## 🎛️ Capture UI (Apple Camera language)

The viewfinder chrome was rebuilt from the ground up around the iOS Camera /
Portrait UI. Tokens, components and the full interaction map live in
[`design/DESIGN.md`](design/DESIGN.md); a clickable mock of the shipping layout
lives in [`design/index.html`](design/index.html).

| | Before | After |
|---|---|---|
| **Theme** | Nothing OS: full-width `#D00B0B0E` bars, acrylic cards, red accents | iOS dark: floating glass controls, one accent (`#FFD60A`) |
| **Viewfinder** | Pushed down by an 88 dp header, boxed in | Floats to the top edge; chrome overlays the scene |
| **Modes** | `PHOTO · 50M PRO · VIDEO · PORTRAIT` white/grey text | `PHOTO · PORTRAIT · PRO` with a systemYellow selection |
| **Shutter** | Static layered drawable | Custom `ShutterButton`: compresses on press, ring pulse + haptic |
| **Flash** | Painted but inert | Cycles off → auto → on and sets `CONTROL_AE_MODE` on the still request |
| **Zoom** | Dead pill badges | Lens chips + pinch + orbit dial → real `SCALER_CROP_REGION` |
| **Aperture** | — | 22-stop ƒ ladder (Portrait) driving the physical CoC of the bokeh engine |
| **Focus** | Static yellow square | Spring-in AE/AF reticle with a hardware focus scan at the tap |
| **Pro controls** | Chevron drawer with dead EV/ISO/shutter buttons | Glass sheet: ƒ, EV (±4 EV), Grid, RAW, Timer, live telemetry |
| **Gallery** | Always-empty tile | Real bokeh frame decoded from the capture, plus a fly-to-gallery animation |

Highlights of the implementation:

- **`res/values/`** — `colors.xml`, `dimens.xml`, `styles.xml` design tokens (8-pt grid, iOS type scale).
- **`res/drawable/ios_*`** — glass capsules/circles, ripples, iOS slider track & knob, toolbar scrim.
- **`ShutterButton`** — ring/gap/disc anatomy with press compression and capture pulse.
- **`FocusOverlayView`** — rule-of-thirds grid + spring-in focus reticle.
- **`CameraUserService`** — new AIDL surface (`setFlashMode`, `setEvCompensation`, `setZoom`, `focusAt`, `setPhysicalLens`) mapping 1:1 onto Camera2 controls.
- **`tools/verify_ui.py`** — resource-graph + Java syntax gate, now part of `build.sh`.

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
├── AndroidManifest.xml        # Application manifest with Camera & Shizuku permissions
├── build.sh                   # Standalone shell build script (NDK + AAPT2 + D8 + Apksigner)
├── src/                       # Java / AIDL source files
│   └── com/zenith/auxcam/
│       ├── MainActivity.java
│       ├── CameraUserService.java
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
├── res/                       # Apple Camera design system (layout, drawables, tokens)
│   ├── layout/activity_main.xml
│   ├── drawable/ios_*.xml     # glass capsules, ripples, iOS slider, icons
│   └── values/                # colors.xml, dimens.xml, styles.xml, strings.xml
├── design/                    # DESIGN.md + clickable UI mock (index.html)
├── tools/verify_ui.py         # UI resource-graph & Java syntax gate
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
- Linux / macOS with `bash`
- Android SDK (`build-tools 34.0.0+`, `platforms/android-35`)
- Android NDK (`r27b+`)
- Java Development Kit (`JDK 17+`)
- `glslc` (Vulkan shader compiler)

### Build Command
```bash
# Optional: Set environment variables if using custom SDK/NDK locations
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk

# Run build
./build.sh
```

### Installation
```bash
# Install to connected device via ADB
adb install -r release/auxcam_aligned.apk
```

---

## 📱 Device Setup & Permissions
1. Ensure **Shizuku** is running and authorized on the device.
2. Launch **AuxCam**; grant the Shizuku permission prompt.
3. The app connects to the privileged `CameraUserService` running under the shell UID to access physical stereo Camera ID `5`.
4. Live viewfinder immediately begins streaming real-time portrait bokeh.
