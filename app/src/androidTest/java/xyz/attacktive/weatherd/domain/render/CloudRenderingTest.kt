package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
	fun highCloudsRenderAsUpperSkyCirrus() {
		val layers = SceneCloudLayers(low = 0.05f, mid = 0.05f, high = 0.8f)
		val withoutClouds = renderForeground(
			cloudiness = 0.8f,
			cloudScale = 0f,
			cloudLayers = layers
		)
		val withClouds = renderForeground(
			cloudiness = 0.8f,
			cloudScale = 1f,
			cloudLayers = layers
		)
		val bounds = differenceBounds(withoutClouds, withClouds)

		assertTrue(
			"High cloud must render visible cirrus across the upper sky",
			bounds.width >= (WIDTH * 0.35f).roundToInt()
		)
		assertTrue(
			"Cirrus must stay above the lower cumulus region, but reached ${bounds.bottom}",
			bounds.bottom <= (HEIGHT * 0.42f).roundToInt()
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

	@Test
	fun partlyCloudyBankAppliesContrastScale() {
		val renderer = SceneRenderer(resources)
		renderer.prewarmCloudTextures()

		val soft = createBitmap(WIDTH, HEIGHT)
		val hard = createBitmap(WIDTH, HEIGHT)
		val params = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = 0.72f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0.2f,
			cloudScale = 1f,
			cloudContrastScale = 0.5f
		)

		renderer.drawPartlyCloudBank(Canvas(soft), WIDTH.toFloat(), HEIGHT.toFloat(), params, TIME_SECONDS, 0.95f)
		renderer.drawPartlyCloudBank(Canvas(hard), WIDTH.toFloat(), HEIGHT.toFloat(), params.copy(cloudContrastScale = 1.5f), TIME_SECONDS, 0.95f)

		val softPixels = IntArray(WIDTH * HEIGHT)
		val hardPixels = IntArray(WIDTH * HEIGHT)
		soft.getPixels(softPixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
		hard.getPixels(hardPixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)

		var changed = 0
		var visible = 0
		for (index in softPixels.indices) {
			val softAlpha = Color.alpha(softPixels[index])
			val hardAlpha = Color.alpha(hardPixels[index])
			if (softAlpha > 0) {
				visible++
				assertEquals("Bank alpha must be invariant to contrast", softAlpha, hardAlpha)
				if (softPixels[index] != hardPixels[index]) {
					changed++
				}
			}
		}

		assertTrue("Partly cloudy bank must render visible pixels", visible > 0)
		assertTrue("Partly cloudy bank pixels must change with contrast scale", changed > 0)

		soft.recycle()
		hard.recycle()
	}

	@Test
	fun denseFogSuppressesLowSunCloudGradeOnScatteredClouds() {
		val renderer = SceneRenderer(resources)
		renderer.prewarmCloudTextures()

		val clearLowSunParams = SceneParams(
			dayPhase = DayPhase.DAWN,
			cloudiness = 0.425f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			cloudScale = 1f,
			celestialProgress = 0.5f
		)
		assertEquals(
			cloudColorGradeFor(DayPhase.DAWN, 0.5f),
			renderer.cloudGradeFor(clearLowSunParams)
		)

		val denseFogParams = clearLowSunParams.copy(fogDensity = 1f)
		assertEquals(
			CloudColorGrade.IDENTITY,
			renderer.cloudGradeFor(denseFogParams)
		)

		val thunderParams = clearLowSunParams.copy(thunder = true)
		assertEquals(
			CloudColorGrade.IDENTITY,
			renderer.cloudGradeFor(thunderParams)
		)
	}

	private fun renderForeground(
		cloudiness: Float,
		cloudScale: Float,
		cloudLayers: SceneCloudLayers? = null,
		cloudContrastScale: Float = 1f
	): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		val params = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = cloudiness,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0.2f,
			cloudLayers = cloudLayers,
			cloudScale = cloudScale,
			cloudContrastScale = cloudContrastScale
		)
		val renderer = SceneRenderer(resources)
		renderer.prewarmCloudTextures()
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
