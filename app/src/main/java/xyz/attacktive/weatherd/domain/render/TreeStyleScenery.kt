package xyz.attacktive.weatherd.domain.render

import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.TreeStyle

/** Applies the user's tree morphology to scenery that already owns an individual tree population. */
fun styledSceneryOutlinesFor(scene: BackdropScene, aspectRatio: Float, treeStyle: TreeStyle): SceneryOutlines? {
	val outlines = sceneryOutlinesFor(scene, aspectRatio) ?: return null
	if (scene != BackdropScene.METROPOLIS || treeStyle == TreeStyle.ROUNDED) {
		return outlines
	}

	return outlines.copy(
		glyphs = outlines.glyphs.map { glyph ->
			if (glyph.material == SceneryMaterial.FOREST && glyph.plane == SceneryPlane.NEAR) {
				glyph.copy(outline = coniferOutline(glyph.outline))
			} else {
				glyph
			}
		}
	)
}

/** Reuses one rounded crown's exact bounding box and ground line while replacing only its silhouette. */
private fun coniferOutline(rounded: List<OutlinePoint>): List<OutlinePoint> {
	val minX = rounded.minOf { it.x }
	val maxX = rounded.maxOf { it.x }
	val topY = rounded.minOf { it.y }
	val groundY = rounded.maxOf { it.y }
	val centerX = (minX + maxX) * 0.5f
	val halfWidth = (maxX - minX) * 0.5f
	val height = groundY - topY

	return listOf(
		OutlinePoint(centerX - halfWidth * 0.30f, groundY),
		OutlinePoint(centerX - halfWidth, topY + height * 0.78f),
		OutlinePoint(centerX - halfWidth * 0.48f, topY + height * 0.70f),
		OutlinePoint(centerX - halfWidth * 0.82f, topY + height * 0.55f),
		OutlinePoint(centerX - halfWidth * 0.38f, topY + height * 0.47f),
		OutlinePoint(centerX - halfWidth * 0.58f, topY + height * 0.32f),
		OutlinePoint(centerX, topY),
		OutlinePoint(centerX + halfWidth * 0.58f, topY + height * 0.32f),
		OutlinePoint(centerX + halfWidth * 0.38f, topY + height * 0.47f),
		OutlinePoint(centerX + halfWidth * 0.82f, topY + height * 0.55f),
		OutlinePoint(centerX + halfWidth * 0.48f, topY + height * 0.70f),
		OutlinePoint(centerX + halfWidth, topY + height * 0.78f),
		OutlinePoint(centerX + halfWidth * 0.30f, groundY)
	)
}
