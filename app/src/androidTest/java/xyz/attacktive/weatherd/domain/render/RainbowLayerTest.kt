package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase

@RunWith(AndroidJUnit4::class)
class RainbowLayerTest {
	@Test
	fun prismaticArcLeavesTheUpperSkyOpenInBothOrientations() {
		for ((width, height) in listOf(360 to 780, 780 to 360)) {
			val image = render(width = width, height = height)
			try {
				val centerY = height / 2
				val lower = pixels(image, centerY, height - centerY).maxOf(Color::alpha)
				val upper = pixels(image, 0, centerY).maxOf(Color::alpha)
				assertTrue("The lower prismatic arc must remain visible", lower >= 5)
				assertEquals("The partial arc must not close into a ring above the sun", 0, upper)
			} finally {
				image.recycle()
			}
		}
	}

	@Test
	fun spectralColorChangesAcrossTheBandRatherThanAroundAFlatColoredRing() {
		val image = render()
		try {
			val column = (image.height / 2 until image.height).map { y -> image.getPixel(image.width / 2, y) }
			val visible = column.withIndex().filter { Color.alpha(it.value) >= 5 }
			val cool = visible.minBy { Color.red(it.value) - Color.blue(it.value) }
			val warm = visible.maxBy { Color.red(it.value) - Color.blue(it.value) }
			assertTrue("The arc must separate cool inner and warm outer spectral bands", cool.index < warm.index)
			assertTrue("Inner spectral light must contain more blue than red", Color.blue(cool.value) > Color.red(cool.value))
			assertTrue("Outer spectral light must contain more red than blue", Color.red(warm.value) > Color.blue(warm.value))
		} finally {
			image.recycle()
		}
	}

	@Test
	fun spectralEdgesTintBlueSkyInsteadOfOnlyWhiteningIt() {
		val sky = Color.rgb(100, 160, 220)
		val image = createBitmap(360, 780)
		try {
			image.eraseColor(sky)
			RainbowLayer().draw(Canvas(image), image.width / 2f, image.height / 2f, DayPhase.DAY)
			val column = (image.height / 2 until image.height).map { y -> image.getPixel(image.width / 2, y) }
			val cool = column.withIndex().minBy { Color.red(it.value) - Color.blue(it.value) }
			val warm = column.withIndex().maxBy { Color.red(it.value) - Color.blue(it.value) }
			assertTrue("The cool edge must precede the warm edge against opaque sky", cool.index < warm.index)
			assertTrue("The inner edge must contribute blue", Color.blue(cool.value) > Color.blue(sky))
			assertTrue("The outer edge must contribute red", Color.red(warm.value) > Color.red(sky))
			assertTrue("The warm edge must reduce sky blue rather than lift all channels into haze", Color.blue(warm.value) < Color.blue(sky))
		} finally {
			image.recycle()
		}
	}

	@Test
	fun restrainedArcFadesWithSunVisibilityAndNeverBecomesOpaque() {
		val full = render()
		val half = render(visibility = 0.5f)
		try {
			val fullAlpha = pixels(full).maxOf(Color::alpha)
			val halfAlpha = pixels(half).maxOf(Color::alpha)
			assertTrue("Prismatic light must remain translucent", fullAlpha in 5..32)
			assertTrue("A fading sun must fade the whole arc", halfAlpha in 1 until fullAlpha)
			assertTrue("Half-visible sunlight must not retain full-strength optics", halfAlpha <= fullAlpha / 2 + 1)
		} finally {
			full.recycle()
			half.recycle()
		}
	}

	@Test
	fun nightAndFullyFadedSunLeaveNoPrismaticLight() {
		for ((phase, visibility) in listOf(DayPhase.NIGHT to 1f, DayPhase.DAY to 0f)) {
			val image = render(phase = phase, visibility = visibility)
			try {
				assertTrue("No sunlight means no rainbow optics", pixels(image).all { Color.alpha(it) == 0 })
			} finally {
				image.recycle()
			}
		}
	}

	@Test
	fun dawnAndDuskStayQuieterThanDaylight() {
		val strengths = listOf(DayPhase.DAY, DayPhase.DAWN, DayPhase.DUSK).map { phase ->
			val image = render(phase = phase)
			try {
				pixels(image).maxOf(Color::alpha)
			} finally {
				image.recycle()
			}
		}

		assertTrue("Twilight optics should recede rather than compete with the sky", strengths[0] > strengths[1] && strengths[1] > strengths[2])
	}

	@Test
	fun arcRadiusScalesProportionallyWithRainbowSizeScale() {
		for ((width, height) in listOf(360 to 780, 780 to 360)) {
			val small = render(width = width, height = height, sizeScale = 0.6f)
			val normal = render(width = width, height = height, sizeScale = 1.0f)
			val large = render(width = width, height = height, sizeScale = 1.4f)
			try {
				val centerX = width / 2
				val centerY = height / 2
				val smallRadius = peakAlphaRadius(small, centerX, centerY)
				val normalRadius = peakAlphaRadius(normal, centerX, centerY)
				val largeRadius = peakAlphaRadius(large, centerX, centerY)

				assertTrue("Small arc radius ($smallRadius) must be strictly less than normal ($normalRadius)", smallRadius < normalRadius)
				assertTrue("Large arc radius ($largeRadius) must be strictly greater than normal ($normalRadius)", largeRadius > normalRadius)

				val smallRatio = smallRadius.toFloat() / normalRadius
				val largeRatio = largeRadius.toFloat() / normalRadius
				assertTrue("0.6x scale ratio was $smallRatio", smallRatio in 0.55f..0.65f)
				assertTrue("1.4x scale ratio was $largeRatio", largeRatio in 1.35f..1.45f)
			} finally {
				small.recycle()
				normal.recycle()
				large.recycle()
			}
		}
	}

	@Test
	fun nearEdgeArcsAreClippedTranslationsWithoutRecenteringOrResizing() {
		for ((width, height) in listOf(360 to 780, 780 to 360)) {
			for (scale in listOf(0.6f, 1f, 1.4f)) {
				val reference = createBitmap(width, height).also {
					RainbowLayer().draw(Canvas(it), width / 2f, (height / 3).toFloat(), DayPhase.DAY, sizeScale = scale)
				}

				try {
					for ((centerX, centerY) in listOf(20 to 20, width - 20 to height - 120)) {
						val clipped = createBitmap(width, height)
						try {
							RainbowLayer().draw(Canvas(clipped), centerX.toFloat(), centerY.toFloat(), DayPhase.DAY, sizeScale = scale)
							val offsetX = centerX - width / 2
							val offsetY = centerY - height / 3
							var visiblePixels = 0
							for (y in 0 until height) {
								for (x in 0 until width) {
									val sourceX = x - offsetX
									val sourceY = y - offsetY
									val expected = if (sourceX in 0 until width && sourceY in 0 until height) {
										reference.getPixel(sourceX, sourceY)
									} else {
										Color.TRANSPARENT
									}

									assertEquals("Clipped arc at ($x, $y), scale $scale", Color.alpha(expected).toDouble(), Color.alpha(clipped.getPixel(x, y)).toDouble(), 1.0)
									if (Color.alpha(expected) > 0) {
										visiblePixels++
									}
								}
							}

							assertTrue("Boundary fixture must retain a visible arc", visiblePixels > 50)
						} finally {
							clipped.recycle()
						}
					}
				} finally {
					reference.recycle()
				}
			}
		}
	}

	private fun peakAlphaRadius(bitmap: Bitmap, centerX: Int, centerY: Int): Int {
		var maxAlpha = 0
		var peakRadius = 0
		for (y in centerY until bitmap.height) {
			val alpha = Color.alpha(bitmap.getPixel(centerX, y))
			if (alpha > maxAlpha) {
				maxAlpha = alpha
				peakRadius = y - centerY
			}
		}

		return peakRadius
	}

	private fun render(width: Int = 360, height: Int = 780, phase: DayPhase = DayPhase.DAY, visibility: Float = 1f, sizeScale: Float = 1f) = createBitmap(width, height).also {
		RainbowLayer().draw(Canvas(it), width / 2f, height / 2f, phase, visibility, sizeScale)
	}

	private fun pixels(image: Bitmap, top: Int = 0, height: Int = image.height) = IntArray(image.width * height).also {
		image.getPixels(it, 0, image.width, 0, top, image.width, height)
	}
}
