package xyz.attacktive.weatherd.domain.render

import java.util.Locale
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateUtils

/** A compact viewport notice; text formatting and measurement happen only when its inputs change. */
internal class WeatherNoticeLayer(private val resources: Resources) {
	private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
		color = Color.WHITE
		textAlign = Paint.Align.CENTER
	}
	private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(205, 16, 22, 30) }
	private val bounds = RectF()
	private var title = ""
	private var updated: String? = null
	private var lastNotice: WeatherNotice? = null
	private var lastMinute = Long.MIN_VALUE
	private var lastWidth = 0
	private var lastHeight = 0
	private var lastLocale: Locale? = null
	private var lastFontScale = 0f
	private var lastOccupiedTop = Float.NaN
	private var lastOccupiedBottom = Float.NaN
	private var titleBaseline = 0f
	private var updatedBaseline = 0f
	private var radius = 0f

	fun draw(canvas: Canvas, width: Int, height: Int, notice: WeatherNotice?, occupiedTop: Float, occupiedBottom: Float, nowMillis: Long) {
		if (notice == null || width <= 0 || height <= 0) {
			return
		}

		val minute = nowMillis / DateUtils.MINUTE_IN_MILLIS
		val locale = resources.configuration.locales[0]
		val fontScale = resources.configuration.fontScale
		if (notice != lastNotice || minute != lastMinute || width != lastWidth || height != lastHeight || locale != lastLocale || fontScale != lastFontScale || occupiedTop != lastOccupiedTop || occupiedBottom != lastOccupiedBottom) {
			textPaint.textSize = minOf(width, height) * 0.035f * fontScale.coerceIn(1f, 2f)
			val padding = textPaint.textSize * 0.7f
			val availableWidth = (width * 0.9f - padding * 2f).coerceAtLeast(1f)
			title = TextUtils.ellipsize(weatherNoticeTitle(resources, notice), textPaint, availableWidth, TextUtils.TruncateAt.END).toString()
			updated = weatherNoticeUpdated(resources, notice, nowMillis)?.let {
				TextUtils.ellipsize(it, textPaint, availableWidth, TextUtils.TruncateAt.END).toString()
			}

			val lineHeight = textPaint.fontSpacing
			val lineCount = if (updated == null) {
				1
			} else {
				2
			}

			val boxHeight = lineHeight * lineCount + padding * 2f
			val boxWidth = maxOf(textPaint.measureText(title), updated?.let(textPaint::measureText) ?: 0f) + padding * 2f
			var top = height * 0.17f
			if (top < occupiedBottom + padding && top + boxHeight > occupiedTop - padding) {
				top = if (occupiedBottom + padding + boxHeight <= height * 0.9f) {
					occupiedBottom + padding
				} else {
					occupiedTop - padding - boxHeight
				}
			}

			bounds.set((width - boxWidth) / 2f, top, (width + boxWidth) / 2f, top + boxHeight)
			titleBaseline = top + padding - textPaint.ascent()
			updatedBaseline = titleBaseline + lineHeight
			radius = padding
			lastNotice = notice
			lastMinute = minute
			lastWidth = width
			lastHeight = height
			lastLocale = locale
			lastFontScale = fontScale
			lastOccupiedTop = occupiedTop
			lastOccupiedBottom = occupiedBottom
		}

		canvas.drawRoundRect(bounds, radius, radius, backgroundPaint)
		canvas.drawText(title, width / 2f, titleBaseline, textPaint)
		updated?.let { canvas.drawText(it, width / 2f, updatedBaseline, textPaint) }
	}
}
