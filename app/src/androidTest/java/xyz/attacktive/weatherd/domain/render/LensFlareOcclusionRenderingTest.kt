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
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.SunbeamMode

@RunWith(AndroidJUnit4::class)
class LensFlareOcclusionRenderingTest {
	@Test
	fun cloudsCrossingTheSunFadeOpticsEvenWithSunbeamsOff() {
		val renderer = SceneRenderer(InstrumentationRegistry.getInstrumentation().targetContext.resources, epochDay = 20743L)
		renderer.prewarmCloudTextures()
		val params = SceneParams(
			dayPhase = DayPhase.DAY,
			celestialProgress = 0.2f,
			cloudiness = 0.5f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0.2f,
			sunbeamMode = SunbeamMode.OFF,
			distantAircraftEnabled = false,
			birdFlockSize = BirdFlockSize.OFF
		)

		val centerX = (WIDTH * 0.72f).roundToInt()
		val centerY = (HEIGHT * celestialHeightFraction(DayPhase.DAY, params.celestialProgress)).roundToInt()
		var openPeak = 0
		var blockedPeak = 0
		var openFrames = 0
		var blockedFrames = 0
		for (second in 0..720 step 15) {
			val clouds = render(renderer, params.copy(sunVisible = false), second.toFloat())
			val active = render(renderer, params, second.toFloat())
			val off = render(renderer, params.copy(lensFlareEnabled = false), second.toFloat())
			try {
				val obstruction = Color.alpha(clouds.getPixel(centerX, centerY))
				val energy = unoccludedOpticalPeak(active, off, clouds)
				if (obstruction <= 3) {
					openFrames++
					openPeak = maxOf(openPeak, energy)
				}

				if (obstruction >= 230) {
					blockedFrames++
					blockedPeak = maxOf(blockedPeak, energy)
				}
			} finally {
				clouds.recycle()
				active.recycle()
				off.recycle()
			}
		}

		assertTrue("Pinned cloud drift must exercise both open and obscured sunlight: $openFrames/$blockedFrames", openFrames > 0 && blockedFrames > 0)
		assertTrue("An open sun must produce visible optical reflections", openPeak >= 10)
		assertTrue("A cloud over the sun must fade reflections even over clear destination pixels: $openPeak -> $blockedPeak", blockedPeak < openPeak / 3)
	}

	private fun unoccludedOpticalPeak(active: Bitmap, off: Bitmap, clouds: Bitmap): Int {
		var peak = 0
		for (y in 430 until 560) {
			for (x in 90 until 220) {
				if (Color.alpha(clouds.getPixel(x, y)) == 0) {
					peak = maxOf(peak, abs(Color.alpha(active.getPixel(x, y)) - Color.alpha(off.getPixel(x, y))))
				}
			}
		}

		return peak
	}

	private fun render(renderer: SceneRenderer, params: SceneParams, time: Float): Bitmap {
		val image = createBitmap(WIDTH, HEIGHT)
		renderer.renderForeground(Canvas(image), WIDTH, HEIGHT, params, time)
		return image
	}

	private companion object {
		const val WIDTH = 360
		const val HEIGHT = 780
	}
}
