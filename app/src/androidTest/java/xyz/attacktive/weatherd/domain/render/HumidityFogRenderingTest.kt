package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BackdropScene
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

	@Test
	fun humidityTransitionsMatchFreshRenderer() {
		val reused = SceneRenderer(resources)
		val states = listOf(
			params(),
			params(humidityHazeDensity = 0.125f),
			params(humidityHazeDensity = 0.25f),
			params(humidityHazeDensity = 0.25f).copy(dayPhase = DayPhase.DUSK, celestialProgress = 0.6f),
			params()
		)

		for (state in states) {
			val reusedBitmap = renderComplete(reused, state)
			val freshBitmap = renderComplete(SceneRenderer(resources), state)

			assertBitmapsEqual(reusedBitmap, freshBitmap)
			reusedBitmap.recycle()
			freshBitmap.recycle()
		}

		val fog = params(fogDensity = 1f)
		val fogWithHumidity = fog.copy(humidityHazeDensity = 0.25f)
		val fogBitmap = renderComplete(SceneRenderer(resources), fog)
		val fogWithHumidityBitmap = renderComplete(SceneRenderer(resources), fogWithHumidity)

		assertBitmapsEqual(fogBitmap, fogWithHumidityBitmap)
		fogBitmap.recycle()
		fogWithHumidityBitmap.recycle()
	}

	@Test
	fun nightPhotoDimmingWinsOverHumidityHaze() {
		val renderer = SceneRenderer(resources)
		val photo = createBitmap(WIDTH, HEIGHT)
		photo.eraseColor(Color.WHITE)
		renderer.backgroundPhoto = photo
		val clearDay = params().copy(backdropScene = BackdropScene.PHOTO)
		val humidDay = clearDay.copy(humidityHazeDensity = 0.25f)
		val humidNight = humidDay.copy(
			dayPhase = DayPhase.NIGHT,
			nightBrightnessScale = 0f
		)
		val clearDayBitmap = renderBackdrop(renderer, clearDay)
		val humidDayBitmap = renderBackdrop(renderer, humidDay)
		val humidNightBitmap = renderBackdrop(renderer, humidNight)

		assertTrue(
			"Humidity haze must remain visible over a daylight photo backdrop",
			averageRgbDifference(clearDayBitmap, humidDayBitmap, HEIGHT / 2, HEIGHT) > 0f
		)

		for (y in intArrayOf(HEIGHT / 4, HEIGHT / 2, HEIGHT * 3 / 4)) {
			assertEquals(Color.BLACK, humidNightBitmap.getPixel(WIDTH / 2, y))
		}

		renderer.backgroundPhoto = null
		photo.recycle()
		clearDayBitmap.recycle()
		humidDayBitmap.recycle()
		humidNightBitmap.recycle()
	}

	private fun renderBackdrop(params: SceneParams) = renderBackdrop(SceneRenderer(resources), params)

	private fun renderBackdrop(renderer: SceneRenderer, params: SceneParams): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		renderer.renderBackdrop(Canvas(bitmap), WIDTH, HEIGHT, params)

		return bitmap
	}

	private fun renderForeground(params: SceneParams): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		SceneRenderer(resources).renderForeground(Canvas(bitmap), WIDTH, HEIGHT, params, TIME_SECONDS)

		return bitmap
	}

	private fun renderComplete(renderer: SceneRenderer, params: SceneParams): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		renderer.render(Canvas(bitmap), WIDTH, HEIGHT, params, TIME_SECONDS)

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

	private fun assertBitmapsEqual(expected: Bitmap, actual: Bitmap) {
		assertEquals(expected.width, actual.width)
		assertEquals(expected.height, actual.height)

		for (y in 0 until expected.height) {
			for (x in 0 until expected.width) {
				assertEquals("Pixel mismatch at ($x, $y)", expected.getPixel(x, y), actual.getPixel(x, y))
			}
		}
	}

	private companion object {
		const val WIDTH = 360
		const val HEIGHT = 780
		const val TIME_SECONDS = 11f
	}
}
