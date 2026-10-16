package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BirdAppearanceInterval
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

@RunWith(AndroidJUnit4::class)
class BirdRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun selectedFlockSizesDrawDistinctBirdsInPortraitAndLandscape() {
		for ((width, height) in listOf(360 to 780, 780 to 360)) {
			for ((size, expected) in listOf(BirdFlockSize.SMALL to 3..5, BirdFlockSize.MEDIUM to 6..8, BirdFlockSize.LARGE to 9..12)) {
				val count = birdSilhouettes(clearParams().copy(birdFlockSize = size), 11f, width, height)
				assertTrue("$size must draw $expected complete birds at ${width}x$height, got $count", count in expected)
			}
		}
	}

	@Test
	fun configuredCrossingsFinishOffscreenAndRepeatAfterTheQuietGap() {
		val params = clearParams().copy(birdFlockSize = BirdFlockSize.LARGE)
		assertTrue(birdSilhouettes(params, 11f) in 9..12)
		assertEquals("The full flock must have left the viewport before its crossing ends", 0, birdSilhouettes(params, 22f))
		assertEquals(0, birdSilhouettes(params, 29.99f))
		assertTrue(birdSilhouettes(params, 41f) in 9..12)
		assertEquals(0, birdSilhouettes(params.copy(birdAppearanceInterval = BirdAppearanceInterval.MINUTES_5), 41f))
	}

	@Test
	fun birdControlsDoNotOverrideNightFogRainOrOpaqueClouds() {
		val clear = clearParams()
		val hiddenScenes = listOf(
			clear.copy(birdFlockSize = BirdFlockSize.OFF),
			clear.copy(dayPhase = DayPhase.NIGHT),
			clear.copy(fogDensity = 0.4f),
			clear.copy(precipitation = Precipitation(PrecipitationKind.RAIN, severity = 0.5f, observed = 0.5f)),
			clear.copy(cloudiness = 0.55f)
		)

		for (params in hiddenScenes) {
			assertEquals("Bird preferences must respect scene eligibility: $params", 0, birdSilhouettes(params, 11f))
		}
	}

	private fun birdSilhouettes(params: SceneParams, timeSeconds: Float, width: Int = 360, height: Int = 780): Int {
		val actual = render(params, timeSeconds, width, height)
		val control = render(params.copy(birdFlockSize = BirdFlockSize.OFF), timeSeconds, width, height)
		var silhouettes = 0
		var previousColumnChanged = false
		for (x in 0 until width) {
			var changed = false
			for (y in 0 until height) {
				if (actual.getPixel(x, y) != control.getPixel(x, y)) {
					changed = true
					break
				}
			}

			if (changed && !previousColumnChanged) {
				silhouettes++
			}

			previousColumnChanged = changed
		}

		actual.recycle()
		control.recycle()

		return silhouettes
	}

	private fun render(params: SceneParams, timeSeconds: Float, width: Int, height: Int): Bitmap {
		val bitmap = createBitmap(width, height)
		SceneRenderer(resources).renderForeground(Canvas(bitmap), width, height, params, timeSeconds)

		return bitmap
	}

	private fun clearParams() = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f,
		sunVisible = false,
		moonVisible = false,
		distantAircraftEnabled = false,
		birdAppearanceInterval = BirdAppearanceInterval.SECONDS_30
	)
}
