package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
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
class HumidityFogRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun humidityHazeIsRestrainedComparedWithReportedFog() {
		val clear = renderBackdrop(params())
		val haze = renderBackdrop(params(humidityHazeDensity = 0.25f))
		val fog = renderBackdrop(params(fogDensity = 1f))
		val hazeUpperDifference = averageRgbDifference(clear, haze, 0, HEIGHT / 4)
		val hazeLowerDifference = averageRgbDifference(clear, haze, HEIGHT * 3 / 4, HEIGHT)
		val fogLowerDifference = averageRgbDifference(clear, fog, HEIGHT * 3 / 4, HEIGHT)

		assertTrue("Humidity haze must remain visible near the ground", hazeLowerDifference > 0f)
		assertTrue(
			"Humidity haze must stay materially lighter than reported fog, but haze difference was $hazeLowerDifference and fog difference was $fogLowerDifference",
			hazeLowerDifference < fogLowerDifference
		)

		assertTrue(
			"Humidity haze must influence the lower scene more than the upper sky, but upper difference was $hazeUpperDifference and lower difference was $hazeLowerDifference",
			hazeLowerDifference > hazeUpperDifference
		)

		clear.recycle()
		haze.recycle()
		fog.recycle()
	}

	@Test
	fun humidityHazeForegroundRemainsTranslucent() {
		val clear = renderForeground(params())
		val haze = renderForeground(params(humidityHazeDensity = 0.25f))
		val fog = renderForeground(params(fogDensity = 1f))
		val top = HEIGHT * 3 / 4
		val clearAlpha = integratedAlpha(clear, top, HEIGHT)
		val hazeAlpha = integratedAlpha(haze, top, HEIGHT)
		val fogAlpha = integratedAlpha(fog, top, HEIGHT)

		assertTrue("Humidity haze must add moving foreground coverage", hazeAlpha > clearAlpha)
		assertTrue("Humidity haze foreground must remain lighter than reported fog", hazeAlpha < fogAlpha)
		assertTrue("Humidity haze foreground must remain translucent", maximumAlpha(haze, top, HEIGHT) < 255)

		clear.recycle()
		haze.recycle()
		fog.recycle()
	}

	private fun renderBackdrop(params: SceneParams): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		SceneRenderer(resources).renderBackdrop(Canvas(bitmap), WIDTH, HEIGHT, params)

		return bitmap
	}

	private fun renderForeground(params: SceneParams): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		SceneRenderer(resources).renderForeground(Canvas(bitmap), WIDTH, HEIGHT, params, TIME_SECONDS)

		return bitmap
	}

	private fun params(fogDensity: Float = 0f, humidityHazeDensity: Float = 0f) = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = fogDensity,
		precipitation = null,
		thunder = false,
		windFactor = 0.25f,
		sunVisible = false,
		moonVisible = false,
		humidityHazeDensity = humidityHazeDensity
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

	private fun integratedAlpha(bitmap: Bitmap, top: Int, bottom: Int): Long {
		var alpha = 0L

		for (y in top until bottom) {
			for (x in 0 until bitmap.width) {
				alpha += Color.alpha(bitmap.getPixel(x, y))
			}
		}

		return alpha
	}

	private fun maximumAlpha(bitmap: Bitmap, top: Int, bottom: Int): Int {
		var maximum = 0

		for (y in top until bottom) {
			for (x in 0 until bitmap.width) {
				maximum = maxOf(maximum, Color.alpha(bitmap.getPixel(x, y)))
			}
		}

		return maximum
	}

	private companion object {
		const val WIDTH = 360
		const val HEIGHT = 780
		const val TIME_SECONDS = 11f
	}
}
