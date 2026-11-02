package com.zenith.auxcam

/** Stable IDs, never preference ordinals. Only the requested model is exposed; no substitute weights are bundled. */
enum class BokehModel(val id: String, val label: String, val detail: String) {
    MobileNetV4Int8("mobilenetv4_conv_medium_int8", "MobileNetV4 · INT8", "512 × 512 · LRASPP/matting · APU-only inference · awaiting trained export and verified runtime");

    companion object {
        fun fromId(id: String?): BokehModel = MobileNetV4Int8
    }
}

data class BokehCheckResult(
    val model: BokehModel,
    val passed: Boolean,
    val summary: String,
)
