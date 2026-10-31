package xyz.attacktive.weatherd.domain.render

import xyz.attacktive.weatherd.domain.model.TreeSeason
import xyz.attacktive.weatherd.domain.model.usesSpringArtwork

/**
 * Recolors foliage pixels without changing alpha, trunk color, or leaf texture.
 * The native evergreen and broadleaf bitmaps remain immutable; this runs only while textures prewarm off the render thread.
 */
internal fun seasonalTreeFoliagePixel(pixel: Int, season: TreeSeason): Int {
	if (season.usesSpringArtwork || pixel ushr 24 == 0) {
		return pixel
	}

	val red = (pixel ushr 16) and 0xff
	val green = (pixel ushr 8) and 0xff
	val blue = pixel and 0xff
	if (green * 100 < red * 105 || green * 100 < blue * 110) {
		return pixel
	}

	return when (season) {
		TreeSeason.AUTO, TreeSeason.SPRING -> pixel
		TreeSeason.SUMMER -> seasonalTreeRgb(pixel, red * 0.92f, green * 1.16f + 4f, blue * 0.90f)
		TreeSeason.AUTUMN -> seasonalTreeRgb(pixel, red * 0.36f + green * 1.15f, green * 0.64f, blue * 0.57f)
		TreeSeason.WINTER -> seasonalTreeRgb(pixel, green * 1.32f + 35f, green * 1.39f + 40f, green * 1.51f + 48f)
	}
}

private fun seasonalTreeRgb(original: Int, red: Float, green: Float, blue: Float) =
	(original and (0xff shl 24)) or
		(red.toInt().coerceIn(0, 255) shl 16) or
		(green.toInt().coerceIn(0, 255) shl 8) or
		blue.toInt().coerceIn(0, 255)
