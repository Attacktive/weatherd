package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase

@RunWith(AndroidJUnit4::class)
class CloudRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun mostlyCloudyForegroundRendersMovingTextureClouds() {
		val withoutClouds = renderForeground(cloudiness = 0.65f, cloudScale = 0f)
		val withClouds = renderForeground(cloudiness = 0.65f, cloudScale = 1f)

		assertFalse(
			"Mostly cloudy skies must retain moving textured clouds instead of only the cached ceiling",
			withoutClouds.sameAs(withClouds)
		)
		withoutClouds.recycle()
		withClouds.recycle()
	}

	@Test
	fun overcastCloudsReachIntoTheMidSky() {
		val withoutClouds = renderForeground(cloudiness = 0.9f, cloudScale = 0f)
		val withClouds = renderForeground(cloudiness = 0.9f, cloudScale = 1f)
		val bounds = differenceBounds(withoutClouds, withClouds)

		assertTrue(
			"Overcast cloud banks must occupy the mid-sky instead of collapsing into a top strip, but their lower edge was ${bounds.bottom}",
			bounds.bottom >= (HEIGHT * 0.62f).roundToInt()
		)
		withoutClouds.recycle()
		withClouds.recycle()
	}

	@Test
	fun overcastCloudsContainAScreenDominantPortraitBank() {
		val withoutClouds = renderForeground(cloudiness = 0.9f, cloudScale = 0f)
		val withClouds = renderForeground(cloudiness = 0.9f, cloudScale = 1f)
		val bounds = differenceBounds(withoutClouds, withClouds)

		assertTrue(
			"Overcast composition must cover most of the portrait width, but its changed-pixel span was ${bounds.width}px",
			bounds.width >= (WIDTH * 0.82f).roundToInt()
		)
		withoutClouds.recycle()
		withClouds.recycle()
	}

	@Test
	fun overcastBankHeightStaysBoundedInLandscape() {
		val bankHeight = SceneRenderer.overcastBankHeight(
			width = 780f,
			height = 360f,
			viewports = 1.03f,
			heightScale = 1.10f
		)

		assertTrue(
			"An overcast bank must not grow taller than 55% of a landscape surface, but was $bankHeight",
			bankHeight <= 360f * 0.55f
		)
	}

	@Test
	fun denseFogDoesNotReuseOvercastCloudBanks() {
		assertFalse(
			"Dense fog without precipitation must use fog veils rather than overcast cloud banks",
			SceneRenderer.shouldDrawOvercastBanks(cloudiness = 0.9f, fogDensity = 1f, hasPrecipitation = false)
		)
		assertTrue(
			"Precipitation must retain its cloud deck even when fog is also present",
			SceneRenderer.shouldDrawOvercastBanks(cloudiness = 0.9f, fogDensity = 1f, hasPrecipitation = true)
		)
	}

	private fun renderForeground(cloudiness: Float, cloudScale: Float): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		val params = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = cloudiness,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0.2f,
			cloudScale = cloudScale,
		)
		val renderer = SceneRenderer(resources)
		renderer.prewarmOvercastClouds()
		renderer.renderForeground(Canvas(bitmap), WIDTH, HEIGHT, params, TIME_SECONDS)

		return bitmap
	}

	private fun differenceBounds(first: Bitmap, second: Bitmap): PixelBounds {
		var left = first.width
		var right = -1
		var bottom = -1

		for (y in 0 until first.height) {
			for (x in 0 until first.width) {
				if (first.getPixel(x, y) != second.getPixel(x, y)) {
					left = minOf(left, x)
					right = maxOf(right, x)
					bottom = maxOf(bottom, y)
				}
			}
		}

		return PixelBounds(left, right, bottom)
	}

	private data class PixelBounds(val left: Int, val right: Int, val bottom: Int) {
		val width
			get() = if (right < left) 0 else right - left + 1
	}

	private companion object {
		const val WIDTH = 360
		const val HEIGHT = 780
		const val TIME_SECONDS = 17f
	}
}
