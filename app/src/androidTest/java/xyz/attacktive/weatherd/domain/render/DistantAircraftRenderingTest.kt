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
class DistantAircraftRenderingTest {
	@Test
	fun aircraftAndContrailLifecycle() {
		val params = fairWeatherParams()
		val passStart = findPassStart()
		val layer = DistantAircraftLayer()

		val aircraftAndTrail = renderAircraft(layer, params, passStart + 30f)
		assertTrue(nonTransparentPixelCount(aircraftAndTrail) > 0)
		aircraftAndTrail.recycle()

		val trailOnly = renderAircraft(layer, params, passStart + 65f)
		assertTrue(nonTransparentPixelCount(trailOnly) > 0)
		trailOnly.recycle()

		val expired = renderAircraft(layer, params, passStart + 80f)
		assertEquals(0, nonTransparentPixelCount(expired))
		expired.recycle()

		val disabled = renderAircraft(layer, params.copy(distantAircraftEnabled = false), passStart + 30f)
		assertEquals(0, nonTransparentPixelCount(disabled))
		disabled.recycle()
	}

	private fun renderAircraft(layer: DistantAircraftLayer, params: SceneParams, timeSeconds: Float): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		layer.draw(Canvas(bitmap), WIDTH.toFloat(), HEIGHT.toFloat(), params, timeSeconds)

		return bitmap
	}

	private fun nonTransparentPixelCount(bitmap: Bitmap): Int {
		val pixels = IntArray(WIDTH * HEIGHT)
		bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)

		return pixels.count { Color.alpha(it) > 0 }
	}

	private fun findPassStart(): Float {
		val frame = DistantAircraftFrame()
		for (slot in 0 until 120) {
			val slotStart = slot * 180f
			for (step in 10..170) {
				if (frame.sample(WIDTH.toFloat(), HEIGHT.toFloat(), slotStart + step)) {
					return frame.passStartSeconds
				}
			}
		}

		error("No active aircraft pass found in one scene-clock period")
	}

	private fun fairWeatherParams() = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f
	)

	private companion object {
		const val WIDTH = 360
		const val HEIGHT = 780
	}
}
