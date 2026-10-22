package xyz.attacktive.weatherd.domain.render

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.TreeStyle

/** Fixed foreground composition using shared, immutable first-party tree sprites decoded outside the frame loop. */
internal class ForegroundTreeLayer(private val resources: Resources) {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val destinations = Array(EVERGREEN_PLACEMENTS.size) { RectF() }
	private var cachedWidth = 0f
	private var cachedHeight = 0f
	private var cachedStyle: TreeStyle? = null
	private val lighting = SceneryLighting(SceneryPlane.NEAR)

	fun isTextureReady(style: TreeStyle) = spritesFor(style) != null

	fun prewarm(style: TreeStyle) {
		if (isTextureReady(style)) {
			return
		}

		synchronized(ForegroundTreeLayer::class.java) {
			if (!isTextureReady(style)) {
				val options = BitmapFactory.Options().apply { inScaled = false }

				when (style) {
					TreeStyle.EVERGREEN -> evergreenSprites = arrayOf(
						checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.tree_evergreen_1, options)),
						checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.tree_evergreen_2, options)),
						checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.tree_evergreen_3, options))
					)
					TreeStyle.BROADLEAF -> broadleafSprites = arrayOf(
						checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.tree_broadleaf, options)),
						checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.tree_broadleaf_companion, options))
					)
				}
			}
		}
	}

	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams) {
		if (!params.treesEnabled || width <= 0f || height <= 0f) {
			return
		}

		val sources = spritesFor(params.treeStyle) ?: return
		val placements = placementsFor(params.treeStyle)
		if (width != cachedWidth || height != cachedHeight || params.treeStyle != cachedStyle) {
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
			cachedStyle = params.treeStyle
		}

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

	private fun spritesFor(style: TreeStyle) = when (style) {
		TreeStyle.EVERGREEN -> evergreenSprites
		TreeStyle.BROADLEAF -> broadleafSprites
	}

	private fun placementsFor(style: TreeStyle) = when (style) {
		TreeStyle.EVERGREEN -> EVERGREEN_PLACEMENTS
		TreeStyle.BROADLEAF -> BROADLEAF_PLACEMENTS
	}

	private companion object {
		@Volatile
		var evergreenSprites: Array<Bitmap>? = null

		@Volatile
		var broadleafSprites: Array<Bitmap>? = null
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
