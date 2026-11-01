package com.zenith.auxcam

/** Stable IDs, never preference ordinals. All model files are bundled at build time. */
enum class BokehModel(val id: String, val label: String, val detail: String) {
    MediaPipeAuto("mediapipe_auto", "MediaPipe Auto", "Current baseline · dual, with visible thermal/FPS Landscape fallback"),
    MediaPipeDual("mediapipe_dual", "MediaPipe Dual", "General + Landscape · fixed pair · NNAPI requested"),
    MediaPipeLandscape("mediapipe_landscape", "MediaPipe Landscape", "256 × 144 · lightweight · TFLite CPU"),
    MediaPipeGeneral("mediapipe_general", "MediaPipe General", "256 × 256 · TFLite CPU"),
    MODNet("modnet", "MODNet", "384 × 512 portrait matting · ONNX CPU · Apache 2.0"),
    RVM("rvm_mobilenetv3", "RVM MobileNetV3", "384 × 512 · recurrent · ONNX CPU · GPL-3.0-only");

    companion object {
        fun fromId(id: String?): BokehModel = entries.firstOrNull { it.id == id } ?: MediaPipeAuto
    }
}

data class BokehCheckResult(
    val model: BokehModel,
    val passed: Boolean,
    val summary: String,
)
