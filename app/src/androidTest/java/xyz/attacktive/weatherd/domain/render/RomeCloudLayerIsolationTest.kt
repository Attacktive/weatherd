package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.SunbeamMode

/**
 * October 18, 2026 Rome comparison from #211.
 *
 * All four controlled cases retain DWD's total (65%) and middle-cloud (10%) values,
 * so the low- and high-cloud changes are independent. The fifth case uses the exact
 * ItaliaMeteo totals and vertical layers from the user's diagnostic.
 *
 * This measures what the renderer changes; it cannot establish which forecast
 * matched the real sky without an independent ground/sky observation.
 */
@RunWith(AndroidJUnit4::class)
class RomeCloudLayerIsolationTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun romeForecastDisagreementSeparatesHighCirrusFromLowCloudEffects() {
		val cases = romeCases()
		// CloudLayer's daily placements must not change with the CI run date or at midnight.
		val renderer = SceneRenderer(resources, epochDay = FIXTURE_EPOCH_DAY)
		renderer.prewarmCloudTextures()
		val backdrops = cases.mapValues { (_, params) -> render(renderer, params, foreground = false) }
		val frames = cases.mapValues { (_, params) -> render(renderer, params, foreground = true) }

		try {
			assertCloudGateAndBackdropEffects(cases, backdrops, frames)
			assertCirrusBandAndLogPixelDeltas(frames)
			assertActualItaliaMeteoMatchesCombinedControl(backdrops, frames)
		} finally {
			frames.values.forEach(Bitmap::recycle)
			backdrops.values.forEach(Bitmap::recycle)
		}
	}

	private fun romeCases(): Map<String, SceneParams> {
		val dwd = romeScene(total = 0.65f, low = 0.58f, mid = 0.10f, high = 0.15f)
		val layers = checkNotNull(dwd.cloudLayers)
		return linkedMapOf(
			"dwd" to dwd,
			"highOnly" to dwd.copy(cloudLayers = layers.copy(high = 0.65f)),
			"lowOnly" to dwd.copy(cloudLayers = layers.copy(low = 0.62f)),
			"both" to dwd.copy(cloudLayers = layers.copy(low = 0.62f, high = 0.65f)),
			"italia" to romeScene(total = 0.90f, low = 0.62f, mid = 0.01f, high = 0.65f)
		)
	}

	private fun assertCloudGateAndBackdropEffects(
		cases: Map<String, SceneParams>,
		backdrops: Map<String, Bitmap>,
		frames: Map<String, Bitmap>
	) {
		assertEquals(0.58f, effectiveOpaqueCloudiness(cases.getValue("dwd")), EPSILON)
		assertEquals(0.62f, effectiveOpaqueCloudiness(cases.getValue("italia")), EPSILON)
		assertFalse(SceneRenderer.shouldDrawOvercastBanks(0.58f, 0f, false))
		assertFalse(SceneRenderer.shouldDrawOvercastBanks(0.62f, 0f, false))

		assertTrue(
			"Raising only high clouds must not alter the cached sky gradient",
			backdrops.getValue("dwd").sameAs(backdrops.getValue("highOnly"))
		)
		assertFalse(
			"Raising only low clouds must change the gradual gray-sky blend",
			backdrops.getValue("dwd").sameAs(backdrops.getValue("lowOnly"))
		)
		assertFalse("High-cloud-only change must visibly change cirrus", frames.getValue("dwd").sameAs(frames.getValue("highOnly")))
		assertFalse("Low-cloud-only change must visibly affect the scene", frames.getValue("dwd").sameAs(frames.getValue("lowOnly")))
	}

	private fun assertCirrusBandAndLogPixelDeltas(frames: Map<String, Bitmap>) {
		val base = frames.getValue("dwd")
		val upperBoundary = (HEIGHT * UPPER_SKY_LIMIT).roundToInt()
		val highUpperDelta = meanRgbDelta(base, frames.getValue("highOnly"), 0, upperBoundary)
		val highLowerDelta = meanRgbDelta(base, frames.getValue("highOnly"), upperBoundary, HEIGHT)
		val lowUpperDelta = meanRgbDelta(base, frames.getValue("lowOnly"), 0, upperBoundary)
		val lowLowerDelta = meanRgbDelta(base, frames.getValue("lowOnly"), upperBoundary, HEIGHT)
		val combinedUpperDelta = meanRgbDelta(base, frames.getValue("both"), 0, upperBoundary)
		val combinedLowerDelta = meanRgbDelta(base, frames.getValue("both"), upperBoundary, HEIGHT)

		// High cirrus must not move opaque clouds or modify the cached backdrop.
		assertTrue("Changing high cover must visibly affect the upper sky", highUpperDelta > 0.0)
		assertEquals("High-only clouds must not spill below upper sky", 0.0, highLowerDelta, 0.0)

		val result = "Rome cloud isolation (mean RGB channel delta, 0-255): " +
			"high-only upper=$highUpperDelta lower=$highLowerDelta, " +
			"low-only upper=$lowUpperDelta lower=$lowLowerDelta, " +
			"combined upper=$combinedUpperDelta lower=$combinedLowerDelta"
		Log.i(LOG_TAG, result)
		println(result)
	}

	private fun assertActualItaliaMeteoMatchesCombinedControl(
		backdrops: Map<String, Bitmap>,
		frames: Map<String, Bitmap>
	) {
		// The 10% versus 1% middle cover is below the scattered-deck floor.
		// Total cover cannot change the renderer when all three layers exist.
		assertTrue(
			"Actual ItaliaMeteo values should match combined low/high control",
			frames.getValue("both").sameAs(frames.getValue("italia"))
		)
		assertTrue(
			"Source total and sub-threshold mid cover must not change the backdrop",
			backdrops.getValue("both").sameAs(backdrops.getValue("italia"))
		)
	}

	private fun romeScene(total: Float, low: Float, mid: Float, high: Float) = SceneParams(
		dayPhase = DayPhase.DAY,
		celestialProgress = 0.10f,
		cloudiness = total,
		cloudLayers = SceneCloudLayers(low = low, mid = mid, high = high),
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0.2f,
		cloudCountScale = 1f,
		cloudScale = 1f,
		treesEnabled = false,
		distantAircraftEnabled = false,
		birdFlockSize = BirdFlockSize.OFF,
		sunbeamMode = SunbeamMode.OFF,
		lensFlareEnabled = false
	)

	private fun render(renderer: SceneRenderer, params: SceneParams, foreground: Boolean): Bitmap {
		val image = createBitmap(WIDTH, HEIGHT)
		val canvas = Canvas(image)
		renderer.renderBackdrop(canvas, WIDTH, HEIGHT, params)
		if (foreground) {
			renderer.renderForeground(canvas, WIDTH, HEIGHT, params, TIME_SECONDS, includeOverlayLabels = false)
		}
		return image
	}

	private fun meanRgbDelta(first: Bitmap, second: Bitmap, startY: Int, endY: Int): Double {
		val a = IntArray(WIDTH * HEIGHT)
		val b = IntArray(WIDTH * HEIGHT)
		first.getPixels(a, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
		second.getPixels(b, 0, WIDTH, 0, 0, WIDTH, HEIGHT)

		var total = 0L
		for (y in startY until endY) {
			for (x in 0 until WIDTH) {
				val index = y * WIDTH + x
				total += abs(Color.red(a[index]) - Color.red(b[index]))
				total += abs(Color.green(a[index]) - Color.green(b[index]))
				total += abs(Color.blue(a[index]) - Color.blue(b[index]))
			}
		}
		return total.toDouble() / ((endY - startY) * WIDTH * 3)
	}

	private companion object {
		private const val WIDTH = 360
		private const val HEIGHT = 780
		private const val TIME_SECONDS = 17f
		private const val FIXTURE_EPOCH_DAY = 20744L // 2026-10-18 UTC
		private const val UPPER_SKY_LIMIT = 0.42f
		private const val EPSILON = 0.0001f
		private const val LOG_TAG = "RomeCloudIsolation"
	}
}
