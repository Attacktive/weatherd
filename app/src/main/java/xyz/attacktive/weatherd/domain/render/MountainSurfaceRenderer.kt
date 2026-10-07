package xyz.attacktive.weatherd.domain.render

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuffXfermode
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF

/** Active-scene immutable terrain sources; lighting changes only paints, never masks or material pixels. */
internal class MountainSurfaceRenderer(outlines: SceneryOutlines, surfaces: List<MountainSurface>, width: Int, height: Int) {
	private val rasterScale = minOf(0.25f, 1024f / maxOf(width, height))
	private val groups: Array<MountainMaterialGroup>

	init {
		val layers = Array(outlines.layers.size) { index ->
			val layer = outlines.layers[index]
			val contour = layer.outline + listOf(OutlinePoint(1f, 1f), OutlinePoint(0f, 1f))
			val path = terrainPath(contour, width, height)
			val surface = surfaces.first { it.layerIndex == index }
			val patches = Array(surface.patches.size) { patchIndex ->
				val patch = surface.patches[patchIndex]
				val feather = height * 0.014f
				val patchPath = terrainPath(patch.outline, width, height)
				val bounds = rasterBounds(patchPath, feather * 2f, width, height, rasterScale)
				val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
					color = Color.WHITE
					maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
				}

				val image = renderImmutableBitmap(bounds.width, bounds.height, Bitmap.Config.ALPHA_8) { canvas ->
					canvas.scale(rasterScale, rasterScale)
					canvas.translate(-bounds.destination.left, -bounds.destination.top)
					canvas.clipPath(path)
					canvas.drawPath(patchPath, brush)
				}

				MountainPatchRaster(patch, MountainRaster(image, bounds.destination))
			}

			val (highlight, shadow) = materialFields(path, layer.outline, layer.material, index, width, height, rasterScale)
			val edge = if (layer.plane == SceneryPlane.FAR) {
				boundaryField(path, layer.outline, null, width, height, rasterScale)
			} else {
				null
			}

			val forest = outlines.layers.firstOrNull { it.material == SceneryMaterial.FOREST }
			val contact = if (layer.material == SceneryMaterial.MEADOW && forest != null) {
				boundaryField(path, layer.outline, forest.outline, width, height, rasterScale)
			} else {
				null
			}

			MountainMaterialGroup(layer.material, layer.plane, path, patches, highlight, shadow, edge, contact)
		}

		val snow = outlines.glyphs.filter { it.material == SceneryMaterial.SNOW }.mapIndexed { index, glyph ->
			val parent = layers.first { it.material == SceneryMaterial.ROCK && it.plane == glyph.plane }
			val path = terrainPath(glyph.outline, width, height)
			val (highlight, shadow) = materialFields(path, glyph.outline, SceneryMaterial.SNOW, index, width, height, rasterScale)

			// Snow owns only its material field and paint; orientations, masks, and ridge-edge coverage are the parent's exact sources.
			MountainMaterialGroup(SceneryMaterial.SNOW, glyph.plane, path, parent.patches, highlight, shadow, parent.edge, null)
		}

		groups = layers + snow
	}

	fun updateLighting(lighting: SceneryLighting) {
		for (group in groups) {
			val base = surfaceColorFor(group.material, group.plane, 0.55f, lighting)
			group.basePaint.color = base
			group.drawDirectionalFaces = lighting.directStrength > 0f
			if (group.drawDirectionalFaces) {
				for (index in group.patches.indices) {
					val patch = group.patches[index].patch
					val diffuse = surfaceDiffuseFor(patch, lighting)
					val color = surfaceColorFor(group.material, group.plane, diffuse, lighting)
					group.patchPaints[index].color = color
				}
			}

			val materialContrast = when (group.material) {
				SceneryMaterial.ROCK -> 0.38f
				SceneryMaterial.SNOW -> 0.12f
				SceneryMaterial.FOREST -> 0.45f
				else -> 0.25f
			}

			val depthContrast = if (group.plane == SceneryPlane.FAR) { 0.65f } else { 1f }
			val textureAlpha = (255f * lighting.textureStrength * materialContrast * depthContrast).roundToInt()
			group.drawTexture = textureAlpha > 0
			group.textureBasePaint.color = base
			group.textureBasePaint.alpha = textureAlpha
			group.highlightPaint.color = base
			group.highlightPaint.alpha = textureAlpha
			group.shadowPaint.color = Color.BLACK
			group.shadowPaint.alpha = textureAlpha
			group.edgePaint.color = lighting.ambientColor
			group.edgePaint.alpha = 50
			group.contactPaint.color = blendSurfaceColor(base, Color.BLACK, 0.45f)
			group.contactPaint.alpha = (95f * lighting.textureStrength).roundToInt()
		}
	}

	fun drawPlane(canvas: Canvas, plane: SceneryPlane) {
		for (group in groups) {
			if (group.plane != plane) {
				continue
			}

			val checkpoint = canvas.save()
			canvas.clipPath(group.path)
			canvas.drawPath(group.path, group.basePaint)
			if (group.drawDirectionalFaces) {
				for (index in group.patches.indices) {
					val raster = group.patches[index].raster
					canvas.drawBitmap(raster.bitmap, null, raster.destination, group.patchPaints[index])
				}
			}

			if (group.drawTexture) {
				canvas.drawPath(group.path, group.textureBasePaint)
				canvas.drawBitmap(group.shadow.bitmap, null, group.shadow.destination, group.shadowPaint)
				canvas.drawBitmap(group.highlight.bitmap, null, group.highlight.destination, group.highlightPaint)
			}

			val edge = group.edge
			if (edge != null) {
				canvas.drawBitmap(edge.bitmap, null, edge.destination, group.edgePaint)
			}

			val contact = group.contact
			if (contact != null) {
				canvas.drawBitmap(contact.bitmap, null, contact.destination, group.contactPaint)
			}

			canvas.restoreToCount(checkpoint)
		}
	}
}

private class MountainMaterialGroup(val material: SceneryMaterial, val plane: SceneryPlane, val path: Path, val patches: Array<MountainPatchRaster>, val highlight: MountainRaster, val shadow: MountainRaster, val edge: MountainRaster?, val contact: MountainRaster?) {
	var drawDirectionalFaces = true
	var drawTexture = true
	val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
	val textureBasePaint = Paint(Paint.ANTI_ALIAS_FLAG)
	val patchPaints = Array(patches.size) { Paint(Paint.FILTER_BITMAP_FLAG) }
	val highlightPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
		xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
	}
	val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
	val edgePaint = Paint(Paint.FILTER_BITMAP_FLAG)
	val contactPaint = Paint(Paint.FILTER_BITMAP_FLAG)
}

private class MountainPatchRaster(val patch: MountainSurfacePatch, val raster: MountainRaster)

private class MountainRaster(val bitmap: Bitmap, val destination: RectF)

private class MountainRasterBounds(val width: Int, val height: Int, val destination: RectF)

private fun terrainPath(outline: List<OutlinePoint>, width: Int, height: Int) = Path().apply {
	moveTo(outline.first().x * width, outline.first().y * height)
	for (index in 1 until outline.size) {
		lineTo(outline[index].x * width, outline[index].y * height)
	}

	close()
}

/** Cropped raster dimensions use one scale on both axes; filtering cannot stretch relief after a viewport change. */
private fun rasterBounds(path: Path, padding: Float, width: Int, height: Int, scale: Float): MountainRasterBounds {
	val bounds = RectF()
	path.computeBounds(bounds, true)
	val left = floor((bounds.left - padding).coerceAtLeast(0f) * scale) / scale
	val top = floor((bounds.top - padding).coerceAtLeast(0f) * scale) / scale
	val right = (bounds.right + padding).coerceAtMost(width.toFloat())
	val bottom = (bounds.bottom + padding).coerceAtMost(height.toFloat())
	val rasterWidth = ceil((right - left) * scale).toInt().coerceAtLeast(1)
	val rasterHeight = ceil((bottom - top) * scale).toInt().coerceAtLeast(1)

	return MountainRasterBounds(rasterWidth, rasterHeight, RectF(left, top, left + rasterWidth / scale, top + rasterHeight / scale))
}

/** Stable low-frequency material fields, never regenerated for weather, phase, or animation time. */
private fun materialFields(path: Path, outline: List<OutlinePoint>, material: SceneryMaterial, index: Int, width: Int, height: Int, scale: Float): Pair<MountainRaster, MountainRaster> {
	val bounds = rasterBounds(path, 0f, width, height, scale)
	val totalPixels = bounds.width * bounds.height
	val highlightPixels = IntArray(totalPixels)
	val shadowPixels = IntArray(totalPixels)
	val seed = 7717 + material.ordinal * 1013 + index * 313
	for (x in 0 until bounds.width) {
		val physicalX = bounds.destination.left + (x + 0.5f) / scale
		val u = physicalX / height
		val slope = if (material == SceneryMaterial.ROCK) { outlineSlope(outline, physicalX / width, width.toFloat() / height) } else { 0f }
		for (y in 0 until bounds.height) {
			val v = (bounds.destination.top + (y + 0.5f) / scale) / height
			val broad = materialNoise(u * 13f, v * 13f, seed)
			val variation = materialVariation(material, u, v, slope, broad, seed)

			val delta = variation * 30f
			if (delta > 0f) {
				val alpha = (delta * (255f / 128f)).roundToInt().coerceIn(0, 255)
				highlightPixels[y * bounds.width + x] = Color.argb(alpha, 255, 255, 255)
			} else if (delta < 0f) {
				val alpha = (-delta * (255f / 128f)).roundToInt().coerceIn(0, 255)
				shadowPixels[y * bounds.width + x] = Color.argb(alpha, 255, 255, 255)
			}
		}
	}

	val highlight = publishField(highlightPixels, bounds, path, scale)
	val shadow = publishField(shadowPixels, bounds, path, scale)

	return highlight to shadow
}

private fun materialVariation(material: SceneryMaterial, u: Float, v: Float, slope: Float, broad: Float, seed: Int) = when (material) {
	SceneryMaterial.ROCK -> {
		val strata = sin((v + u * slope.coerceIn(-0.7f, 0.7f)) * 190f + broad * 3f)
		val crevice = materialNoise(u * 40f, v * 18f, seed + 1)

		broad * 0.48f + strata * 0.2f + crevice * 0.32f
	}

	SceneryMaterial.SNOW -> broad * 0.5f + materialNoise(u * 6f, v * 6f, seed + 2) * 0.5f
	SceneryMaterial.FOREST -> broad * 0.4f + materialNoise(u * 48f, v * 48f, seed + 3) * 0.6f
	else -> broad * 0.6f + materialNoise(u * 7f, v * 16f, seed + 4) * 0.4f
}

/** Ridge softening and receiver-only forest contact both fade inside the original terrain rather than haloing into the sky. */
private fun boundaryField(path: Path, edge: List<OutlinePoint>, occluder: List<OutlinePoint>?, width: Int, height: Int, scale: Float): MountainRaster {
	val bounds = rasterBounds(path, 0f, width, height, scale)
	val pixels = IntArray(bounds.width * bounds.height)
	for (x in 0 until bounds.width) {
		val physicalX = bounds.destination.left + (x + 0.5f) / scale
		val u = physicalX / width
		val crest = outlineHeight(edge, u) * height
		val reach = if (occluder == null) { height * 0.0045f } else { height * 0.011f }
		val strength = if (occluder == null) {
			1f
		} else {
			val separation = (outlineHeight(edge, u) - outlineHeight(occluder, u)).coerceAtLeast(0f)

			(separation / 0.08f).coerceIn(0.15f, 1f) * (0.7f + 0.3f * materialNoise(physicalX / height * 25f, 0f, 9173))
		}

		for (y in 0 until bounds.height) {
			val depth = bounds.destination.top + (y + 0.5f) / scale - crest
			val distance = depth.coerceAtLeast(0f) / reach
			val alpha = (255f * exp(-distance * distance) * strength).roundToInt().coerceIn(0, 255)
			pixels[y * bounds.width + x] = Color.argb(alpha, 255, 255, 255)
		}
	}

	return publishField(pixels, bounds, path, scale)
}

private fun publishField(pixels: IntArray, bounds: MountainRasterBounds, clip: Path, scale: Float): MountainRaster {
	val source = Bitmap.createBitmap(pixels, bounds.width, bounds.height, Bitmap.Config.ARGB_8888)

	return try {
		val bitmap = renderImmutableBitmap(bounds.width, bounds.height, Bitmap.Config.ALPHA_8) { canvas ->
			canvas.scale(scale, scale)
			canvas.translate(-bounds.destination.left, -bounds.destination.top)
			canvas.clipPath(clip)
			canvas.translate(bounds.destination.left, bounds.destination.top)
			canvas.scale(1f / scale, 1f / scale)
			canvas.drawBitmap(source, 0f, 0f, null)
		}

		MountainRaster(bitmap, bounds.destination)
	} finally {
		// Only this software staging source is recycled; the published bitmap never changes.
		source.recycle()
	}
}

private fun outlineHeight(outline: List<OutlinePoint>, x: Float): Float {
	for (index in 1 until outline.size) {
		val right = outline[index]
		if (right.x >= x) {
			val left = outline[index - 1]
			val fraction = ((x - left.x) / (right.x - left.x).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)

			return left.y + (right.y - left.y) * fraction
		}
	}

	return outline.last().y
}

private fun outlineSlope(outline: List<OutlinePoint>, x: Float, aspect: Float): Float {
	for (index in 1 until outline.size) {
		val right = outline[index]
		if (right.x >= x) {
			val left = outline[index - 1]

			return (right.y - left.y) / ((right.x - left.x) * aspect).coerceAtLeast(0.0001f)
		}
	}

	return 0f
}

/** Smooth seeded value noise evaluated only while publishing geometry-owned material images. */
private fun materialNoise(x: Float, y: Float, seed: Int): Float {
	val ix = floor(x).toInt()
	val iy = floor(y).toInt()
	val fx = x - ix
	val fy = y - iy
	val sx = fx * fx * (3f - 2f * fx)
	val sy = fy * fy * (3f - 2f * fy)
	val topLeft = materialHash(ix, iy, seed)
	val bottomLeft = materialHash(ix, iy + 1, seed)
	val top = topLeft + (materialHash(ix + 1, iy, seed) - topLeft) * sx
	val bottom = bottomLeft + (materialHash(ix + 1, iy + 1, seed) - bottomLeft) * sx

	return top + (bottom - top) * sy
}

private fun materialHash(x: Int, y: Int, seed: Int): Float {
	var value = x * 374761393 + y * 668265263 + seed * 1442695041
	value = (value xor (value ushr 13)) * 1274126177
	value = value xor (value ushr 16)

	return (value and 0xFFFF) / 32767.5f - 1f
}
