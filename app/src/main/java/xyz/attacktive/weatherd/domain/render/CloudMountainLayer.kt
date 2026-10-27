package xyz.attacktive.weatherd.domain.render

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.BackdropScene

/**
 * The submitted cloud-mountain artwork anchors the horizon under the live sky and weather.
 * It is decoded once off the frame loop, keeps its original proportions, and takes shared environmental lighting without re-rasterizing.
 */
internal class CloudMountainLayer(private val resources: Resources) {
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val destination = RectF()
	private var cachedWidth = 0f
	private var cachedHeight = 0f
	private val lighting = SceneryLighting(SceneryPlane.FAR)

	val textureReady: Boolean
		get() = source != null

	/** Prepares the selected scenery on a background dispatcher; drawing never decodes. */
	fun prewarm() {
		if (textureReady) {
			return
		}

		synchronized(CloudMountainLayer::class.java) {
			if (source == null) {
				source = checkNotNull(
					BitmapFactory.decodeResource(
						resources,
						R.drawable.cloud_mountains,
						BitmapFactory.Options().apply { inScaled = false }
					)
				)
			}
		}
	}

	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams) {
		if (params.backdropScene != BackdropScene.CLOUD_MOUNTAINS || width <= 0f || height <= 0f) {
			return
		}

		val artwork = source ?: return
		if (width != cachedWidth || height != cachedHeight) {
			val artworkHeight = cloudMountainArtworkHeight(width, artwork.width, artwork.height)
			val top = cloudMountainArtworkTop(height, artworkHeight)
			destination.set(0f, top, width, top + artworkHeight)
			cachedWidth = width
			cachedHeight = height
		}

		lighting.applyTo(paint, params)

		canvas.drawBitmap(artwork, null, destination, paint)
	}

	private companion object {
		@Volatile
		var source: Bitmap? = null
	}
}

/** Scale by viewport width to preserve the original image proportions and full horizontal composition. */
internal fun cloudMountainArtworkHeight(viewWidth: Float, sourceWidth: Int, sourceHeight: Int): Float {
	if (viewWidth <= 0f || sourceWidth <= 0 || sourceHeight <= 0) {
		return 0f
	}

	return viewWidth * sourceHeight / sourceWidth
}

/** Lift the unchanged artwork above the bottom icon row; clamp short viewports so the skyline stays visible. */
internal fun cloudMountainArtworkTop(viewHeight: Float, artworkHeight: Float) = (viewHeight * 0.91f - artworkHeight).coerceAtLeast(0f)
