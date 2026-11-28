package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.withClip
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.SunColorPreset

internal class LakeLayer(private val resources: Resources) {
	private val skyReflectionPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val skyShaderMatrix = Matrix()
	private var skyShaderBitmap: Bitmap? = null
	private var skyShaderGeneration = 0
	private var skyShader: BitmapShader? = null
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

			val red = (Color.red(sky) * .27f * nightScale)
				.toInt()
				.coerceIn(0, 255)

			val green = (Color.green(sky) * .47f * nightScale)
				.toInt()
				.coerceIn(0, 255)

			val blue = (Color.blue(sky) * .48f * nightScale)
				.toInt()
				.coerceIn(0, 255)

			waterColor = Color.rgb(red, green, blue)
			paletteParams = params
		}

		val waterTop = height * LAKE_WATER_START
		val waterHeight = height - waterTop
		val wind = (params.windFactor * params.windScale).coerceIn(0f, 1f)
		val overscan = width * .025f * wind
		val skyScaleX = (width + overscan * 2f) / reflectedSky.width
		val skyScaleY = -waterHeight / reflectedSky.height
		val skyShader = skyShaderFor(reflectedSky)
		lighting.applyTo(bankPaint, params)

		canvas.withClip(0f, waterTop, width, height) {
			for (band in 0 until BAND_COUNT) {
				val start = band.toFloat() / BAND_COUNT
				val end = (band + 1f) / BAND_COUNT
				val top = waterTop + waterHeight * start
				val bottom = waterTop + waterHeight * end + 1f
				val movement = sin(start * 67f - timeSeconds * (.35f + wind * .7f)) + .45f * cos(start * 111f - timeSeconds * .5f)
				val shift = width * .012f * wind * movement * (.35f + .65f * start)
				skyShaderMatrix.setScale(skyScaleX, skyScaleY)
				skyShaderMatrix.postTranslate(-overscan + shift, height)
				skyShader.setLocalMatrix(skyShaderMatrix)
				drawRect(0f, top, width, bottom, skyReflectionPaint)

				destination.set(-overscan + shift, top, width + overscan + shift, bottom)
				val bankTop = ((LAKE_WATER_START + (1f - LAKE_WATER_START) * start) * reflection.height)
					.toInt()
					.coerceIn(0, reflection.height - 1)

				val bankBottom = ((LAKE_WATER_START + (1f - LAKE_WATER_START) * end) * reflection.height)
					.toInt()
					.coerceIn(bankTop + 1, reflection.height)

				sourceRect.set(0, bankTop, reflection.width, bankBottom)
				drawBitmap(reflection, sourceRect, destination, bankPaint)
			}

			waterPaint.color = waterColor
			waterPaint.alpha = 68
			drawRect(0f, waterTop, width, height, waterPaint)
			drawGlints(this, width, waterTop, waterHeight, params, timeSeconds, wind)
		}

		destination.set(0f, 0f, width, height)
		canvas.drawBitmap(land, null, destination, bankPaint)
	}

	private fun skyShaderFor(reflectedSky: Bitmap): BitmapShader {
		val generation = reflectedSky.generationId
		val existing = skyShader
		if (existing != null && skyShaderBitmap === reflectedSky && skyShaderGeneration == generation) {
			return existing
		}

		return BitmapShader(reflectedSky, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
			skyShaderBitmap = reflectedSky
			skyShaderGeneration = generation
			skyShader = it
			skyReflectionPaint.shader = it
		}
	}

	private fun drawGlints(canvas: Canvas, width: Float, waterTop: Float, waterHeight: Float, params: SceneParams, timeSeconds: Float, wind: Float) {
		val visibility = glintVisibility(params)
		if (visibility <= 0f) {
			return
		}

		val sunX = params.sunHorizontalPosition
		glintPaint.color = glintColor(params)

		for (i in 0 until GLINT_COUNT) {
			val depth = ((i * 37) % GLINT_COUNT + .5f) / GLINT_COUNT
			val spread = .025f + depth * .12f
			val offset = (((i * 53) % GLINT_COUNT).toFloat() / GLINT_COUNT * 2f - 1f) * spread
			val x = (sunX + offset) * width
			val shimmer = .5f + .5f * sin(timeSeconds * (.6f + wind) + i * 3.1f)
			val concentration = (1f - abs(offset) / spread).coerceIn(0f, 1f)
			val alpha = (visibility * concentration * shimmer * (12f + 27f * wind))
				.toInt()
				.coerceIn(0, 255)

			if (alpha <= 0) {
				continue
			}

			glintPaint.alpha = alpha
			glintPaint.strokeWidth = 1f + depth * heightScale(waterHeight)
			val halfWidth = width * (.001f + .009f * depth) * (.65f + .35f * shimmer)
			val y = waterTop + waterHeight * (.05f + .9f * depth)
			canvas.drawLine(x - halfWidth, y, x + halfWidth, y, glintPaint)
		}
	}

	private fun glintVisibility(params: SceneParams): Float {
		if (!params.sunVisible || params.dayPhase == DayPhase.NIGHT || params.precipitation != null || params.thunder || params.fogDensity > 0f) {
			return 0f
		}

		val sunY = celestialHeightFraction(params.dayPhase, params.celestialProgress)
		if (params.sunHorizontalPosition !in 0f..1f || sunY !in 0f..LAKE_WATER_START) {
			return 0f
		}

		return sunVisibility(params.dayPhase, params.celestialProgress) * (1f - effectiveOpaqueCloudiness(params) / .7f).coerceIn(0f, 1f)
	}

	private fun glintColor(params: SceneParams) = when (params.sunColorPreset) {
		SunColorPreset.WHITE -> Color.rgb(255, 255, 248)
		SunColorPreset.NATURAL -> if (params.dayPhase == DayPhase.DAY) {
			Color.rgb(255, 248, 218)
		} else {
			Color.rgb(255, 218, 188)
		}

		SunColorPreset.GOLDEN -> Color.rgb(255, 228, 150)
		SunColorPreset.ORANGE -> Color.rgb(255, 231, 202)
	}

	private fun heightScale(waterHeight: Float) = (waterHeight / 500f).coerceIn(0f, 1f)

	private companion object {
		const val BAND_COUNT = 64
		const val GLINT_COUNT = 96

		@Volatile
		var banks: Bitmap? = null

		@Volatile
		var reflectedBanks: Bitmap? = null
	}
}
