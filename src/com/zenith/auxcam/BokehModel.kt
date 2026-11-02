package com.zenith.auxcam

/** Stable IDs, never preference ordinals. Only the two supported portrait choices are bundled at build time. */
enum class BokehModel(val id: String, val label: String, val detail: String) {
    MediaPipeAuto("mediapipe_auto", "Auto", "Person segmentation · NNAPI accelerator when accepted; explicit CPU fallback"),
    MODNet("modnet", "Portrait quality", "Person matting · finer soft edges · CPU · may be still-only on this device");

    companion object {
        // Removed experimental selections migrate safely; stored IDs never use ordinals.
        fun fromId(id: String?): BokehModel = if (id == "modnet") MODNet else MediaPipeAuto
    }
}

data class BokehCheckResult(
    val model: BokehModel,
    val passed: Boolean,
    val summary: String,
)
