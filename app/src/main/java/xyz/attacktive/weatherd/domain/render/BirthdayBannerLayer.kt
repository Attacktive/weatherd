package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

internal class BirthdayBannerLayer {
	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		textAlign = Paint.Align.CENTER
		typeface = Typeface.DEFAULT_BOLD
		color = Color.rgb(255, 235, 177)
	}

	private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.rgb(32, 27, 57)
	}

	private val metrics = Paint.FontMetrics()
	private var cachedText = ""
	private var cachedWidth = 0f
	private var cachedHeight = 0f
	private var bannerWidth = 0f
	private var baselineOffset = 0f

	fun draw(canvas: Canvas, width: Float, text: String, frame: SeasonalCelebrationFrame) {
		val height = frame.birthdayGreetingHeight
		val opacity = frame.birthdayGreetingOpacity
		if (text.isEmpty() || height <= 0f || opacity <= 0f) {
			return
		}

		if (cachedText != text || cachedWidth != width || cachedHeight != height) {
			cachedText = text
			cachedWidth = width
			cachedHeight = height
			textPaint.textSize = height * 0.54f
			val maxTextWidth = width * 0.78f - height
			val measuredWidth = textPaint.measureText(text)
			if (measuredWidth > maxTextWidth && measuredWidth > 0f) {
				textPaint.textSize *= maxTextWidth / measuredWidth
			}

			bannerWidth = textPaint.measureText(text) + height
			textPaint.getFontMetrics(metrics)
			baselineOffset = -(metrics.ascent + metrics.descent) * 0.5f
		}

		val centerX = width * 0.5f
		val centerY = frame.birthdayGreetingY
		bannerPaint.alpha = (175f * opacity).roundToInt()
		textPaint.alpha = (255f * opacity).roundToInt()
		canvas.drawRoundRect(centerX - bannerWidth * 0.5f, centerY - height * 0.5f, centerX + bannerWidth * 0.5f, centerY + height * 0.5f, height * 0.25f, height * 0.25f, bannerPaint)
		canvas.drawText(text, centerX, centerY + baselineOffset, textPaint)
	}
}
