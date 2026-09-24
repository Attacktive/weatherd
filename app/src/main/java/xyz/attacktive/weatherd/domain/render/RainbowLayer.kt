package xyz.attacktive.weatherd.domain.render

import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.annotation.DrawableRes
import kotlin.math.roundToInt
import xyz.attacktive.weatherd.domain.model.DayPhase

/** Reuses decoded chromatic halo pixels, a sampling transform, and day-phase tint state between frames. */
internal class RainbowLayer(resources: Resources, @DrawableRes texture: Int) {
	private val bitmap = checkNotNull(BitmapFactory.decodeResource(resources, texture, BitmapFactory.Options().apply { inScaled = false }))
	private val transform = Matrix()
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private var previousTint = Color.WHITE

	fun draw(canvas: Canvas, centerX: Float, centerY: Float, dayPhase: DayPhase, visibility: Float = 1f) {
		if (canvas.width <= 0 || canvas.height <= 0 || dayPhase == DayPhase.NIGHT) {
			return
		}

		val targetRadius = minOf(canvas.width, canvas.height) * HALO_RADIUS_FRACTION
		val scale = targetRadius / (bitmap.width * TEXTURE_HALO_RADIUS_FRACTION)
		val drawWidth = bitmap.width * scale
		val drawHeight = bitmap.height * scale
		val left = centerX - drawWidth / 2f
		val top = centerY - drawHeight / 2f

		transform.setScale(scale, scale)
		transform.postTranslate(left, top)
		paint.alpha = (255f * haloStrength(dayPhase) * visibility).roundToInt().coerceIn(0, 255)

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
		private const val HALO_RADIUS_FRACTION = 0.34f
		private const val TEXTURE_HALO_RADIUS_FRACTION = 0.34f

		private fun haloStrength(dayPhase: DayPhase) = when (dayPhase) {
			DayPhase.DAY -> 0.38f
			DayPhase.DAWN -> 0.07f
			DayPhase.DUSK -> 0.028f
			DayPhase.NIGHT -> 0f
		}

		/** Gentle atmospheric tinting during dawn and dusk to harmonize the rainbow with warm lighting. */
		internal fun rainbowTint(dayPhase: DayPhase) = when (dayPhase) {
			DayPhase.DAWN -> 0xFFFCECD8.toInt()
			DayPhase.DUSK -> 0xFFFFDECC.toInt()
			DayPhase.DAY -> Color.WHITE
			DayPhase.NIGHT -> Color.BLACK
		}
	}
}
