package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
	private val labelCases = listOf(OverlayLabels("Light rain · 10°", null), OverlayLabels(null, "Ålesund"), OverlayLabels("Light rain · 10°", "Ålesund"))

	@Test
	fun endpointsAlignTheWholeVisibleBlockWithoutClippingEitherLine() {
		for ((width, height) in listOf(400 to 1000, 1000 to 400)) {
			for (labels in labelCases) {
				val middle = bounds(render(width, height, 0.5f, labels))
				val top = bounds(render(width, height, 0f, labels))
				val bottom = bounds(render(width, height, 1f, labels))

				assertTrue("0% must reach the drawable top: $labels at ${width}x$height", top.top <= 1)
				assertTrue("100% must reach the drawable bottom: $labels at ${width}x$height", bottom.bottom >= height - 1)
				for (edge in listOf(top, bottom)) {
					assertTrue("Moving to an edge must not clip or resize the text block", abs(edge.height() - middle.height()) <= 1)
					assertTrue("Horizontal alignment must not change", abs(edge.left - middle.left) <= 1)
					assertTrue("Horizontal alignment must not change", abs(edge.right - middle.right) <= 1)
				}
			}
		}
	}

	@Test
	fun intermediatePositionsUseTheWholeAvailableTravel() {
		for ((width, height) in listOf(400 to 1000, 1000 to 400)) {
			for (labels in labelCases) {
				val top = mutableListOf<Float>()
				val bottom = mutableListOf<Float>()
				render(width, height, 0f, labels, top).recycle()
				render(width, height, 1f, labels, bottom).recycle()
				for (position in listOf(0.01f, 0.25f, 0.75f, 0.99f)) {
					val actual = mutableListOf<Float>()
					render(width, height, position, labels, actual).recycle()
					for (line in top.indices) {
						val expected = top[line] + (bottom[line] - top[line]) * position
						assertEquals("The emitted baseline must use the full available travel", expected, actual[line], 0.001f)
					}
				}
			}
		}
	}

	@Test
	fun anEmptyWeatherLineDoesNotReserveInvisibleSpace() {
		for (position in listOf(0f, 0.5f, 1f)) {
			val locationOnly = bounds(render(400, 1000, position, OverlayLabels(null, "Rome")))
			val emptyWeather = bounds(render(400, 1000, position, OverlayLabels("   ", "Rome")))
			assertTrue(abs(locationOnly.top - emptyWeather.top) <= 1)
			assertTrue(abs(locationOnly.bottom - emptyWeather.bottom) <= 1)
		}
	}

	@Test
	fun outOfRangePositionsStopAtTheDrawableEdges() {
		for ((position, boundary) in listOf(-1f to 0f, 2f to 1f)) {
			val actual = render(400, 1000, position)
			val expected = render(400, 1000, boundary)
			assertTrue("Out-of-range positions must not put text off-screen", expected.sameAs(actual))
			actual.recycle()
			expected.recycle()
		}
	}

	private fun render(width: Int, height: Int, position: Float, labels: OverlayLabels = OverlayLabels("Rain · 10°", "Rome"), baselines: MutableList<Float>? = null): Bitmap {
		val bitmap = createBitmap(width, height)
		val canvas = object: Canvas(bitmap) {
			override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
				baselines?.add(y)
				super.drawText(text, x, y, paint)
			}
		}

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

		renderer.renderOverlayLabels(canvas, width, height, params)
		return bitmap
	}

	private fun bounds(bitmap: Bitmap): Rect {
		val bounds = Rect(bitmap.width, bitmap.height, 0, 0)
		for (y in 0 until bitmap.height) {
			for (x in 0 until bitmap.width) {
				val pixel = bitmap.getPixel(x, y)
				if (Color.alpha(pixel) > 32 && Color.red(pixel) > 200) {
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
