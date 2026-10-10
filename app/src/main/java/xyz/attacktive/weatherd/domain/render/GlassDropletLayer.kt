package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

internal class GlassDropletLayer {
	private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
		.apply {
			style = Paint.Style.FILL
			color = Color.rgb(222, 232, 240)
		}

	private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
		.apply {
			style = Paint.Style.STROKE
			strokeCap = Paint.Cap.ROUND
			color = Color.rgb(24, 34, 44)
		}

	private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
		.apply {
			style = Paint.Style.FILL
			color = Color.WHITE
		}

	private val bodyPath = Path()
	private val rimPath = Path()
	private val bodyRect = RectF()
	private val highlightRect = RectF()
	private val field = GlassDropletField()

	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams, timeSeconds: Float) {
		val count = field.sample(glassDropletCount(params, width, height), width, height, timeSeconds)

		for (slot in 0 until count) {
			val frame = field.frame(slot)
			if (frame.opacity <= 0f) {
				continue
			}

			val halfHeight = frame.radius * frame.verticalStretch
			if (isOutsideViewport(frame, width, height, halfHeight)) {
				continue
			}

			drawDroplet(canvas, frame, halfHeight)
		}
	}

	private fun isOutsideViewport(frame: GlassDropletFrame, width: Float, height: Float, halfHeight: Float) = isOutsideHorizontalViewport(frame, width) || isOutsideVerticalViewport(frame, height, halfHeight)

	private fun isOutsideHorizontalViewport(frame: GlassDropletFrame, width: Float) = frame.centerX + frame.radius < 0f || frame.centerX - frame.radius > width

	private fun isOutsideVerticalViewport(frame: GlassDropletFrame, height: Float, halfHeight: Float) = frame.centerY + halfHeight < 0f || frame.centerY - halfHeight > height

	private fun drawDroplet(canvas: Canvas, frame: GlassDropletFrame, halfHeight: Float) {
		bodyPaint.alpha = (GLASS_DROPLET_BODY_ALPHA * frame.opacity).roundToInt().coerceIn(0, 255)
		rimPaint.alpha = (GLASS_DROPLET_RIM_ALPHA * frame.opacity).roundToInt().coerceIn(0, 255)
		rimPaint.strokeWidth = maxOf(GLASS_DROPLET_MIN_STROKE_WIDTH, frame.radius * GLASS_DROPLET_RIM_WIDTH_FRACTION)
		highlightPaint.alpha = (GLASS_DROPLET_HIGHLIGHT_ALPHA * frame.opacity).roundToInt().coerceIn(0, 255)

		if (frame.verticalStretch <= GLASS_DROPLET_ROUND_STRETCH_THRESHOLD) {
			drawRoundBodyAndRim(canvas, frame)
		} else {
			drawSlidingBodyAndRim(canvas, frame, halfHeight)
		}

		val highlightRadiusX = frame.radius * GLASS_DROPLET_HIGHLIGHT_RADIUS_X_FRACTION
		val highlightRadiusY = frame.radius * GLASS_DROPLET_HIGHLIGHT_RADIUS_Y_FRACTION
		val highlightCenterX = frame.centerX - frame.radius * GLASS_DROPLET_HIGHLIGHT_X_OFFSET_FRACTION
		val highlightCenterY = frame.centerY - halfHeight * GLASS_DROPLET_HIGHLIGHT_Y_OFFSET_FRACTION
		highlightRect.set(
			highlightCenterX - highlightRadiusX,
			highlightCenterY - highlightRadiusY,
			highlightCenterX + highlightRadiusX,
			highlightCenterY + highlightRadiusY
		)

		canvas.drawOval(highlightRect, highlightPaint)
	}

	private fun drawRoundBodyAndRim(canvas: Canvas, frame: GlassDropletFrame) {
		bodyRect.set(
			frame.centerX - frame.radius,
			frame.centerY - frame.radius,
			frame.centerX + frame.radius,
			frame.centerY + frame.radius
		)

		canvas.drawOval(bodyRect, bodyPaint)
		canvas.drawArc(bodyRect, GLASS_DROPLET_RIM_START_DEGREES, GLASS_DROPLET_RIM_SWEEP_DEGREES, false, rimPaint)
	}

	private fun drawSlidingBodyAndRim(canvas: Canvas, frame: GlassDropletFrame, halfHeight: Float) {
		val halfWidth = frame.radius * (1f - GLASS_DROPLET_SLIDE_NARROWING * ((frame.verticalStretch - 1f) / GLASS_DROPLET_STRETCH_RANGE).coerceIn(0f, 1f))
		val top = frame.centerY - halfHeight
		val bottom = frame.centerY + halfHeight
		val rightShoulderX = frame.centerX + halfWidth * GLASS_DROPLET_LOWER_SHOULDER_X_FRACTION
		val lowerShoulderY = frame.centerY + halfHeight * GLASS_DROPLET_LOWER_SHOULDER_Y_FRACTION
		val rightBottomControlX = frame.centerX + halfWidth * GLASS_DROPLET_BOTTOM_CONTROL_X_FRACTION
		val leftBottomControlX = frame.centerX - halfWidth * GLASS_DROPLET_BOTTOM_CONTROL_X_FRACTION
		val leftShoulderX = frame.centerX - halfWidth * GLASS_DROPLET_LOWER_SHOULDER_X_FRACTION

		bodyPath.rewind()
		bodyPath.moveTo(frame.centerX, top)
		bodyPath.cubicTo(
			frame.centerX + halfWidth * GLASS_DROPLET_UPPER_CONTROL_X_FRACTION,
			top + halfHeight * GLASS_DROPLET_UPPER_CONTROL_Y_FRACTION,
			frame.centerX + halfWidth,
			frame.centerY + halfHeight * GLASS_DROPLET_SIDE_CONTROL_Y_FRACTION,
			rightShoulderX,
			lowerShoulderY
		)

		bodyPath.cubicTo(
			rightBottomControlX,
			bottom,
			leftBottomControlX,
			bottom,
			leftShoulderX,
			lowerShoulderY
		)

		bodyPath.cubicTo(
			frame.centerX - halfWidth,
			frame.centerY + halfHeight * GLASS_DROPLET_SIDE_CONTROL_Y_FRACTION,
			frame.centerX - halfWidth * GLASS_DROPLET_UPPER_CONTROL_X_FRACTION,
			top + halfHeight * GLASS_DROPLET_UPPER_CONTROL_Y_FRACTION,
			frame.centerX,
			top
		)

		bodyPath.close()
		canvas.drawPath(bodyPath, bodyPaint)

		rimPath.rewind()
		rimPath.moveTo(rightShoulderX, lowerShoulderY)
		rimPath.cubicTo(
			rightBottomControlX,
			bottom,
			leftBottomControlX,
			bottom,
			leftShoulderX,
			lowerShoulderY
		)

		canvas.drawPath(rimPath, rimPaint)
	}

	private companion object {
		const val GLASS_DROPLET_BODY_ALPHA = 48
		const val GLASS_DROPLET_RIM_ALPHA = 78
		const val GLASS_DROPLET_HIGHLIGHT_ALPHA = 138
		const val GLASS_DROPLET_MIN_STROKE_WIDTH = 1f
		const val GLASS_DROPLET_RIM_WIDTH_FRACTION = 0.14f
		const val GLASS_DROPLET_ROUND_STRETCH_THRESHOLD = 1.05f
		const val GLASS_DROPLET_RIM_START_DEGREES = 20f
		const val GLASS_DROPLET_RIM_SWEEP_DEGREES = 140f
		const val GLASS_DROPLET_HIGHLIGHT_RADIUS_X_FRACTION = 0.22f
		const val GLASS_DROPLET_HIGHLIGHT_RADIUS_Y_FRACTION = 0.12f
		const val GLASS_DROPLET_HIGHLIGHT_X_OFFSET_FRACTION = 0.3f
		const val GLASS_DROPLET_HIGHLIGHT_Y_OFFSET_FRACTION = 0.32f
		const val GLASS_DROPLET_SLIDE_NARROWING = 0.12f
		const val GLASS_DROPLET_STRETCH_RANGE = 0.8f
		const val GLASS_DROPLET_UPPER_CONTROL_X_FRACTION = 0.75f
		const val GLASS_DROPLET_UPPER_CONTROL_Y_FRACTION = 0.12f
		const val GLASS_DROPLET_SIDE_CONTROL_Y_FRACTION = 0.18f
		const val GLASS_DROPLET_LOWER_SHOULDER_X_FRACTION = 0.68f
		const val GLASS_DROPLET_LOWER_SHOULDER_Y_FRACTION = 0.62f
		const val GLASS_DROPLET_BOTTOM_CONTROL_X_FRACTION = 0.38f
	}
}
