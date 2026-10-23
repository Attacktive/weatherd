package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import xyz.attacktive.weatherd.domain.model.DayPhase

/** One cached, feathered radial spectrum; the angular envelope leaves the sky above the sun open. */
internal class RainbowLayer {
	private val bitmap = renderImmutableBitmap(SPRITE_SIZE, SPRITE_SIZE) { canvas ->
		val center = SPRITE_SIZE / 2f
		val spectrum = RadialGradient(
			center,
			center,
			center,
			intArrayOf(
				Color.TRANSPARENT,
				Color.argb(0, 100, 120, 245),
				Color.rgb(100, 120, 245),
				Color.rgb(55, 180, 235),
				Color.rgb(80, 210, 130),
				Color.rgb(245, 195, 65),
				Color.rgb(245, 105, 90),
				Color.argb(0, 245, 105, 90),
				Color.TRANSPARENT
			),
			floatArrayOf(0f, 0.69f, 0.72f, 0.75f, 0.78f, 0.81f, 0.84f, 0.88f, 1f),
			Shader.TileMode.CLAMP
		)

		val envelope = SweepGradient(
			center,
			center,
			intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(200, 255, 255, 255), Color.WHITE, Color.argb(120, 255, 255, 255), Color.TRANSPARENT, Color.TRANSPARENT),
			floatArrayOf(0f, 0.03f, 0.15f, 0.25f, 0.40f, 0.50f, 1f)
		)

		val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			shader = ComposeShader(spectrum, envelope, PorterDuff.Mode.DST_IN)
		}

		canvas.drawCircle(center, center, center, brush)
	}

	private val coverage = SunSpriteCoverage(bitmap)
	private val destination = RectF()
	// Translucent color blending retains spectral separation against blue sky instead of whitening it.
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

	fun draw(canvas: Canvas, centerX: Float, centerY: Float, dayPhase: DayPhase, visibility: Float = 1f, sizeScale: Float = 1f) {
		if (canvas.width <= 0 || canvas.height <= 0 || dayPhase == DayPhase.NIGHT || !visibility.isFinite() || visibility <= 0f) {
			return
		}

		val reach = minOf(canvas.width, canvas.height) * ARC_RADIUS_FRACTION / SPECTRUM_RADIUS_FRACTION * sizeScale
		destination.set(centerX - reach, centerY - reach, centerX + reach, centerY + reach)
		paint.alpha = (255f * haloStrength(dayPhase) * visibility.coerceAtMost(1f)).roundToInt()
		coverage.draw(canvas, destination, paint)
	}

	private companion object {
		const val SPRITE_SIZE = 256
		const val ARC_RADIUS_FRACTION = 0.32f
		const val SPECTRUM_RADIUS_FRACTION = 0.78f

		fun haloStrength(dayPhase: DayPhase) = when (dayPhase) {
			DayPhase.DAY -> 0.12f
			DayPhase.DAWN -> 0.04f
			DayPhase.DUSK -> 0.015f
			DayPhase.NIGHT -> 0f
		}
	}
}
