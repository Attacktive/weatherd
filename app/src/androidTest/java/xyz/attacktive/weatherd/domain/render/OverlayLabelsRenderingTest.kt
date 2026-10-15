package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase

@RunWith(AndroidJUnit4::class)
class OverlayLabelsRenderingTest {
	private val renderer = SceneRenderer(InstrumentationRegistry.getInstrumentation().targetContext.resources)

	@Test
	fun positionMovesBothLinesTogetherWithoutResizingOrChangingTheirSpacing() {
		for ((width, height) in listOf(400 to 1000, 1000 to 400)) {
			val weather = bounds(render(width, height, 0.09f, OverlayLabels("Rain · 10°", null)))
			val location = bounds(render(width, height, 0.09f, OverlayLabels(null, "Rome")))
			for (position in listOf(0.04f, 0.55f, 0.94f)) {
				val movedWeather = bounds(render(width, height, position, OverlayLabels("Rain · 10°", null)))
				val movedLocation = bounds(render(width, height, position, OverlayLabels(null, "Rome")))
				val expectedShift = height * (position - 0.09f)

				assertTrue("Weather must follow the saved position", abs(movedWeather.top - weather.top - expectedShift) <= 1f)
				assertTrue("Location must follow the same position even with weather hidden", abs(movedLocation.top - location.top - expectedShift) <= 1f)
				assertEquals(weather.left, movedWeather.left)
				assertEquals(weather.right, movedWeather.right)
				assertEquals(location.left, movedLocation.left)
				assertEquals(location.right, movedLocation.right)
				assertTrue(abs(movedWeather.height() - weather.height()) <= 1)
				assertTrue(abs(movedLocation.height() - location.height()) <= 1)
				assertTrue(abs((movedLocation.top - movedWeather.top) - (location.top - weather.top)) <= 1)
				assertTrue(movedWeather.top > 0)
				assertTrue(movedLocation.bottom < height)
			}
		}
	}

	@Test
	fun outOfRangePositionsStopAtTheSafeMargins() {
		for ((position, boundary) in listOf(-1f to 0.04f, 2f to 0.94f)) {
			val actual = render(400, 1000, position)
			val expected = render(400, 1000, boundary)
			assertTrue("Out-of-range positions must not put text off-screen", expected.sameAs(actual))
			actual.recycle()
			expected.recycle()
		}
	}

	private fun render(width: Int, height: Int, position: Float, labels: OverlayLabels = OverlayLabels("Rain · 10°", "Rome")): Bitmap {
		val bitmap = createBitmap(width, height)
		val params = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = 0f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			overlayLabels = labels,
			labelVerticalPosition = position
		)

		renderer.renderOverlayLabels(Canvas(bitmap), width, height, params)
		return bitmap
	}

	private fun bounds(bitmap: Bitmap): Rect {
		val bounds = Rect(bitmap.width, bitmap.height, 0, 0)
		for (y in 0 until bitmap.height) {
			for (x in 0 until bitmap.width) {
				if (Color.alpha(bitmap.getPixel(x, y)) > 32) {
					bounds.left = minOf(bounds.left, x)
					bounds.top = minOf(bounds.top, y)
					bounds.right = maxOf(bounds.right, x + 1)
					bounds.bottom = maxOf(bounds.bottom, y + 1)
				}
			}
		}

		bitmap.recycle()
		assertTrue("The enabled line must produce visible text", bounds.width() > 0 && bounds.height() > 0)
		return bounds
	}
}
