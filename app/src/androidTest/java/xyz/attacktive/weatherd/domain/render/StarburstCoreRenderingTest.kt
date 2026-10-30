package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
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
	fun coreWhiteningKeepsSourceOpacityAndEveryOuterPixel() {
		val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
		val source = checkNotNull(BitmapFactory.decodeResource(
			resources,
			R.drawable.sun_starburst,
			BitmapFactory.Options().apply { inScaled = false }
		))

		val layer = StarburstSunLayer(resources)
		layer.prewarm()

		val width = source.width
		val height = source.height
		val centerX = width * 0.5f
		val centerY = height * 0.5f
		val discRadius = width * 0.1f
		val outerGuardRadiusSquared = (width * 56f / 800f).let { it * it }

		try {
			val tintAndAlphaCases = listOf(
				Color.rgb(255, 228, 150) to 255,
				Color.rgb(255, 231, 202) to 128,
				Color.WHITE to 255
			)

			for ((tint, alpha) in tintAndAlphaCases) {
				val before = createBitmap(width, height)
				val after = createBitmap(width, height)
				try {
					val oldTint = paleBaselineTint(tint)
					val oldPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
						colorFilter = LightingColorFilter(oldTint, 0)
						this.alpha = alpha
					}

					Canvas(before).drawBitmap(source, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), oldPaint)
					layer.draw(Canvas(after), centerX, centerY, discRadius, tint, alpha)

					for (y in 0 until height) {
						val dy = y + 0.5f - centerY
						for (x in 0 until width) {
							val original = before.getPixel(x, y)
							val changed = after.getPixel(x, y)
							if (Color.alpha(original) != Color.alpha(changed)) {
								assertEquals("Core adjustment must preserve source opacity", Color.alpha(original), Color.alpha(changed))
							}

							val dx = x + 0.5f - centerX
							if (dx * dx + dy * dy >= outerGuardRadiusSquared && original != changed) {
								assertEquals("The outer glow and rays must remain pixel-identical", original, changed)
							}
						}
					}

					val beforeCore = before.getPixel(width / 2, height / 2)
					val afterCore = after.getPixel(width / 2, height / 2)
					if (tint == Color.WHITE) {
						assertTrue("An already-white source must not change", before.sameAs(after))
					} else {
						assertTrue("Warm Starburst cores must become visibly whiter", Color.blue(afterCore) > Color.blue(beforeCore))
						assertEquals("The compact core must reach pure white", 255, Color.blue(afterCore))
					}
				} finally {
					before.recycle()
					after.recycle()
				}
			}
		} finally {
			source.recycle()
		}
	}

	private fun paleBaselineTint(tint: Int) = Color.rgb(
		(255f - (255 - Color.red(tint)) * 0.15f).roundToInt(),
		(255f - (255 - Color.green(tint)) * 0.15f).roundToInt(),
		(255f - (255 - Color.blue(tint)) * 0.15f).roundToInt()
	)
}
