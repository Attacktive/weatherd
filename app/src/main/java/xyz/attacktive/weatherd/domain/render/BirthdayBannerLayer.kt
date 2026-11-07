package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

internal class BirthdayBannerLayer {
	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		textAlign = Paint.Align.CENTER
		typeface = Typeface.create("cursive", Typeface.BOLD)
		color = Color.rgb(255, 235, 177)
	}

	private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.rgb(32, 27, 57)
	}

	private val trimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		color = Color.rgb(255, 205, 133)
	}

	private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.rgb(255, 224, 150)
	}

	private val sparklePath = Path().apply {
		moveTo(0f, -1f)
		quadTo(0.18f, -0.18f, 1f, 0f)
		quadTo(0.18f, 0.18f, 0f, 1f)
		quadTo(-0.18f, 0.18f, -1f, 0f)
		quadTo(-0.18f, -0.18f, 0f, -1f)
		close()
	}

	private val metrics = Paint.FontMetrics()
	private var cachedText = ""
	private var cachedWidth = 0f
	private var cachedHeight = 0f
	private var glyphWidth = 0f
	private var bannerWidth = 0f
	private var baselineOffset = 0f

	fun draw(canvas: Canvas, width: Float, text: String, frame: SeasonalCelebrationFrame) {
		val height = frame.birthdayGreetingHeight
		val opacity = frame.birthdayGreetingOpacity
		if (text.isEmpty() || height <= 0f || opacity <= 0f) {
			return
		}

		prepareTextLayout(width, text, height)

		val centerX = width * 0.5f
		val centerY = frame.birthdayGreetingY
		val left = centerX - bannerWidth * 0.5f
		val right = centerX + bannerWidth * 0.5f
		val top = centerY - height * 0.5f
		val bottom = centerY + height * 0.5f
		val radius = height * 0.34f
		val inset = height * 0.025f
		bannerPaint.alpha = (175f * opacity).roundToInt()
		trimPaint.alpha = (140f * opacity).roundToInt()
		trimPaint.strokeWidth = height * 0.018f
		sparklePaint.alpha = (240f * opacity).roundToInt()
		textPaint.alpha = (255f * opacity).roundToInt()
		canvas.drawRoundRect(left, top, right, bottom, radius, radius, bannerPaint)
		canvas.drawRoundRect(left + inset, top + inset, right - inset, bottom - inset, radius, radius, trimPaint)
		drawSparkle(canvas, centerX - glyphWidth * 0.5f - height * 0.34f, centerY, height * 0.13f)
		drawSparkle(canvas, centerX + glyphWidth * 0.5f + height * 0.34f, centerY, height * 0.13f)
		canvas.drawText(text, centerX, centerY + baselineOffset, textPaint)
	}

	private fun prepareTextLayout(width: Float, text: String, height: Float) {
		if (cachedText == text && cachedWidth == width && cachedHeight == height) {
			return
		}

		cachedText = text
		cachedWidth = width
		cachedHeight = height
		textPaint.textSize = height * 0.60f
		val maxTextWidth = width * 0.78f - height * 1.2f
		val measuredWidth = textPaint.measureText(text)
		if (measuredWidth > maxTextWidth && measuredWidth > 0f) {
			textPaint.textSize *= maxTextWidth / measuredWidth
		}

		glyphWidth = textPaint.measureText(text)
		bannerWidth = glyphWidth + height * 1.2f
		textPaint.getFontMetrics(metrics)
		baselineOffset = -(metrics.ascent + metrics.descent) * 0.5f
	}

	private fun drawSparkle(canvas: Canvas, x: Float, y: Float, radius: Float) {
		val saveCount = canvas.save()
		canvas.translate(x, y)
		canvas.scale(radius, radius)
		canvas.drawPath(sparklePath, sparklePaint)
		canvas.restoreToCount(saveCount)
	}
}
