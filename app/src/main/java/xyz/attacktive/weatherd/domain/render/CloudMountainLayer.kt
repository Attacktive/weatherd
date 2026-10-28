package xyz.attacktive.weatherd.domain.render

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.roundToInt
import kotlin.math.sin
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import androidx.core.graphics.withTranslation

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
	private val mistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		shader = RadialGradient(0f, 0f, 1f, 0xFFD5DFEB.toInt(), 0x00D5DFEB, Shader.TileMode.CLAMP)
	}

	private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		shader = RadialGradient(
			0f,
			0f,
			1f,
			intArrayOf(0xFFFFD39C.toInt(), 0xD0FFD39C.toInt(), 0x30FFB96F, 0x00FFB96F),
			floatArrayOf(0f, 0.18f, 0.4f, 1f),
			Shader.TileMode.CLAMP
		)
	}

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

	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) {
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
		drawValleyMist(canvas, params, timeSeconds)
		drawDistantLights(canvas, params)
	}

	/** Two low-opacity veils stay in the artwork's valleys, away from the detailed summit silhouette. */
	private fun drawValleyMist(canvas: Canvas, params: SceneParams, timeSeconds: Float) {
		val strength = when (params.dayPhase) {
			DayPhase.DAWN -> dawnSunriseStrength(params.celestialProgress) * (1f - dawnDaylightStrength(params.celestialProgress))
			DayPhase.DUSK -> duskWarmStrength(params.celestialProgress) * (1f - duskNightStrength(params.celestialProgress))
			DayPhase.DAY, DayPhase.NIGHT -> 0f
		}

		val alpha = (18f * strength * (1f - effectiveFogDensity(params).coerceIn(0f, 1f))).roundToInt()
		if (alpha <= 0) {
			return
		}

		mistPaint.alpha = alpha
		mistPaint.colorFilter = paint.colorFilter

		val drift = sin(timeSeconds * 0.035f) * 0.015f
		drawMistBand(canvas, 0.32f + drift, 0.47f, 0.20f, 0.035f)
		drawMistBand(canvas, 0.62f - drift * 0.6f, 0.365f, 0.17f, 0.025f)
	}

	private fun drawMistBand(canvas: Canvas, x: Float, y: Float, halfWidth: Float, halfHeight: Float) {
		val checkpoint = canvas.save()
		canvas.translate(destination.left + destination.width() * x, destination.top + destination.height() * y)
		canvas.scale(destination.width() * halfWidth, destination.height() * halfHeight)
		canvas.drawCircle(0f, 0f, 1f, mistPaint)
		canvas.restoreToCount(checkpoint)
	}

	/** A few irregular habitation anchors follow the terrain, never the animation clock. */
	private fun drawDistantLights(canvas: Canvas, params: SceneParams) {
		val night = when (params.dayPhase) {
			DayPhase.DAY -> 0f
			DayPhase.DAWN -> 1f - dawnSunriseStrength(params.celestialProgress)
			DayPhase.DUSK -> duskNightStrength(params.celestialProgress)
			DayPhase.NIGHT -> 1f
		}

		val strength = if (params.dayPhase == DayPhase.DUSK) {
			0.18f * duskWarmStrength(params.celestialProgress) + 0.82f * night
		} else {
			night
		}

		val brightness = 1f - night * (1f - params.nightBrightnessScale.coerceIn(0f, 1f))
		val alpha = (180f * strength * brightness * (1f - effectiveFogDensity(params).coerceIn(0f, 1f))).roundToInt()
		if (alpha <= 0) {
			return
		}

		lightPaint.alpha = alpha
		val radius = destination.width() * 0.0036f
		var index = 0
		while (index < LIGHT_ANCHORS.size) {
			val x = destination.left + destination.width() * LIGHT_ANCHORS[index]
			val y = destination.top + destination.height() * LIGHT_ANCHORS[index + 1]

			canvas.withTranslation(x, y) {
				canvas.scale(radius, radius)
				canvas.drawCircle(0f, 0f, 1f, lightPaint)
			}

			index += 2
		}
	}

	private companion object {
		// Two tiny foothill groups and one farther valley light, normalized to the unchanged artwork.
		val LIGHT_ANCHORS = floatArrayOf(
			0.356f, 0.511f,
			0.364f, 0.507f,
			0.371f, 0.512f,
			0.681f, 0.470f,
			0.688f, 0.474f,
			0.537f, 0.405f
		)

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
