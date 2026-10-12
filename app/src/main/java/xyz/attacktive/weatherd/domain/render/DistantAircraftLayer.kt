package xyz.attacktive.weatherd.domain.render

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

		if (frame.hasTrail) {
			drawTrail(canvas, minOf(width, height), params.dayPhase)
		}

		if (frame.planeOpacity > 0f) {
			drawAircraft(canvas, params.dayPhase)
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

	private fun drawAircraft(canvas: Canvas, dayPhase: DayPhase) {
		val length = frame.bodyLength * PLANE_VISUAL_SCALE
		val alpha = PLANE_PEAK_ALPHA * frame.planeOpacity
		val saveCount = canvas.save()
		canvas.translate(frame.planeX, frame.planeY)
		canvas.rotate(Math.toDegrees(atan2(frame.directionY, frame.directionX).toDouble()).toFloat())

		buildAircraftSilhouette(length)
		paint.style = Paint.Style.FILL
		paint.color = withAlpha(aircraftBaseColor(dayPhase), alpha)
		canvas.drawPath(path, paint)

		buildAircraftShadow(length)
		paint.color = withAlpha(aircraftShadowColor(dayPhase), alpha * AIRCRAFT_SHADOW_ALPHA_SCALE)
		canvas.drawPath(path, paint)

		buildAircraftHighlight(length)
		paint.color = withAlpha(aircraftHighlightColor(dayPhase), alpha * AIRCRAFT_HIGHLIGHT_ALPHA_SCALE)
		canvas.drawPath(path, paint)

		drawAircraftDetails(canvas, length, dayPhase, alpha)
		canvas.restoreToCount(saveCount)
	}

	private fun buildAircraftSilhouette(length: Float) {
		path.reset()
		path.moveTo(length * 0.52f, 0f)
		path.cubicTo(length * 0.46f, -length * 0.022f, length * 0.35f, -length * 0.038f, length * 0.10f, -length * 0.040f)
		path.lineTo(length * 0.04f, -length * 0.040f)
		path.lineTo(-length * 0.11f, -length * 0.28f)
		path.lineTo(-length * 0.17f, -length * 0.265f)
		path.lineTo(-length * 0.10f, -length * 0.044f)
		path.lineTo(-length * 0.32f, -length * 0.036f)
		path.lineTo(-length * 0.42f, -length * 0.09f)
		path.lineTo(-length * 0.445f, -length * 0.085f)
		path.lineTo(-length * 0.43f, -length * 0.030f)
		path.cubicTo(-length * 0.48f, -length * 0.026f, -length * 0.51f, -length * 0.013f, -length * 0.52f, 0f)
		path.cubicTo(-length * 0.51f, length * 0.013f, -length * 0.48f, length * 0.026f, -length * 0.43f, length * 0.030f)
		path.lineTo(-length * 0.46f, length * 0.10f)
		path.lineTo(-length * 0.43f, length * 0.11f)
		path.lineTo(-length * 0.32f, length * 0.036f)
		path.lineTo(-length * 0.10f, length * 0.044f)
		path.lineTo(-length * 0.21f, length * 0.33f)
		path.lineTo(-length * 0.13f, length * 0.35f)
		path.lineTo(length * 0.04f, length * 0.040f)
		path.lineTo(length * 0.10f, length * 0.040f)
		path.cubicTo(length * 0.35f, length * 0.038f, length * 0.46f, length * 0.022f, length * 0.52f, 0f)
		path.close()
	}

	private fun buildAircraftShadow(length: Float) {
		path.reset()
		path.moveTo(length * 0.50f, 0f)
		path.lineTo(length * 0.10f, length * 0.014f)
		path.lineTo(length * 0.04f, length * 0.040f)
		path.lineTo(-length * 0.13f, length * 0.35f)
		path.lineTo(-length * 0.21f, length * 0.33f)
		path.lineTo(-length * 0.10f, length * 0.044f)
		path.lineTo(-length * 0.32f, length * 0.036f)
		path.lineTo(-length * 0.43f, length * 0.11f)
		path.lineTo(-length * 0.46f, length * 0.10f)
		path.lineTo(-length * 0.43f, length * 0.030f)
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
		path.moveTo(-length * 0.31f, -length * 0.034f)
		path.lineTo(-length * 0.42f, -length * 0.09f)
		path.lineTo(-length * 0.445f, -length * 0.085f)
		path.lineTo(-length * 0.43f, -length * 0.030f)
		path.close()
		canvas.drawPath(path, paint)

		paint.color = withAlpha(aircraftCockpitColor(dayPhase), alpha * AIRCRAFT_COCKPIT_ALPHA_SCALE)
		canvas.drawOval(length * 0.31f, -length * 0.024f, length * 0.41f, length * 0.024f, paint)
		canvas.drawOval(-length * 0.03f, -length * 0.108f, length * 0.04f, -length * 0.076f, paint)
		canvas.drawOval(-length * 0.04f, length * 0.085f, length * 0.05f, length * 0.125f, paint)
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

	private fun aircraftBaseColor(dayPhase: DayPhase) = if (dayPhase == DayPhase.DAY) {
		DAY_AIRCRAFT_BASE_COLOR
	} else {
		LOW_SUN_AIRCRAFT_BASE_COLOR
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