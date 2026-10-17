package xyz.attacktive.weatherd.domain.render

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
			paint.colorFilter = LightingColorFilter(tint, 0)
			previousTint = tint
		}

		// The generator's opaque core occupies 0.1 of the half-width; match Classic's compact core, not its full bloom.
		val reach = discRadius * 0.22f / 0.1f
		destination.set(centerX - reach, centerY - reach, centerX + reach, centerY + reach)
		paint.alpha = alpha.coerceIn(0, 255)
		canvas.drawBitmap(bitmap, null, destination, paint)
	}

	private companion object {
		@Volatile
		var source: Bitmap? = null
	}
}
