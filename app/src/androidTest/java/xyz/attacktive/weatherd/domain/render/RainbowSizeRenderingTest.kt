package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.SunbeamMode
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STEADY

@RunWith(AndroidJUnit4::class)
class RainbowSizeRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun sizeMovesOnlyTheArcWhileSunGhostsStreakCoronaAndBeamsStayFixed() {
		for ((width, height) in listOf(360 to 780, 780 to 360)) {
			val params = clearParams()
			val small = renderForeground(width, height, params.copy(rainbowSizeScale = 0.6f))
			val large = renderForeground(width, height, params.copy(rainbowSizeScale = 1.4f))
			val smallMask = arcMask(width, height, 0.6f)
			val largeMask = arcMask(width, height, 1.4f)
			try {
				val centerX = (width * 0.72f).roundToInt()
				val centerY = (height * 0.17f).roundToInt()
				// With a 360-pixel short side, the selected radii are about 69 and 161 pixels.
				assertTrue("60% must brighten the inner band instead of the 140% band", Color.alpha(small.getPixel(centerX, centerY + 69)) > Color.alpha(large.getPixel(centerX, centerY + 69)) + 3)
				assertTrue("140% must move the arc outward", Color.alpha(large.getPixel(centerX, centerY + 161)) > Color.alpha(small.getPixel(centerX, centerY + 161)) + 3)
				for (y in 0 until height) {
					for (x in 0 until width) {
						if (Color.alpha(smallMask.getPixel(x, y)) == 0 && Color.alpha(largeMask.getPixel(x, y)) == 0) {
							assertEquals("Optics outside the arc at ($x, $y), $width x $height", small.getPixel(x, y), large.getPixel(x, y))
						}
					}
				}
			} finally {
				small.recycle()
				large.recycle()
				smallMask.recycle()
				largeMask.recycle()
			}
		}
	}

	@Test
	fun weatherGatesAndLensOffSuppressSizeEffects() {
		val suppressedScenes = listOf(
			"lens_off" to clearParams().copy(lensFlareEnabled = false),
			"hidden_sun" to clearParams().copy(sunVisible = false),
			"rain" to clearParams().copy(precipitation = Precipitation(kind = PrecipitationKind.RAIN, severity = SEVERITY_STEADY, observed = 0.5f)),
			"fog" to clearParams().copy(fogDensity = 1f),
			"thunder" to clearParams().copy(thunder = true),
			"night" to clearParams().copy(dayPhase = DayPhase.NIGHT),
			"heavy_cloud" to clearParams().copy(cloudiness = 0.95f)
		)

		for ((name, scene) in suppressedScenes) {
			val small = renderForeground(360, 780, scene.copy(rainbowSizeScale = 0.6f))
			val large = renderForeground(360, 780, scene.copy(rainbowSizeScale = 1.4f))
			try {
				assertArrayEquals("Suppressed rainbow state ($name) must ignore size", pixels(small), pixels(large))
			} finally {
				small.recycle()
				large.recycle()
			}
		}
	}

	private fun clearParams() = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f,
		celestialProgress = 0.5f,
		sunbeamMode = SunbeamMode.CLEAR_SKIES_TOO,
		distantAircraftEnabled = false,
		birdFlockSize = BirdFlockSize.OFF
	)

	private fun arcMask(width: Int, height: Int, scale: Float) = createBitmap(width, height).also {
		RainbowLayer().draw(Canvas(it), width * 0.72f, height * celestialHeightFraction(DayPhase.DAY, 0.5f), DayPhase.DAY, sizeScale = scale)
	}

	private fun renderForeground(width: Int, height: Int, params: SceneParams) = createBitmap(width, height).also {
		SceneRenderer(resources).renderForeground(Canvas(it), width, height, params, 0f)
	}

	private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
		bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
	}
}
