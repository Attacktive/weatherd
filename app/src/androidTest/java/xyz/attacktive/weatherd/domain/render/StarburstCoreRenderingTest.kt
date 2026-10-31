package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.R

@RunWith(AndroidJUnit4::class)
class StarburstCoreRenderingTest {
	@Test
	fun innerGlowStaysLocalAndHonorsRequestedOpacity() {
		val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
		val source = checkNotNull(
			BitmapFactory.decodeResource(
				resources,
				R.drawable.sun_starburst,
				BitmapFactory.Options().apply { inScaled = false }
			)
		)

		val layer = StarburstSunLayer(resources)
		layer.prewarm()
		try {
			for ((tint, alpha) in listOf(Color.WHITE to 255, Color.rgb(255, 228, 150) to 255, Color.rgb(255, 231, 202) to 128, Color.WHITE to 64, Color.WHITE to 0)) {
				assertLocalizedGlow(source, layer, tint, alpha)
			}
		} finally {
			source.recycle()
		}
	}

	private fun assertLocalizedGlow(source: Bitmap, layer: StarburstSunLayer, tint: Int, alpha: Int) {
		val width = source.width
		val height = source.height
		val before = createBitmap(width, height)
		val after = createBitmap(width, height)
		try {
			val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
				colorFilter = LightingColorFilter(paleBaselineTint(tint), 0)
				this.alpha = alpha
			}

			Canvas(before).drawBitmap(source, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
			layer.draw(Canvas(after), width * 0.5f, height * 0.5f, width * 0.1f, tint, alpha)
			val outerGuard = width * 112f / 800f
			val shoulderInner = width * 48f / 800f
			val shoulderOuter = width * 88f / 800f
			var shoulderLift = 0L
			var shoulderPixels = 0
			for (y in 0 until height) {
				val dy = y + 0.5f - height * 0.5f
				for (x in 0 until width) {
					val dx = x + 0.5f - width * 0.5f
					val distanceSquared = dx * dx + dy * dy
					val original = before.getPixel(x, y)
					val changed = after.getPixel(x, y)
					val changedAlpha = Color.alpha(changed)
					assertTrue("The single-pass glow must respect requested opacity $alpha at $x,$y", changedAlpha <= alpha)
					assertTrue("The core and existing light must not be dimmed", changedAlpha >= Color.alpha(original))
					if (distanceSquared >= outerGuard * outerGuard) {
						assertEquals("Outer halo and rays must remain pixel-identical at $x,$y", original, changed)
					}

					if (distanceSquared in shoulderInner * shoulderInner..shoulderOuter * shoulderOuter) {
						shoulderLift += changedAlpha - Color.alpha(original)
						shoulderPixels++
					}
				}
			}

			assertEquals("The solar center must retain its original opacity", Color.alpha(before.getPixel(width / 2, height / 2)), Color.alpha(after.getPixel(width / 2, height / 2)))
			if (alpha > 0) {
				assertTrue("The inner glow must become perceptibly stronger, including for white light; average alpha lift was ${shoulderLift.toFloat() / shoulderPixels}", shoulderLift.toFloat() / shoulderPixels >= alpha * 0.02f)
				val core = after.getPixel(width / 2, height / 2)
				assertTrue("The core must remain white rather than yellow", Color.red(core) >= 253 && Color.green(core) >= 253 && Color.blue(core) >= 253)
			}
		} finally {
			before.recycle()
			after.recycle()
		}
	}

	private fun paleBaselineTint(tint: Int) = Color.rgb(
		(255f - (255 - Color.red(tint)) * 0.15f).roundToInt(),
		(255f - (255 - Color.green(tint)) * 0.15f).roundToInt(),
		(255f - (255 - Color.blue(tint)) * 0.15f).roundToInt()
	)
}
