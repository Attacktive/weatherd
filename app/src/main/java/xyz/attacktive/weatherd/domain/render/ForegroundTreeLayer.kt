package xyz.attacktive.weatherd.domain.render

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.TreeSeason
import xyz.attacktive.weatherd.domain.model.TreeStyle
import xyz.attacktive.weatherd.domain.model.usesSpringArtwork

/** Fixed foreground composition using shared, immutable first-party tree sprites decoded outside the frame loop. */
internal class ForegroundTreeLayer(private val resources: Resources) {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val destinations = Array(EVERGREEN_PLACEMENTS.size) { RectF() }
	private var cachedWidth = 0f
	private var cachedHeight = 0f
	private var cachedStyle: TreeStyle? = null
	private val lighting = SceneryLighting(SceneryPlane.NEAR)

	fun isTextureReady(params: SceneParams) = !shouldDraw(params) || spritesFor(params.treeStyle, params.treeSeason) != null

	fun prewarm(params: SceneParams) {
		if (isTextureReady(params)) {
			return
		}

		synchronized(ForegroundTreeLayer::class.java) {
			if (isTextureReady(params)) {
				return
			}

			val style = params.treeStyle
			var variants = prepared[style].orEmpty()
			if (variants.isEmpty()) {
				variants = mapOf(TreeSeason.SPRING to decodeArtwork(style))
			}

			val originals = checkNotNull(variants[TreeSeason.SPRING])
			if (!params.treeSeason.usesSpringArtwork) {
				val tinted = Array(originals.size) { index -> recolor(originals[index], params.treeSeason) }
				variants = variants + (params.treeSeason to tinted)
			}

			prepared = prepared + (style to variants)
		}
	}

	private fun decodeArtwork(style: TreeStyle): Array<Bitmap> {
		val options = BitmapFactory.Options().apply { inScaled = false }

		return when (style) {
			TreeStyle.EVERGREEN -> arrayOf(
				checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.tree_evergreen_1, options)),
				checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.tree_evergreen_2, options)),
				checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.tree_evergreen_3, options))
			)
			TreeStyle.BROADLEAF -> arrayOf(
				checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.tree_broadleaf, options)),
				checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.tree_broadleaf_companion, options))
			)
		}
	}

	/** Recolors the selected foliage once in the background while retaining the source alpha and tree geometry. */
	private fun recolor(source: Bitmap, season: TreeSeason): Bitmap {
		val pixels = IntArray(source.width * source.height)
		source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
		for (index in pixels.indices) {
			pixels[index] = seasonalTreeFoliagePixel(pixels[index], season)
		}

		val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
		result.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)

		return result
	}

	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams) {
		if (!shouldDraw(params) || width <= 0f || height <= 0f) {
			return
		}

		// Keep the original foliage visible while the requested season is prepared off the frame thread.
		val style = params.treeStyle
		val sources = spritesFor(style, params.treeSeason)
			?: spritesFor(style, TreeSeason.SPRING)
			?: return
		val placements = placementsFor(style)
		updateDestinations(width, height, style, sources)

		lighting.applyTo(paint, params)

		for (index in placements.indices) {
			val placement = placements[index]
			val destination = destinations[index]
			if (placement.mirror) {
				val saved = canvas.save()
				canvas.scale(-1f, 1f, destination.centerX(), destination.centerY())
				canvas.drawBitmap(sources[placement.sprite], null, destination, paint)
				canvas.restoreToCount(saved)
			} else {
				canvas.drawBitmap(sources[placement.sprite], null, destination, paint)
			}
		}
	}

	private fun updateDestinations(width: Float, height: Float, style: TreeStyle, sources: Array<Bitmap>) {
		if (width == cachedWidth && height == cachedHeight && style == cachedStyle) {
			return
		}

		val placements = placementsFor(style)
		for (index in placements.indices) {
			val placement = placements[index]
			val source = sources[placement.sprite]
			val unclampedHeight = height * placement.height
			val unclampedWidth = unclampedHeight * source.width / source.height
			val treeWidth = unclampedWidth.coerceAtMost(width * placement.maxWidth)
			val treeHeight = treeWidth * source.height / source.width
			val center = width * placement.center
			val left = center - treeWidth / 2f
			val horizontalOffset = (width * placement.minLeft - left).coerceAtLeast(0f)
			val bottom = height * placement.bottom
			destinations[index].set(left + horizontalOffset, bottom - treeHeight, center + treeWidth / 2f + horizontalOffset, bottom)
		}

		cachedWidth = width
		cachedHeight = height
		cachedStyle = style
	}

	private fun spritesFor(style: TreeStyle, season: TreeSeason) = prepared[style]?.get(
		if (season.usesSpringArtwork) TreeSeason.SPRING else season
	)

	private fun shouldDraw(params: SceneParams) = params.treesEnabled && params.backdropScene != BackdropScene.TREESCAPE

	private fun placementsFor(style: TreeStyle) = when (style) {
		TreeStyle.EVERGREEN -> EVERGREEN_PLACEMENTS
		TreeStyle.BROADLEAF -> BROADLEAF_PLACEMENTS
	}

	private companion object {
		@Volatile
		var prepared: Map<TreeStyle, Map<TreeSeason, Array<Bitmap>>> = emptyMap()
	}
}

private data class TreePlacement(
	val sprite: Int,
	val center: Float,
	val height: Float,
	val bottom: Float,
	val mirror: Boolean = false,
	val maxWidth: Float = Float.POSITIVE_INFINITY,
	val minLeft: Float = Float.NEGATIVE_INFINITY
)

// Smaller trees draw first; the dominant tree and asymmetric right tree anchor the near foreground.
private val EVERGREEN_PLACEMENTS = arrayOf(
	TreePlacement(sprite = 1, center = 0.02f, height = 0.39f, bottom = 1.10f),
	TreePlacement(sprite = 2, center = 0.65f, height = 0.37f, bottom = 1.08f),
	TreePlacement(sprite = 2, center = 0.57f, height = 0.45f, bottom = 1.12f, mirror = true),
	TreePlacement(sprite = 0, center = 0.32f, height = 0.64f, bottom = 1.08f),
	TreePlacement(sprite = 1, center = 0.97f, height = 0.53f, bottom = 1.09f, mirror = true)
)

// Keep the full primary crown inset through launcher scrolling without reducing its scale.
private val BROADLEAF_PLACEMENTS = arrayOf(
	TreePlacement(sprite = 0, center = 0.25f, height = 0.43f, bottom = 1.02f, maxWidth = 0.58f, minLeft = 0.12f),
	TreePlacement(sprite = 1, center = 0.86f, height = 0.30f, bottom = 1.02f, maxWidth = 0.22f)
)

