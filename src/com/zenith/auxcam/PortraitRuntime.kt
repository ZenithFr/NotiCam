package com.zenith.auxcam

/** Fail closed, rather than mislabel CPU/GPU or partially delegated inference
 * as APU-only. An NNAPI accelerator name alone cannot satisfy this requirement.
 *
 * Integration requires: trained segmentation/matting weights (not ImageNet
 * classification), redistribution license, full INT8 calibration/quantization
 * metadata, 512² input geometry, soft alpha output/dequantization contract, and
 * a MediaTek runtime with whole-graph APU placement verified on the target phone.
 */
internal object PortraitRuntime {
    const val INPUT_SIZE = 512
    const val MODEL_ID = "mobilenetv4_conv_medium_int8"
    const val blockedReason = "Portrait unavailable: trained INT8 MobileNetV4-Conv-Medium LRASPP/matting weights and verified APU-only runtime are not installed. No CPU/GPU fallback."
    val available: Boolean get() = false
    fun requireAvailable() { if (!available) throw PortraitUnavailableException(blockedReason) }
}
internal class PortraitUnavailableException(message: String) : IllegalStateException(message)
