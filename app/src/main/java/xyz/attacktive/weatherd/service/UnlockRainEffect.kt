package xyz.attacktive.weatherd.service

import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

internal class UnlockRainEffect {
	private val dropletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.rgb(190, 220, 240)
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
	}
	private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.WHITE
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
	}
	private val wiperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.rgb(24, 28, 32)
		strokeCap = Paint.Cap.ROUND
	}
	private val wiperEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.rgb(78, 86, 94)
		strokeCap = Paint.Cap.ROUND
	}

	fun draw(canvas: Canvas, width: Float, height: Float, elapsedSeconds: Float) {
		if (!elapsedSeconds.isFinite() || elapsedSeconds < 0f || elapsedSeconds >= EFFECT_DURATION_SECONDS || width <= 0f || height <= 0f) {
			return
		}

		val shortEdge = min(width, height)
		val reveal = smoothUnlockRain((elapsedSeconds / RAIN_REVEAL_SECONDS).coerceIn(0f, 1f))
		val fade = if (elapsedSeconds < FADE_START_SECONDS) {
			1f
		} else {
			1f - smoothUnlockRain(((elapsedSeconds - FADE_START_SECONDS) / (EFFECT_DURATION_SECONDS - FADE_START_SECONDS)).coerceIn(0f, 1f))
		}
		val opacity = reveal * fade
		val wipeProgress = ((elapsedSeconds - WIPER_START_SECONDS) / WIPER_DURATION_SECONDS).coerceIn(0f, 1f)
		val wipeEased = smoothUnlockRain(wipeProgress)
		val wiperAngle = lerpUnlockRain(WIPER_START_ANGLE_DEGREES, WIPER_END_ANGLE_DEGREES, wipeEased)
		val pivotX = width * WIPER_PIVOT_X_FRACTION
		val pivotY = height * WIPER_PIVOT_Y_FRACTION
		val bladeLength = height * WIPER_LENGTH_HEIGHT_FRACTION

		drawDroplets(
			canvas,
			width,
			height,
			shortEdge,
			opacity,
			pivotX,
			pivotY,
			bladeLength,
			wiperAngle,
			elapsedSeconds >= WIPER_START_SECONDS
		)

		if (elapsedSeconds in WIPER_START_SECONDS..WIPER_END_SECONDS) {
			drawWiper(canvas, shortEdge, pivotX, pivotY, bladeLength, wiperAngle)
		}
	}

	private fun drawDroplets(canvas: Canvas, width: Float, height: Float, shortEdge: Float, opacity: Float, pivotX: Float, pivotY: Float, bladeLength: Float, wiperAngle: Float, wipeStarted: Boolean) {
		for (index in 0 until DROPLET_COUNT) {
			val x = unitUnlockRain(index, DROPLET_X_SALT) * width
			val y = unitUnlockRain(index, DROPLET_Y_SALT) * height
			val radius = shortEdge * (DROPLET_MIN_RADIUS_FRACTION + unitUnlockRain(index, DROPLET_RADIUS_SALT) * DROPLET_RADIUS_RANGE_FRACTION)
			if (wipeStarted && dropletWasWiped(x, y, pivotX, pivotY, bladeLength, wiperAngle)) {
				continue
			}

			dropletPaint.alpha = (DROPLET_ALPHA * opacity).roundToInt().coerceIn(0, 255)
			dropletPaint.strokeWidth = (radius * 0.24f).coerceAtLeast(1f)
			canvas.drawOval(x - radius * 0.68f, y - radius, x + radius * 0.68f, y + radius, dropletPaint)

			highlightPaint.alpha = (HIGHLIGHT_ALPHA * opacity).roundToInt().coerceIn(0, 255)
			highlightPaint.strokeWidth = (radius * 0.12f).coerceAtLeast(1f)
			canvas.drawLine(x - radius * 0.28f, y - radius * 0.48f, x - radius * 0.08f, y - radius * 0.72f, highlightPaint)
			if (radius > shortEdge * LARGE_DROPLET_THRESHOLD_FRACTION) {
				canvas.drawLine(x, y + radius * 0.9f, x - radius * 0.12f, y + radius * 1.8f, dropletPaint)
			}
		}
	}

	private fun dropletWasWiped(x: Float, y: Float, pivotX: Float, pivotY: Float, bladeLength: Float, wiperAngle: Float): Boolean {
		val dx = x - pivotX
		val dy = y - pivotY
		val distanceSquared = dx * dx + dy * dy
		if (distanceSquared > bladeLength * bladeLength) {
			return false
		}

		val angle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()

		return angle in WIPER_START_ANGLE_DEGREES..wiperAngle
	}

	private fun drawWiper(canvas: Canvas, shortEdge: Float, pivotX: Float, pivotY: Float, bladeLength: Float, angle: Float) {
		val saved = canvas.save()
		canvas.rotate(angle, pivotX, pivotY)

		wiperPaint.alpha = 235
		wiperPaint.strokeWidth = shortEdge * WIPER_STROKE_FRACTION
		canvas.drawLine(pivotX + bladeLength * WIPER_BLADE_START_FRACTION, pivotY, pivotX + bladeLength, pivotY, wiperPaint)

		wiperEdgePaint.alpha = 200
		wiperEdgePaint.strokeWidth = shortEdge * WIPER_EDGE_STROKE_FRACTION
		canvas.drawLine(pivotX + bladeLength * WIPER_BLADE_START_FRACTION, pivotY - shortEdge * WIPER_EDGE_OFFSET_FRACTION, pivotX + bladeLength, pivotY - shortEdge * WIPER_EDGE_OFFSET_FRACTION, wiperEdgePaint)

		wiperPaint.strokeWidth = shortEdge * WIPER_ARM_STROKE_FRACTION
		canvas.drawLine(pivotX, pivotY, pivotX + bladeLength * WIPER_ARM_LENGTH_FRACTION, pivotY, wiperPaint)
		canvas.restoreToCount(saved)

		canvas.drawCircle(pivotX, pivotY, shortEdge * WIPER_PIVOT_RADIUS_FRACTION, wiperPaint)
	}
}

private fun smoothUnlockRain(value: Float) = value * value * (3f - 2f * value)

private fun lerpUnlockRain(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction

private fun unitUnlockRain(index: Int, salt: Int): Float {
	var value = index * 0x045d9f3b + salt
	value = value xor (value ushr 16)
	value *= 0x045d9f3b
	value = value xor (value ushr 16)

	return ((value ushr 8) and 0x00ff_ffff) / 16_777_216f
}

private const val EFFECT_DURATION_SECONDS = 2.6f
private const val RAIN_REVEAL_SECONDS = 0.35f
private const val FADE_START_SECONDS = 2.0f
private const val WIPER_START_SECONDS = 0.65f
private const val WIPER_DURATION_SECONDS = 1.15f
private const val WIPER_END_SECONDS = WIPER_START_SECONDS + WIPER_DURATION_SECONDS
private const val WIPER_START_ANGLE_DEGREES = -78f
private const val WIPER_END_ANGLE_DEGREES = -22f
private const val WIPER_PIVOT_X_FRACTION = 0.12f
private const val WIPER_PIVOT_Y_FRACTION = 1.04f
private const val WIPER_LENGTH_HEIGHT_FRACTION = 1.08f
private const val WIPER_BLADE_START_FRACTION = 0.18f
private const val WIPER_ARM_LENGTH_FRACTION = 0.46f
private const val WIPER_STROKE_FRACTION = 0.025f
private const val WIPER_EDGE_STROKE_FRACTION = 0.005f
private const val WIPER_ARM_STROKE_FRACTION = 0.012f
private const val WIPER_EDGE_OFFSET_FRACTION = 0.011f
private const val WIPER_PIVOT_RADIUS_FRACTION = 0.025f
private const val DROPLET_COUNT = 42
private const val DROPLET_MIN_RADIUS_FRACTION = 0.005f
private const val DROPLET_RADIUS_RANGE_FRACTION = 0.013f
private const val LARGE_DROPLET_THRESHOLD_FRACTION = 0.012f
private const val DROPLET_ALPHA = 175
private const val HIGHLIGHT_ALPHA = 125
private const val DROPLET_X_SALT = 0x13a5ba1d
private const val DROPLET_Y_SALT = 0x2156d72b
private const val DROPLET_RADIUS_SALT = 0x32c4f1a7
