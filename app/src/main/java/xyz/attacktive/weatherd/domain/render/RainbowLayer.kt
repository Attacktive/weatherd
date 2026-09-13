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

/** Reuses decoded rainbow pixels, a sampling transform, and day-phase tint state between frames. */
internal class RainbowLayer(resources: Resources, @DrawableRes texture: Int) {
	private val bitmap = checkNotNull(BitmapFactory.decodeResource(resources, texture, BitmapFactory.Options().apply { inScaled = false }))
	private val transform = Matrix()
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private var previousTint = Color.WHITE

	fun draw(canvas: Canvas, width: Float, height: Float, dayPhase: DayPhase) {
		if (width <= 0f || height <= 0f || dayPhase == DayPhase.NIGHT) {
			return
		}

		// Keeps the bow's apex in the lower middle of every aspect ratio instead of pinning a portrait texture to the top edge.
		val scale = maxOf(width / bitmap.width, (height * 0.72f) / bitmap.height)
		val drawWidth = bitmap.width * scale
		val drawHeight = bitmap.height * scale
		val left = (width - drawWidth) / 2f
		val top = height * RAINBOW_APEX_HEIGHT - drawHeight * RAINBOW_TEXTURE_APEX_HEIGHT

		transform.setScale(scale, scale)
		transform.postTranslate(left, top)

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
		private const val RAINBOW_APEX_HEIGHT = 0.55f
		private const val RAINBOW_TEXTURE_APEX_HEIGHT = 0.35f

		/** Gentle atmospheric tinting during dawn and dusk to harmonize the rainbow with warm lighting. */
		internal fun rainbowTint(dayPhase: DayPhase) = when (dayPhase) {
			DayPhase.DAWN -> 0xFFFCECD8.toInt()
			DayPhase.DUSK -> 0xFFFFDECC.toInt()
			DayPhase.DAY -> Color.WHITE
			DayPhase.NIGHT -> Color.BLACK
		}
	}
}
