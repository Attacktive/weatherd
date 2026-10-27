package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.RectF
import xyz.attacktive.weatherd.R

/** Original offline-generated sun artwork; decoding happens only during explicit background prewarming. */
internal class StarburstSunLayer(private val resources: Resources) {
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val destination = RectF()
	private var previousTint = 0
	val textureReady: Boolean
		get() = source != null

	fun prewarm() {
		if (textureReady) {
			return
		}

		synchronized(StarburstSunLayer::class.java) {
			if (source == null) {
				source = checkNotNull(
					BitmapFactory.decodeResource(
						resources,
						R.drawable.sun_starburst,
						BitmapFactory.Options().apply { inScaled = false }
					)
				)
			}
		}
	}

	fun draw(canvas: Canvas, centerX: Float, centerY: Float, discRadius: Float, tint: Int, alpha: Int) {
		val bitmap = source ?: return
		if (tint != previousTint) {
			paint.colorFilter = LightingColorFilter(paleTint(tint), 0)
			previousTint = tint
		}

		// The approved bloom study has a roughly 40-unit core within a 400-unit half-width, with a wider luminous footprint than Classic.
		val reach = discRadius * 5f
		destination.set(centerX - reach, centerY - reach, centerX + reach, centerY + reach)
		paint.alpha = alpha.coerceIn(0, 255)
		canvas.drawBitmap(bitmap, null, destination, paint)
	}

	/** Keeps the selected hue as a subtle warmth instead of tinting the whole light source yellow. */
	private fun paleTint(tint: Int) = Color.rgb(
		(255f - (255 - Color.red(tint)) * TINT_WARMTH).roundToInt(),
		(255f - (255 - Color.green(tint)) * TINT_WARMTH).roundToInt(),
		(255f - (255 - Color.blue(tint)) * TINT_WARMTH).roundToInt()
	)

	private companion object {
		const val TINT_WARMTH = 0.15f

		@Volatile
		var source: Bitmap? = null
	}
}
