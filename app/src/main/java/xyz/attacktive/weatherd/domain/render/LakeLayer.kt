package xyz.attacktive.weatherd.domain.render

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.SunColorPreset

internal class LakeLayer(private val resources: Resources) {
	private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val bankPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val waterPaint = Paint()
	private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val sourceRect = Rect()
	private val destination = RectF()
	private val lighting = SceneryLighting(SceneryPlane.FAR)
	private var paletteParams: SceneParams? = null
	private var waterColor = Color.BLACK

	fun isTextureReady() = banks != null && reflectedBanks != null

	fun prewarm() {
		if (isTextureReady()) {
			return
		}

		synchronized(LakeLayer::class.java) {
			if (banks == null || reflectedBanks == null) {
				val options = BitmapFactory.Options().apply { inScaled = false }
				val land = checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.lake_banks, options))
				val reflection = checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.lake_banks_reflected, options))
				reflectedBanks = reflection
				banks = land
			}
		}
	}

	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float, reflectedSky: Bitmap) {
		if (width <= 0f || height <= 0f) {
			return
		}

		val land = banks ?: return
		val reflection = reflectedBanks ?: return
		if (paletteParams != params) {
			val sky = skyGradientFor(params).bottomColor
			val nightScale = if (params.dayPhase == DayPhase.NIGHT) {
				params.nightBrightnessScale.coerceIn(0f, 1f)
			} else {
				1f
			}

			waterColor = Color.rgb(
				(Color.red(sky) * .27f * nightScale).toInt().coerceIn(0, 255),
				(Color.green(sky) * .47f * nightScale).toInt().coerceIn(0, 255),
				(Color.blue(sky) * .48f * nightScale).toInt().coerceIn(0, 255)
			)

			paletteParams = params
		}

		val waterTop = height * WATER_START
		val waterHeight = height - waterTop
		val wind = (params.windFactor * params.windScale).coerceIn(0f, 1f)
		val overscan = width * .025f * wind
		lighting.applyTo(bankPaint, params)

		canvas.save()
		canvas.clipRect(0f, waterTop, width, height)
		for (band in 0 until BAND_COUNT) {
			val start = band.toFloat() / BAND_COUNT
			val end = (band + 1f) / BAND_COUNT
			val top = waterTop + waterHeight * start
			val bottom = waterTop + waterHeight * end + 1f
			val movement = sin(start * 67f - timeSeconds * (.35f + wind * .7f)) + .45f * cos(start * 111f - timeSeconds * .5f)
			val shift = width * .012f * wind * movement * (.35f + .65f * start)
			destination.set(-overscan + shift, top, width + overscan + shift, bottom)
			val skyTop = ((WATER_START - (1f - WATER_START) * end * 1.43f) * reflectedSky.height).toInt().coerceIn(0, reflectedSky.height - 1)
			val skyBottom = ((WATER_START - (1f - WATER_START) * start * 1.43f) * reflectedSky.height).toInt().coerceIn(skyTop + 1, reflectedSky.height)
			sourceRect.set(0, skyTop, reflectedSky.width, skyBottom)
			canvas.save()
			canvas.scale(1f, -1f, 0f, (top + bottom) * .5f)
			canvas.drawBitmap(reflectedSky, sourceRect, destination, bitmapPaint)
			canvas.restore()

			val bankTop = ((WATER_START + (1f - WATER_START) * start) * reflection.height).toInt().coerceIn(0, reflection.height - 1)
			val bankBottom = ((WATER_START + (1f - WATER_START) * end) * reflection.height).toInt().coerceIn(bankTop + 1, reflection.height)
			sourceRect.set(0, bankTop, reflection.width, bankBottom)
			canvas.drawBitmap(reflection, sourceRect, destination, bankPaint)
		}

		waterPaint.color = waterColor
		waterPaint.alpha = 68
		canvas.drawRect(0f, waterTop, width, height, waterPaint)
		drawGlints(canvas, width, waterTop, waterHeight, params, timeSeconds, wind)
		canvas.restore()
		destination.set(0f, 0f, width, height)
		canvas.drawBitmap(land, null, destination, bankPaint)
	}

	private fun drawGlints(canvas: Canvas, width: Float, waterTop: Float, waterHeight: Float, params: SceneParams, timeSeconds: Float, wind: Float) {
		if (!params.sunVisible || params.dayPhase == DayPhase.NIGHT || params.precipitation != null || params.thunder || params.fogDensity > 0f) {
			return
		}

		val sunX = params.sunHorizontalPosition
		val sunY = celestialHeightFraction(params.dayPhase, params.celestialProgress)
		if (sunX !in 0f..1f || sunY !in 0f..WATER_START) {
			return
		}

		val visibility = sunVisibility(params.dayPhase, params.celestialProgress) * (1f - effectiveOpaqueCloudiness(params) / .7f).coerceIn(0f, 1f)
		if (visibility <= 0f) {
			return
		}

		glintPaint.color = when (params.sunColorPreset) {
			SunColorPreset.WHITE -> Color.rgb(255, 255, 248)
			SunColorPreset.NATURAL -> if (params.dayPhase == DayPhase.DAY) {
				Color.rgb(255, 248, 218)
			} else {
				Color.rgb(255, 218, 188)
			}
			SunColorPreset.GOLDEN -> Color.rgb(255, 228, 150)
			SunColorPreset.ORANGE -> Color.rgb(255, 231, 202)
		}

		for (i in 0 until GLINT_COUNT) {
			val depth = ((i * 37) % GLINT_COUNT + .5f) / GLINT_COUNT
			val spread = .025f + depth * .12f
			val offset = (((i * 53) % GLINT_COUNT).toFloat() / GLINT_COUNT * 2f - 1f) * spread
			val x = (sunX + offset) * width
			val shimmer = .5f + .5f * sin(timeSeconds * (.6f + wind) + i * 3.1f)
			val concentration = (1f - abs(offset) / spread).coerceIn(0f, 1f)
			val alpha = (visibility * concentration * shimmer * (12f + 27f * wind)).toInt().coerceIn(0, 255)
			glintPaint.alpha = alpha
			glintPaint.strokeWidth = 1f + depth * heightScale(waterHeight)
			val halfWidth = width * (.001f + .009f * depth) * (.65f + .35f * shimmer)
			val y = waterTop + waterHeight * (.05f + .9f * depth)
			canvas.drawLine(x - halfWidth, y, x + halfWidth, y, glintPaint)
		}
	}

	private fun heightScale(waterHeight: Float) = (waterHeight / 500f).coerceIn(0f, 1f)

	private companion object {
		const val WATER_START = 598f / 900f
		const val BAND_COUNT = 64
		const val GLINT_COUNT = 96

		@Volatile
		var banks: Bitmap? = null

		@Volatile
		var reflectedBanks: Bitmap? = null
	}
}
