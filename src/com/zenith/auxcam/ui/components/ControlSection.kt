package com.zenith.auxcam.ui.components

/** Search only implemented controls. Multi-word queries require every term. */
internal enum class ControlSection(val label: String, val terms: String) {
    Capture("Capture", "capture photo video flash format raw dng sequence burst timer jpeg resolution quality"),
    Manual("Manual", "manual exposure shutter iso white balance wb focus lock auto sensor"),
    Portrait("Portrait", "portrait depth aperture bokeh models checks auto quality mediapipe modnet"),
    Aids("Aids", "aids grid guide thirds golden diagonal square viewfinder preview fps"),
    System("System", "system shizuku backend camera report diagnostics telemetry permissions");
    fun matches(query: String) = query.isBlank() || query.trim().split(Regex("""\s+""")).all { terms.contains(it, ignoreCase = true) }
}
