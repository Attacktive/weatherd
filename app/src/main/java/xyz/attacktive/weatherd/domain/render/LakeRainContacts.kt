package xyz.attacktive.weatherd.domain.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.pow
import kotlin.random.Random
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

internal class LakeRainContacts {
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
	}
	private val xPositions = FloatArray(CONTACT_COUNT)
	private val yPositions = FloatArray(CONTACT_COUNT)
	private val phases = FloatArray(CONTACT_COUNT)
	private val strengths = FloatArray(CONTACT_COUNT)

	init {
		val random = Random(971)
		for (index in 0 until CONTACT_COUNT) {
			xPositions[index] = 0.08f + random.nextFloat() * 0.84f
			yPositions[index] = 0.75f + random.nextFloat() * 0.20f
			phases[index] = (index + random.nextFloat() * 0.7f) * CYCLE_SECONDS / CONTACT_COUNT
			strengths[index] = 0.66f + random.nextFloat() * 0.46f
		}
	}

	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float, ringsEnabled: Boolean) {
		val precipitation = params.precipitation
		if (precipitation?.kind != PrecipitationKind.RAIN || !validViewport(width, height) || !timeSeconds.isFinite() || !validRainScale(params.precipitationScale)) {
			return
		}

		val count = precipitationDropCount(precipitation, params.precipitationScale, 360f, 780f).coerceIn(0, CONTACT_COUNT)
		val time = ((timeSeconds % CYCLE_SECONDS) + CYCLE_SECONDS) % CYCLE_SECONDS
		paint.strokeWidth = (width / 1200f).coerceAtLeast(1f)
		drawApproachDrops(canvas, width, height, time, count)
		if (ringsEnabled) {
			drawRings(canvas, width, height, time, count)
		}
	}

	private fun validViewport(width: Float, height: Float) = width.isFinite() && height.isFinite() && width > 0f && height > 0f

	private fun validRainScale(scale: Float) = scale.isFinite() && scale > 0f

	private fun drawApproachDrops(canvas: Canvas, width: Float, height: Float, time: Float, count: Int) {
		for (index in 0 until count) {
			val until = (phases[index] - time + CYCLE_SECONDS) % CYCLE_SECONDS
			if (until > 0f && until < APPROACH_SECONDS) {
				val distance = until * height * 0.733f
				val x = xPositions[index] * width - distance * 0.08f
				val y = yPositions[index] * height - distance
				paint.color = Color.argb((95f * strengths[index]).toInt().coerceIn(0, 255), 204, 217, 221)
				canvas.drawLine(x - width / 1200f, y - height / 90f, x, y, paint)
			}
		}
	}

	private fun drawRings(canvas: Canvas, width: Float, height: Float, time: Float, count: Int) {
		for (index in 0 until count) {
			val age = (time - phases[index] + CYCLE_SECONDS) % CYCLE_SECONDS
			if (age >= RING_SECONDS) {
				continue
			}

			val progress = age / RING_SECONDS
			val depth = (yPositions[index] - 0.75f) / 0.20f
			val radiusX = (2f + 18f * progress.pow(0.74f)) * strengths[index] * (0.65f + 0.35f * depth) * width / 1200f
			val radiusY = radiusX * (0.25f + 0.09f * depth) * height / width
			val x = xPositions[index] * width
			val y = yPositions[index] * height
			val fade = strengths[index] * (1f - progress).pow(1.15f) * (1f - 0.18f * progress)
			paint.color = Color.argb((140f * fade).toInt().coerceIn(0, 255), 28, 50, 55)
			canvas.drawOval(x - radiusX, y - radiusY + height / 900f, x + radiusX, y + radiusY + height / 900f, paint)
			paint.color = Color.argb((235f * fade).toInt().coerceIn(0, 255), 219, 233, 231)
			canvas.drawOval(x - radiusX, y - radiusY, x + radiusX, y + radiusY, paint)
			paint.color = Color.argb((98f * fade).toInt().coerceIn(0, 255), 188, 207, 209)
			canvas.drawOval(x - radiusX * 0.69f, y - radiusY * 0.69f, x + radiusX * 0.69f, y + radiusY * 0.69f, paint)
		}
	}

	private companion object {
		const val CONTACT_COUNT = 46
		const val CYCLE_SECONDS = 6f
		const val APPROACH_SECONDS = 0.68f
		const val RING_SECONDS = 0.77f
	}
}
