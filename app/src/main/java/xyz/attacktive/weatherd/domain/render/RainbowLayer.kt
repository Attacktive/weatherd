package xyz.attacktive.weatherd.domain.render

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.annotation.DrawableRes
import xyz.attacktive.weatherd.domain.model.DayPhase

/** Reuses decoded chromatic halo pixels, a sampling transform, and day-phase tint state between frames. */
internal class RainbowLayer(resources: Resources, @DrawableRes texture: Int) {
	private val bitmap = checkNotNull(BitmapFactory.decodeResource(resources, texture, BitmapFactory.Options().apply { inScaled = false }))
	private val transform = Matrix()
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private var previousTint = Color.WHITE

	fun draw(canvas: Canvas, width: Float, height: Float, centerX: Float, centerY: Float, dayPhase: DayPhase) {
		if (width <= 0f || height <= 0f || dayPhase == DayPhase.NIGHT) {
			return
		}

		val targetRadius = minOf(width, height) * HALO_RADIUS_FRACTION
		val scale = targetRadius / (bitmap.width * TEXTURE_HALO_RADIUS_FRACTION)
		val drawWidth = bitmap.width * scale
		val drawHeight = bitmap.height * scale
		val left = centerX - drawWidth / 2f
		val top = centerY - drawHeight / 2f

		transform.setScale(scale, scale)
		transform.postTranslate(left, top)

		paint.alpha = haloAlpha(dayPhase)

		val tint = rainbowTint(dayPhase)
		if (tint != previousTint) {
			paint.colorFilter = if (tint == Color.WHITE) {
				null
			} else {
				LightingColorFilter(tint, Color.BLACK)
			}

			previousTint = tint
		}

		canvas.drawBitmap(bitmap, transform, paint)
	}

	companion object {
		private const val HALO_RADIUS_FRACTION = 0.145f
		private const val TEXTURE_HALO_RADIUS_FRACTION = 0.34f

		private fun haloAlpha(dayPhase: DayPhase) = when (dayPhase) {
			DayPhase.DAY -> 110
			DayPhase.DAWN -> 96
			DayPhase.DUSK -> 88
			DayPhase.NIGHT -> 0
		}

		/** Gentle atmospheric tinting during dawn and dusk to harmonize the rainbow with warm lighting. */
		internal fun rainbowTint(dayPhase: DayPhase) = when (dayPhase) {
			DayPhase.DAWN -> 0xFFFFF4EA.toInt()
			DayPhase.DUSK -> 0xFFFFEADD.toInt()
			DayPhase.DAY -> Color.WHITE
			DayPhase.NIGHT -> Color.BLACK
		}
	}
}
