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
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.DayPhase

@RunWith(AndroidJUnit4::class)
class OverlayLabelsRenderingTest {
	private val renderer = SceneRenderer(InstrumentationRegistry.getInstrumentation().targetContext.resources)
	private val labelCases = listOf(OverlayLabels("Light rain · 10°", null), OverlayLabels(null, "Ålesund"), OverlayLabels("Light rain · 10°", "Ålesund"))

	@Test
	fun defaultPositionMatchesHistoricalBaselinesAndPixelsForEveryVisibilityMode() {
		val defaults = AppSettings()
		for ((width, height) in listOf(400 to 1000, 1000 to 400)) {
			for (labels in labelCases) {
				val baselines = mutableListOf<Float>()
				val actual = render(width, height, defaults.labelVerticalPosition, labels, baselines)
				val expectedBaselines = buildList {
					if (labels.weather != null) {
						add(height * 0.09f)
					}

					if (labels.location != null) {
						add(height * 0.121f)
					}
				}

				assertEquals("The unset/default position must keep the historical baselines for $labels", expectedBaselines, baselines)
				val historical = renderHistoricalLabels(width, height, labels)
				assertTrue("The default must be pixel-identical to the pre-slider overlay for $labels", historical.sameAs(actual))
				actual.recycle()
				historical.recycle()
			}
		}
	}

	@Test
	fun endpointsReachTheDrawableEdgesForEveryVisibilityMode() {
		for ((width, height) in listOf(400 to 1000, 1000 to 400)) {
			for (labels in labelCases) {
				val top = bounds(render(width, height, 0f, labels))
				val bottom = bounds(render(width, height, 1f, labels))

				assertTrue("0% must reach the drawable top: $labels at ${width}x$height", top.top in 0..1)
				assertTrue("100% must reach the drawable bottom: $labels at ${width}x$height", bottom.bottom in height - 1..height)
			}
		}
	}

	@Test
	fun intermediatePositionsUseContinuousTravelOnBothSidesOfTheDefault() {
		val default = AppSettings().labelVerticalPosition
		for ((width, height) in listOf(400 to 1000, 1000 to 400)) {
			for (labels in labelCases) {
				for ((start, end) in listOf(0f to default, default to 1f)) {
					val first = mutableListOf<Float>()
					val last = mutableListOf<Float>()
					render(width, height, start, labels, first).recycle()
					render(width, height, end, labels, last).recycle()
					for (fraction in listOf(0.01f, 0.25f, 0.5f, 0.75f, 0.99f)) {
						val actual = mutableListOf<Float>()
						val position = start + (end - start) * fraction
						render(width, height, position, labels, actual).recycle()
						for (line in first.indices) {
							val expected = first[line] + (last[line] - first[line]) * fraction
							assertEquals("The baseline must move continuously through the historical default", expected, actual[line], 0.001f)
						}
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

	private fun renderHistoricalLabels(width: Int, height: Int, labels: OverlayLabels): Bitmap {
		val bitmap = createBitmap(width, height)
		val canvas = Canvas(bitmap)
		val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			textAlign = Paint.Align.CENTER
			letterSpacing = 0.03f
		}

		paint.setShadowLayer(height * 0.005f, 0f, height * 0.0012f, Color.argb(165, 8, 12, 20))
		labels.weather?.let {
			paint.textSize = height * 0.024f
			paint.color = Color.argb(225, 240, 245, 252)
			canvas.drawText(it, width / 2f, height * 0.09f, paint)
		}

		labels.location?.let {
			paint.textSize = height * 0.0165f
			paint.color = Color.argb(180, 228, 236, 248)
			canvas.drawText(it, width / 2f, height * 0.121f, paint)
		}

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
