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
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase

/**
 * The submitted cloud-mountain artwork anchors the horizon under the live sky and weather.
 * It is decoded once off the frame loop, keeps its original proportions, and takes phase/weather tint without re-rasterizing.
 */
internal class CloudMountainLayer(private val resources: Resources) {
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val destination = RectF()
	private var cachedWidth = 0f
	private var cachedHeight = 0f
	private var cachedTint = 0

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
			destination.set(0f, height - artworkHeight, width, height)
			cachedWidth = width
			cachedHeight = height
		}

		val tint = cloudMountainTint(params)
		if (tint != cachedTint) {
			paint.colorFilter = LightingColorFilter(tint, 0)
			cachedTint = tint
		}

		canvas.drawBitmap(artwork, null, destination, paint)
	}

	private companion object {
		@Volatile
		var source: Bitmap? = null
	}
}

/** Scaling by width preserves the submitted composition in portrait; landscape crops the skyward excess rather than squashing the mountains. */
internal fun cloudMountainArtworkHeight(viewWidth: Float, sourceWidth: Int, sourceHeight: Int): Float {
	if (viewWidth <= 0f || sourceWidth <= 0 || sourceHeight <= 0) {
		return 0f
	}

	return viewWidth * sourceHeight / sourceWidth
}

/** Multiply tint follows the existing cloud palette, with additional storm gloom and respect for night brightness. */
internal fun cloudMountainTint(params: SceneParams): Int {
	val phase = cumulusTint(params.dayPhase, params.celestialProgress)
	val storm = if (params.thunder) {
		0.35f
	} else {
		(params.precipitation?.severity ?: 0f) * 0.15f
	}

	val nightScale = if (params.dayPhase == DayPhase.NIGHT) {
		params.nightBrightnessScale.coerceIn(0f, 1f)
	} else {
		1f
	}

	val brightness = (1f - effectiveOpaqueCloudiness(params) * 0.22f - storm).coerceIn(0.25f, 1f) * nightScale

	return Color.rgb(
		(Color.red(phase) * brightness).roundToInt().coerceIn(0, 255),
		(Color.green(phase) * brightness).roundToInt().coerceIn(0, 255),
		(Color.blue(phase) * brightness).roundToInt().coerceIn(0, 255)
	)
}
