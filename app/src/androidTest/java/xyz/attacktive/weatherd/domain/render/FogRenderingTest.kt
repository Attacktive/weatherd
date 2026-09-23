package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase

@RunWith(AndroidJUnit4::class)
class FogRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun fogBackdropKeepsMoreAirInTheUpperSkyThanNearTheGround() {
		val clear = renderBackdrop(fogDensity = 0f)
		val fog = renderBackdrop(fogDensity = 1f)
		val upperDifference = averageRgbDifference(clear, fog, 0, HEIGHT / 4)
		val lowerDifference = averageRgbDifference(clear, fog, HEIGHT * 3 / 4, HEIGHT)

		assertTrue(
			"Fog must stay restrained in the upper sky and become substantially denser near the ground, but upper difference was $upperDifference and lower difference was $lowerDifference",
			lowerDifference > upperDifference * 2f
		)
		clear.recycle()
		fog.recycle()
	}

	@Test
	fun fogVeilsDriftAcrossMostOfThePortraitViewport() {
		val first = renderForeground(timeSeconds = 7f)
		val second = renderForeground(timeSeconds = 19f)
		val bounds = differenceBounds(first, second)

		assertTrue(
			"Moving fog veils must span most of the portrait width, but their changed-pixel span was ${bounds.width}px",
			bounds.width >= (WIDTH * 0.85f).roundToInt()
		)
		assertTrue(
			"Moving fog veils must reach through the lower scene instead of hovering as one upper band, but their lower edge was ${bounds.bottom}",
			bounds.bottom >= (HEIGHT * 0.75f).roundToInt()
		)
		first.recycle()
		second.recycle()
	}

	private fun renderBackdrop(fogDensity: Float): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		SceneRenderer(resources).renderBackdrop(Canvas(bitmap), WIDTH, HEIGHT, params(fogDensity))

		return bitmap
	}

	private fun renderForeground(timeSeconds: Float): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		SceneRenderer(resources).renderForeground(Canvas(bitmap), WIDTH, HEIGHT, params(fogDensity = 1f), timeSeconds)

		return bitmap
	}

	private fun params(fogDensity: Float) = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = fogDensity,
		precipitation = null,
		thunder = false,
		windFactor = 0.25f,
		sunVisible = false
	)

	private fun averageRgbDifference(first: Bitmap, second: Bitmap, top: Int, bottom: Int): Float {
		var difference = 0L
		var samples = 0

		for (y in top until bottom) {
			for (x in 0 until first.width) {
				val firstPixel = first.getPixel(x, y)
				val secondPixel = second.getPixel(x, y)
				difference += abs(Color.red(firstPixel) - Color.red(secondPixel))
				difference += abs(Color.green(firstPixel) - Color.green(secondPixel))
				difference += abs(Color.blue(firstPixel) - Color.blue(secondPixel))
				samples += 3
			}
		}

		return difference.toFloat() / samples
	}

	private fun differenceBounds(first: Bitmap, second: Bitmap): PixelBounds {
		var left = first.width
		var right = -1
		var bottom = -1

		for (y in 0 until first.height) {
			for (x in 0 until first.width) {
				if (first.getPixel(x, y) != second.getPixel(x, y)) {
					left = minOf(left, x)
					right = maxOf(right, x)
					bottom = maxOf(bottom, y)
				}
			}
		}

		return PixelBounds(left, right, bottom)
	}

	private data class PixelBounds(val left: Int, val right: Int, val bottom: Int) {
		val width
			get() = if (right < left) 0 else right - left + 1
	}

	private companion object {
		const val WIDTH = 360
		const val HEIGHT = 780
	}
}
