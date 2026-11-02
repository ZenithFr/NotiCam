package com.zenith.auxcam.ui.components

/** Search only implemented controls. Multi-word queries require every term. */
internal enum class ControlSection(val label: String, val terms: String) {
    Capture("Capture", "capture photo video flash format raw dng sequence burst timer jpeg resolution quality"),
    Portrait("Portrait", "portrait depth aperture bokeh models checks mobilenetv4 int8 apu matting"),
    Aids("Aids", "aids grid guide thirds golden diagonal square viewfinder preview fps"),
    System("System", "system shizuku backend camera report diagnostics telemetry permissions");
    fun matches(query: String) = query.isBlank() || query.trim().split(Regex("""\s+""")).all { terms.contains(it, ignoreCase = true) }
}
