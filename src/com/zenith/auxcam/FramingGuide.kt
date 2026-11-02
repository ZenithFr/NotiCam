package com.zenith.auxcam

/** Independent viewfinder-only geometry. Never a camera crop or saved-image effect. */
enum class FramingGuide(val id: String, val label: String) {
    Thirds("thirds", "THIRDS"), Golden("golden", "GOLDEN"), Cross("cross", "CROSS"),
    Diagonal("diagonal", "DIAGONAL"), Square("square", "SQUARE");

    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    fun lines(width: Float, height: Float): List<Line> {
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return emptyList()
        fun vertical(x: Float) = Line(x, 0f, x, height)
        fun horizontal(y: Float) = Line(0f, y, width, y)
        return when (this) {
            Thirds -> listOf(vertical(width / 3f), vertical(width * 2f / 3f), horizontal(height / 3f), horizontal(height * 2f / 3f))
            Golden -> listOf(vertical(width * .381966f), vertical(width * .618034f), horizontal(height * .381966f), horizontal(height * .618034f))
            Cross -> listOf(vertical(width / 2f), horizontal(height / 2f))
            Diagonal -> listOf(Line(0f, 0f, width, height), Line(width, 0f, 0f, height))
            Square -> {
                val side = minOf(width, height) * .75f
                val x = (width - side) / 2f; val y = (height - side) / 2f
                listOf(Line(x,y,x+side,y), Line(x+side,y,x+side,y+side), Line(x+side,y+side,x,y+side), Line(x,y+side,x,y))
            }
        }
    }
    fun next(): FramingGuide = entries[(ordinal + 1) % entries.size]
    companion object {
        fun fromId(id: String?): FramingGuide = entries.firstOrNull { it.id == id } ?: Thirds
    }
}
