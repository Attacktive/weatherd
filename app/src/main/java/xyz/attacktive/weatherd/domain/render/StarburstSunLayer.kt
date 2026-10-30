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
						BitmapFactory.Options().apply {
							inScaled = false
							inMutable = true
						}
					)
				)
				.also(::prepareCoreLight)
			}
		}
	}

	fun draw(canvas: Canvas, centerX: Float, centerY: Float, discRadius: Float, tint: Int, alpha: Int) {
		val bitmap = source ?: return
		if (tint != previousTint) {
			paint.colorFilter = coreWhiteningFilter(tint)
			previousTint = tint
		}

		// The approved bloom study has a roughly 40-unit core within a 400-unit half-width, with a wider luminous footprint than Classic.
		val reach = discRadius * 5f
		destination.set(centerX - reach, centerY - reach, centerX + reach, centerY + reach)
		paint.alpha = alpha.coerceIn(0, 255)
		canvas.drawBitmap(bitmap, null, destination, paint)
	}

	/**
	 * Prepare a white core and a restrained inner glow once, without changing the outer artwork.
	 * Keeping the lift inside the source alpha lets the single draw honor user opacity and sunset fade.
	 */
	private fun prepareCoreLight(bitmap: Bitmap) {
		val width = bitmap.width
		val height = bitmap.height
		val pixels = IntArray(width * height)
		bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

		val centerX = (width - 1) * 0.5f
		val centerY = (height - 1) * 0.5f
		val innerRadius = width * CORE_INNER_RADIUS_FRACTION
		val outerRadius = width * CORE_OUTER_RADIUS_FRACTION
		val innerRadiusSquared = innerRadius * innerRadius
		val outerRadiusSquared = outerRadius * outerRadius
		val glowRadius = width * INNER_GLOW_RADIUS_FRACTION
		val glowRadiusSquared = glowRadius * glowRadius

		for (y in 0 until height) {
			val dy = y - centerY
			for (x in 0 until width) {
				val dx = x - centerX
				val distanceSquared = dx * dx + dy * dy
				val weight = ((outerRadiusSquared - distanceSquared) / (outerRadiusSquared - innerRadiusSquared)).coerceIn(0f, 1f)
				val smoothWeight = weight * weight * (3f - 2f * weight)
				val white = (smoothWeight * 255f).roundToInt()
				val index = y * width + x
				val originalAlpha = Color.alpha(pixels[index])
				val glowWeight = (1f - distanceSquared / glowRadiusSquared).coerceIn(0f, 1f)
				val smoothGlowWeight = glowWeight * glowWeight * (3f - 2f * glowWeight)
				// Lift translucent inner light, not the saturated core; zero alpha and the outer rays stay unchanged.
				val glowLift = originalAlpha * (1f - originalAlpha / 255f) * INNER_GLOW_STRENGTH * smoothGlowWeight
				val alpha = (originalAlpha + glowLift).roundToInt().coerceIn(0, 255)
				pixels[index] = (alpha shl 24) or (white * 0x010101)
			}
		}

		bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
	}

	/** The unmodified outer RGB maps to its former tint; only the core approaches white. */
	private fun coreWhiteningFilter(tint: Int): LightingColorFilter {
		val base = paleTint(tint)
		val coreChannelLift = Color.rgb(255 - Color.red(base), 255 - Color.green(base), 255 - Color.blue(base))

		return LightingColorFilter(coreChannelLift, base)
	}

	/** Keeps the selected hue as a subtle warmth instead of tinting the whole light source yellow. */
	private fun paleTint(tint: Int) = Color.rgb(
		(255f - (255 - Color.red(tint)) * TINT_WARMTH).roundToInt(),
		(255f - (255 - Color.green(tint)) * TINT_WARMTH).roundToInt(),
		(255f - (255 - Color.blue(tint)) * TINT_WARMTH).roundToInt()
	)

	private companion object {
		const val TINT_WARMTH = 0.15f
		// The source artwork spans 800 reference units; the existing bright core is about 40.
		const val CORE_INNER_RADIUS_FRACTION = 20f / 800f
		const val CORE_OUTER_RADIUS_FRACTION = 52f / 800f
		const val INNER_GLOW_RADIUS_FRACTION = 110f / 800f
		const val INNER_GLOW_STRENGTH = 0.5f

		@Volatile
		var source: Bitmap? = null
	}
}
