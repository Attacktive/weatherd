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
			val artworkHeight = cloudMountainArtworkHeight(width, height, artwork.width, artwork.height)
			val artworkWidth = artworkHeight * artwork.width / artwork.height
			val left = (width - artworkWidth) / 2f
			val top = cloudMountainArtworkTop(height, artworkHeight)
			destination.set(left, top, left + artworkWidth, top + artworkHeight)
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

/** Cover the width and lift portrait peaks with uniform scaling; crop the sides rather than stretch the artwork. */
internal fun cloudMountainArtworkHeight(viewWidth: Float, viewHeight: Float, sourceWidth: Int, sourceHeight: Int): Float {
	if (viewWidth <= 0f || viewHeight <= 0f || sourceWidth <= 0 || sourceHeight <= 0) {
		return 0f
	}

	return maxOf(viewWidth * sourceHeight / sourceWidth, viewHeight * 0.38f)
}

/** Keep the fading base grounded; pin the skyline to the top when landscape is shorter than the scaled artwork. */
internal fun cloudMountainArtworkTop(viewHeight: Float, artworkHeight: Float) = (viewHeight - artworkHeight).coerceAtLeast(0f)
