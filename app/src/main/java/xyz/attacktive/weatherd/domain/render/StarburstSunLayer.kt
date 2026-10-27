package xyz.attacktive.weatherd.domain.render

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
	@Volatile
	private var orangeCore: SunCoreTint? = null
	val textureReady: Boolean
		get() = orangeCore != null

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

		val bitmap = checkNotNull(source)
		orangeCore = SunCoreTint(bitmap, bitmap.width / 2f, bitmap.height / 2f, bitmap.width * 0.05f, Color.rgb(255, 231, 202))
	}

	fun draw(canvas: Canvas, centerX: Float, centerY: Float, discRadius: Float, tint: Int, alpha: Int, brightenCore: Boolean) {
		val bitmap = source ?: return
		if (tint != previousTint) {
			paint.colorFilter = LightingColorFilter(tint, 0)
			previousTint = tint
		}

		// The approved bloom study has a roughly 40-unit core within a 400-unit half-width, with a wider luminous footprint than Classic.
		val reach = discRadius * 5f
		destination.set(centerX - reach, centerY - reach, centerX + reach, centerY + reach)
		val preparedCore = orangeCore
		if (brightenCore && preparedCore != null) {
			preparedCore.draw(canvas, destination, alpha.coerceIn(0, 255))
		} else {
			paint.alpha = alpha.coerceIn(0, 255)
			canvas.drawBitmap(bitmap, null, destination, paint)
		}
	}

	private companion object {
		@Volatile
		var source: Bitmap? = null
	}
}
