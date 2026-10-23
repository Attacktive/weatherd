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

	private fun render(width: Int = 360, height: Int = 780, phase: DayPhase = DayPhase.DAY, visibility: Float = 1f): Bitmap {
		val image = createBitmap(width, height)
		RainbowLayer().draw(Canvas(image), width / 2f, height / 2f, phase, visibility)
		return image
	}

	private fun pixels(image: Bitmap, top: Int = 0, height: Int = image.height) = IntArray(image.width * height).also {
		image.getPixels(it, 0, image.width, 0, top, image.width, height)
	}
}
