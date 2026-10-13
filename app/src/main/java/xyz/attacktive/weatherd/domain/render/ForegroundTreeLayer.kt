package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.RectF
import xyz.attacktive.weatherd.R

/** Fixed foreground composition using shared, immutable first-party evergreen sprites decoded outside the frame loop. */
internal class ForegroundTreeLayer(private val resources: Resources) {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val destinations = Array(TREE_PLACEMENTS.size) { RectF() }
	private var cachedWidth = 0f
	private var cachedHeight = 0f
	private var previousParams: SceneParams? = null
	private var previousTint = 0

	val texturesReady: Boolean
		get() = sprites != null

	fun prewarm() {
		if (texturesReady) {
			return
		}

		synchronized(ForegroundTreeLayer::class.java) {
			if (sprites == null) {
				val options = BitmapFactory.Options().apply { inScaled = false }
				sprites = arrayOf(
					checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.tree_evergreen_1, options)),
					checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.tree_evergreen_2, options)),
					checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.tree_evergreen_3, options))
				)
			}
		}
	}

	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams) {
		if (!params.treesEnabled || width <= 0f || height <= 0f) {
			return
		}

		val sources = sprites ?: return
		if (width != cachedWidth || height != cachedHeight) {
			for (index in TREE_PLACEMENTS.indices) {
				val placement = TREE_PLACEMENTS[index]
				val source = sources[placement.sprite]
				val treeHeight = height * placement.height
				val treeWidth = treeHeight * source.width / source.height
				val center = width * placement.center
				val bottom = height * placement.bottom
				destinations[index].set(center - treeWidth / 2f, bottom - treeHeight, center + treeWidth / 2f, bottom)
			}

			cachedWidth = width
			cachedHeight = height
		}

		if (params !== previousParams) {
			val tint = treeMultiplyTint(params)
			if (tint != previousTint) {
				paint.colorFilter = LightingColorFilter(tint, 0)
				previousTint = tint
			}

			previousParams = params
		}

		for (index in TREE_PLACEMENTS.indices) {
			val placement = TREE_PLACEMENTS[index]
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

	private companion object {
		@Volatile
		var sprites: Array<Bitmap>? = null
	}
}

private data class TreePlacement(val sprite: Int, val center: Float, val height: Float, val bottom: Float, val mirror: Boolean = false)

// Smaller trees draw first; the dominant tree and asymmetric right tree anchor the near foreground.
private val TREE_PLACEMENTS = arrayOf(
	TreePlacement(sprite = 1, center = 0.02f, height = 0.39f, bottom = 1.10f),
	TreePlacement(sprite = 2, center = 0.65f, height = 0.37f, bottom = 1.08f),
	TreePlacement(sprite = 2, center = 0.57f, height = 0.45f, bottom = 1.12f, mirror = true),
	TreePlacement(sprite = 0, center = 0.32f, height = 0.64f, bottom = 1.08f),
	TreePlacement(sprite = 1, center = 0.97f, height = 0.53f, bottom = 1.09f, mirror = true)
)

/** Reuses the continuous phase tint; cloudy/storm light dims foliage without making the source alpha translucent. */
private fun treeMultiplyTint(params: SceneParams): Int {
	val phaseTint = cumulusTint(params.dayPhase, params.celestialProgress)
	val warmth = twilightWarmthStrength(params.dayPhase, params.celestialProgress) * 0.28f
	val storm = if (params.thunder) {
		0.20f
	} else {
		(params.precipitation?.severity ?: 0f) * 0.12f
	}

	val brightness = 1f - effectiveOpaqueCloudiness(params) * 0.24f - storm

	return Color.rgb(
		(Color.red(phaseTint) * brightness).roundToInt(),
		(Color.green(phaseTint) * brightness * (1f - warmth * 0.12f)).roundToInt(),
		(Color.blue(phaseTint) * brightness * (1f - warmth * 0.32f)).roundToInt()
	)
}
