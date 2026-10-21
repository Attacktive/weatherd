package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import xyz.attacktive.weatherd.domain.model.DayPhase

internal class DistantAircraftLayer {
	private val frame = DistantAircraftFrame()
	private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
	private val path = Path()
	private val trailSegment = TrailSegmentGeometry()

	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) {
		if (!showsDistantAircraft(params) || !frame.sample(width, height, timeSeconds)) {
			return
		}

		if (frame.hasTrail && params.dayPhase != DayPhase.NIGHT) {
			drawTrail(canvas, minOf(width, height), params.dayPhase)
		}

		if (frame.planeOpacity > 0f) {
			drawAircraft(canvas, params.dayPhase, timeSeconds - frame.passStartSeconds)
		}
	}

	private fun drawTrail(canvas: Canvas, shortEdge: Float, dayPhase: DayPhase) {
		val normalX = -frame.directionY
		val normalY = frame.directionX
		val color = trailColor(dayPhase)

		for (segment in 0 until TRAIL_SEGMENTS) {
			configureTrailSegment(segment, shortEdge, normalX, normalY)
			val alpha = trailAlpha(trailSegment.averageAgeSeconds)
			drawTrailSegment(canvas, color, alpha * OUTER_ALPHA_SCALE, OUTER_WIDTH_SCALE)
			drawTrailSegment(canvas, color, alpha, 1f)
		}
	}

	private fun configureTrailSegment(segment: Int, shortEdge: Float, normalX: Float, normalY: Float) {
		val startFraction = segment.toFloat() / TRAIL_SEGMENTS
		val endFraction = (segment + 1f) / TRAIL_SEGMENTS
		val startAge = lerp(frame.trailStartAgeSeconds, frame.trailEndAgeSeconds, startFraction)
		val endAge = lerp(frame.trailStartAgeSeconds, frame.trailEndAgeSeconds, endFraction)

		trailSegment.startX = lerp(frame.trailStartX, frame.trailEndX, startFraction)
		trailSegment.startY = lerp(frame.trailStartY, frame.trailEndY, startFraction)
		trailSegment.endX = lerp(frame.trailStartX, frame.trailEndX, endFraction)
		trailSegment.endY = lerp(frame.trailStartY, frame.trailEndY, endFraction)
		trailSegment.normalX = normalX
		trailSegment.normalY = normalY
		trailSegment.startWidth = trailWidth(shortEdge, startAge)
		trailSegment.endWidth = trailWidth(shortEdge, endAge)
		trailSegment.averageAgeSeconds = (startAge + endAge) * 0.5f
	}

	private fun drawTrailSegment(canvas: Canvas, color: Int, alpha: Float, widthScale: Float) {
		val startHalfWidth = trailSegment.startWidth * widthScale * 0.5f
		val endHalfWidth = trailSegment.endWidth * widthScale * 0.5f
		path.reset()
		path.moveTo(trailSegment.startX + trailSegment.normalX * startHalfWidth, trailSegment.startY + trailSegment.normalY * startHalfWidth)
		path.lineTo(trailSegment.endX + trailSegment.normalX * endHalfWidth, trailSegment.endY + trailSegment.normalY * endHalfWidth)
		path.lineTo(trailSegment.endX - trailSegment.normalX * endHalfWidth, trailSegment.endY - trailSegment.normalY * endHalfWidth)
		path.lineTo(trailSegment.startX - trailSegment.normalX * startHalfWidth, trailSegment.startY - trailSegment.normalY * startHalfWidth)
		path.close()

		paint.style = Paint.Style.FILL
		paint.color = withAlpha(color, alpha)
		canvas.drawPath(path, paint)
	}

	private fun drawAircraft(canvas: Canvas, dayPhase: DayPhase, elapsedSeconds: Float) {
		val length = frame.bodyLength * PLANE_VISUAL_SCALE
		val alpha = PLANE_PEAK_ALPHA * frame.planeOpacity * if (dayPhase == DayPhase.NIGHT) {
			NIGHT_BODY_ALPHA_SCALE
		} else {
			1f
		}

		val horizontalScale = if (frame.directionX >= 0f) {
			1f
		} else {
			-1f
		}

		val travelSlopeRadians = atan2(frame.directionY, abs(frame.directionX))
		val aircraftRotationRadians = travelSlopeRadians * horizontalScale
		val saveCount = canvas.save()
		canvas.translate(frame.planeX, frame.planeY)
		canvas.rotate(Math.toDegrees(aircraftRotationRadians.toDouble()).toFloat())
		canvas.scale(horizontalScale, 1f)

		buildAircraftSilhouette(length)
		paint.style = Paint.Style.FILL
		paint.color = withAlpha(aircraftBaseColor(dayPhase), alpha)
		canvas.drawPath(path, paint)

		if (dayPhase != DayPhase.NIGHT) {
			buildAircraftShadow(length)
			paint.color = withAlpha(aircraftShadowColor(dayPhase), alpha * AIRCRAFT_SHADOW_ALPHA_SCALE)
			canvas.drawPath(path, paint)

			buildAircraftHighlight(length)
			paint.color = withAlpha(aircraftHighlightColor(dayPhase), alpha * AIRCRAFT_HIGHLIGHT_ALPHA_SCALE)
			canvas.drawPath(path, paint)

			drawAircraftDetails(canvas, length, dayPhase, alpha)
		}

		drawAircraftLights(canvas, length, dayPhase, elapsedSeconds)
		canvas.restoreToCount(saveCount)
	}

	private fun drawAircraftLights(canvas: Canvas, length: Float, dayPhase: DayPhase, elapsedSeconds: Float) {
		val strength = when (dayPhase) {
			DayPhase.DAY -> 0f
			DayPhase.DAWN, DayPhase.DUSK -> 0.5f
			DayPhase.NIGHT -> 1f
		} * frame.planeOpacity

		if (strength <= 0f) {
			return
		}

		val radius = maxOf(0.75f, length * 0.035f)
		val haloRadiusScale = if (dayPhase == DayPhase.NIGHT) {
			2f
		} else {
			2.8f
		}

		// Mirroring X reverses the physical wing sides without swapping their screen heights.
		val lowerWingColor = if (frame.directionX >= 0f) {
			STARBOARD_LIGHT_COLOR
		} else {
			PORT_LIGHT_COLOR
		}

		val upperWingColor = if (frame.directionX >= 0f) {
			PORT_LIGHT_COLOR
		} else {
			STARBOARD_LIGHT_COLOR
		}

		drawLight(canvas, -length * 0.22f, length * 0.15f, radius, lowerWingColor, strength, haloRadiusScale)
		drawLight(canvas, -length * 0.18f, -length * 0.10f, radius, upperWingColor, strength * 0.7f, haloRadiusScale)
		drawLight(canvas, -length * 0.5f, 0f, radius * 0.85f, Color.WHITE, strength, haloRadiusScale)

		val beacon = flashStrength(elapsedSeconds, BEACON_PERIOD_SECONDS, BEACON_DURATION_SECONDS)
		drawLight(canvas, 0f, -length * 0.025f, radius * 0.8f, PORT_LIGHT_COLOR, strength * beacon, haloRadiusScale)

		val strobe = flashStrength(elapsedSeconds, STROBE_PERIOD_SECONDS, STROBE_DURATION_SECONDS)
		drawLight(canvas, -length * 0.22f, length * 0.15f, radius * 1.2f, Color.WHITE, strength * strobe, haloRadiusScale)
	}

	private fun drawLight(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int, strength: Float, haloRadiusScale: Float) {
		if (strength <= 0f) {
			return
		}

		paint.style = Paint.Style.FILL
		paint.color = withAlpha(color, 24f * strength)
		canvas.drawCircle(x, y, radius * haloRadiusScale, paint)
		paint.color = withAlpha(color, 255f * strength)
		canvas.drawCircle(x, y, radius, paint)
	}

	private fun flashStrength(elapsedSeconds: Float, periodSeconds: Float, durationSeconds: Float) =
		((durationSeconds - elapsedSeconds % periodSeconds) / FLASH_FADE_SECONDS).coerceIn(0f, 1f)

	private fun buildAircraftSilhouette(length: Float) {
		path.reset()
		path.moveTo(length * 0.52f, 0f)
		path.cubicTo(length * 0.46f, -length * 0.020f, length * 0.34f, -length * 0.034f, length * 0.12f, -length * 0.036f)
		path.lineTo(length * 0.08f, -length * 0.036f)
		path.lineTo(-length * 0.12f, -length * 0.105f)
		path.lineTo(-length * 0.20f, -length * 0.082f)
		path.lineTo(-length * 0.06f, -length * 0.034f)
		path.lineTo(-length * 0.30f, -length * 0.030f)
		path.lineTo(-length * 0.39f, -length * 0.13f)
		path.lineTo(-length * 0.47f, -length * 0.105f)
		path.lineTo(-length * 0.42f, -length * 0.030f)
		path.cubicTo(-length * 0.48f, -length * 0.026f, -length * 0.51f, -length * 0.013f, -length * 0.52f, 0f)
		path.cubicTo(-length * 0.51f, length * 0.013f, -length * 0.48f, length * 0.026f, -length * 0.42f, length * 0.030f)
		path.lineTo(-length * 0.46f, length * 0.062f)
		path.lineTo(-length * 0.39f, length * 0.070f)
		path.lineTo(-length * 0.31f, length * 0.032f)
		path.lineTo(-length * 0.08f, length * 0.038f)
		path.lineTo(-length * 0.24f, length * 0.145f)
		path.lineTo(-length * 0.20f, length * 0.16f)
		path.lineTo(length * 0.12f, length * 0.038f)
		path.cubicTo(length * 0.34f, length * 0.034f, length * 0.46f, length * 0.020f, length * 0.52f, 0f)
		path.close()
	}

	private fun buildAircraftShadow(length: Float) {
		path.reset()
		path.moveTo(length * 0.50f, 0f)
		path.lineTo(length * 0.10f, length * 0.014f)
		path.lineTo(length * 0.12f, length * 0.038f)
		path.lineTo(-length * 0.20f, length * 0.16f)
		path.lineTo(-length * 0.24f, length * 0.145f)
		path.lineTo(-length * 0.08f, length * 0.038f)
		path.lineTo(-length * 0.31f, length * 0.032f)
		path.lineTo(-length * 0.39f, length * 0.070f)
		path.lineTo(-length * 0.46f, length * 0.062f)
		path.lineTo(-length * 0.42f, length * 0.030f)
		path.cubicTo(-length * 0.48f, length * 0.026f, -length * 0.51f, length * 0.013f, -length * 0.52f, 0f)
		path.close()
	}

	private fun buildAircraftHighlight(length: Float) {
		path.reset()
		path.moveTo(length * 0.47f, -length * 0.004f)
		path.cubicTo(length * 0.34f, -length * 0.024f, length * 0.10f, -length * 0.027f, -length * 0.30f, -length * 0.024f)
		path.lineTo(-length * 0.31f, -length * 0.008f)
		path.cubicTo(-length * 0.05f, -length * 0.010f, length * 0.27f, -length * 0.010f, length * 0.47f, 0f)
		path.close()
	}

	private fun drawAircraftDetails(canvas: Canvas, length: Float, dayPhase: DayPhase, alpha: Float) {
		paint.color = withAlpha(aircraftAccentColor(dayPhase), alpha * AIRCRAFT_ACCENT_ALPHA_SCALE)
		path.reset()
		path.moveTo(-length * 0.30f, -length * 0.030f)
		path.lineTo(-length * 0.39f, -length * 0.13f)
		path.lineTo(-length * 0.47f, -length * 0.105f)
		path.lineTo(-length * 0.42f, -length * 0.030f)
		path.close()
		canvas.drawPath(path, paint)

		paint.color = withAlpha(aircraftCockpitColor(dayPhase), alpha * AIRCRAFT_COCKPIT_ALPHA_SCALE)
		canvas.drawOval(length * 0.31f, -length * 0.024f, length * 0.41f, length * 0.024f, paint)
		canvas.drawOval(-length * 0.02f, -length * 0.075f, length * 0.045f, -length * 0.050f, paint)
		canvas.drawOval(-length * 0.035f, length * 0.075f, length * 0.05f, length * 0.108f, paint)
	}

	private fun trailWidth(shortEdge: Float, ageSeconds: Float): Float {
		val ageFraction = (ageSeconds / TRAIL_LIFETIME_SECONDS).coerceIn(0f, 1f)

		return shortEdge * lerp(TRAIL_MIN_WIDTH_FRACTION, TRAIL_MAX_WIDTH_FRACTION, ageFraction)
	}

	private fun trailAlpha(ageSeconds: Float): Float {
		val ageFraction = (ageSeconds / TRAIL_LIFETIME_SECONDS).coerceIn(0f, 1f)

		return TRAIL_PEAK_ALPHA * (1f - ageFraction).pow(2)
	}

	private fun trailColor(dayPhase: DayPhase) = if (dayPhase == DayPhase.DAY) {
		DAY_TRAIL_COLOR
	} else {
		LOW_SUN_TRAIL_COLOR
	}

	private fun aircraftBaseColor(dayPhase: DayPhase) = when (dayPhase) {
		DayPhase.DAY -> DAY_AIRCRAFT_BASE_COLOR
		DayPhase.NIGHT -> NIGHT_AIRCRAFT_BASE_COLOR
		DayPhase.DAWN, DayPhase.DUSK -> LOW_SUN_AIRCRAFT_BASE_COLOR
	}

	private fun aircraftShadowColor(dayPhase: DayPhase) = if (dayPhase == DayPhase.DAY) {
		DAY_AIRCRAFT_SHADOW_COLOR
	} else {
		LOW_SUN_AIRCRAFT_SHADOW_COLOR
	}

	private fun aircraftHighlightColor(dayPhase: DayPhase) = if (dayPhase == DayPhase.DAY) {
		DAY_AIRCRAFT_HIGHLIGHT_COLOR
	} else {
		LOW_SUN_AIRCRAFT_HIGHLIGHT_COLOR
	}

	private fun aircraftAccentColor(dayPhase: DayPhase) = if (dayPhase == DayPhase.DAY) {
		DAY_AIRCRAFT_ACCENT_COLOR
	} else {
		LOW_SUN_AIRCRAFT_ACCENT_COLOR
	}

	private fun aircraftCockpitColor(dayPhase: DayPhase) = if (dayPhase == DayPhase.DAY) {
		DAY_AIRCRAFT_COCKPIT_COLOR
	} else {
		LOW_SUN_AIRCRAFT_COCKPIT_COLOR
	}

	private fun withAlpha(color: Int, alpha: Float) = Color.argb(
		alpha.toInt().coerceIn(0, 255),
		Color.red(color),
		Color.green(color),
		Color.blue(color)
	)

	private class TrailSegmentGeometry {
		var startX = 0f
		var startY = 0f
		var endX = 0f
		var endY = 0f
		var normalX = 0f
		var normalY = 0f
		var startWidth = 0f
		var endWidth = 0f
		var averageAgeSeconds = 0f
	}
}

private fun lerp(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction

private const val TRAIL_SEGMENTS = 8
private const val TRAIL_LIFETIME_SECONDS = 20f
private const val TRAIL_MIN_WIDTH_FRACTION = 0.0016f
private const val TRAIL_MAX_WIDTH_FRACTION = 0.0045f
private const val TRAIL_PEAK_ALPHA = 62f
private const val OUTER_ALPHA_SCALE = 0.25f
private const val OUTER_WIDTH_SCALE = 2f
private const val PLANE_VISUAL_SCALE = 3.45f
private const val PLANE_PEAK_ALPHA = 205f
private const val AIRCRAFT_SHADOW_ALPHA_SCALE = 0.72f
private const val AIRCRAFT_HIGHLIGHT_ALPHA_SCALE = 0.78f
private const val AIRCRAFT_ACCENT_ALPHA_SCALE = 0.82f
private const val AIRCRAFT_COCKPIT_ALPHA_SCALE = 0.90f
private const val NIGHT_BODY_ALPHA_SCALE = 0.55f
private const val BEACON_PERIOD_SECONDS = 1.4f
private const val BEACON_DURATION_SECONDS = 0.4f
private const val STROBE_PERIOD_SECONDS = 1.8f
private const val STROBE_DURATION_SECONDS = 0.35f
private const val FLASH_FADE_SECONDS = 0.1f
private val NIGHT_AIRCRAFT_BASE_COLOR = Color.rgb(42, 51, 69)
private val PORT_LIGHT_COLOR = Color.rgb(255, 55, 45)
private val STARBOARD_LIGHT_COLOR = Color.rgb(45, 245, 105)
private val DAY_TRAIL_COLOR = Color.rgb(232, 240, 250)
private val LOW_SUN_TRAIL_COLOR = Color.rgb(245, 220, 193)
private val DAY_AIRCRAFT_BASE_COLOR = Color.rgb(214, 224, 233)
private val DAY_AIRCRAFT_SHADOW_COLOR = Color.rgb(118, 139, 160)
private val DAY_AIRCRAFT_HIGHLIGHT_COLOR = Color.rgb(244, 248, 250)
private val DAY_AIRCRAFT_ACCENT_COLOR = Color.rgb(96, 132, 166)
private val DAY_AIRCRAFT_COCKPIT_COLOR = Color.rgb(65, 82, 99)
private val LOW_SUN_AIRCRAFT_BASE_COLOR = Color.rgb(230, 216, 201)
private val LOW_SUN_AIRCRAFT_SHADOW_COLOR = Color.rgb(148, 118, 108)
private val LOW_SUN_AIRCRAFT_HIGHLIGHT_COLOR = Color.rgb(250, 233, 214)
private val LOW_SUN_AIRCRAFT_ACCENT_COLOR = Color.rgb(150, 112, 101)
private val LOW_SUN_AIRCRAFT_COCKPIT_COLOR = Color.rgb(89, 72, 72)