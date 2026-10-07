package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
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
	fun portraitCoverProgressionAddsCloudCoreAndLowerSky() {
		var previousCore = 0L
		var previousLower = 0L
		for (cover in listOf(0.20f, 0.45f, 0.70f, 0.90f)) {
			var core = 0L
			var lower = 0L
			for (time in listOf(0f, 125f, 250f, 375f)) {
				val disabled = renderForeground(cover, 0f, timeSeconds = time)
				val enabled = renderForeground(cover, 1f, timeSeconds = time)
				val before = pixels(disabled)
				val after = pixels(enabled)
				for (index in before.indices) {
					if (before[index] == after[index]) {
						continue
					}

					if (Color.alpha(after[index]) >= 192) {
						core++
						if (index / WIDTH >= (HEIGHT * 0.55f).toInt()) {
							lower++
						}
					}
				}

				disabled.recycle()
				enabled.recycle()
			}

			assertTrue("Cover $cover must add cloud-attributable core area, saw $previousCore then $core", core > previousCore)
			assertTrue("Cover $cover must add lower-sky core area, saw $previousLower then $lower", lower > previousLower)
			previousCore = core
			previousLower = lower
		}
	}

	@Test
	fun aMidnightFrameUsesOneEpochForDrawingAndSampling() {
		var reads = 0
		val advancing = SceneRenderer(resources, CloudEpochDaySource { if (reads++ == 0) { 20000 } else { 20001 } })

		val firstDay = SceneRenderer(resources, CloudEpochDaySource { 20000 })
		val secondDay = SceneRenderer(resources, CloudEpochDaySource { 20001 })
		val params = scene(0.45f, 1f).copy(sunVisible = true)
		val first = frame(advancing, params)
		val expectedFirst = frame(firstDay, params)
		val second = frame(advancing, params)
		val expectedSecond = frame(secondDay, params)
		assertTrue("First frame must use the first epoch in every consumer", first.sameAs(expectedFirst))
		assertTrue("Next frame must use the new epoch in every consumer", second.sameAs(expectedSecond))
		assertFalse("The injected next-day layout must change visible cloud output", first.sameAs(second))
		first.recycle()
		expectedFirst.recycle()
		second.recycle()
		expectedSecond.recycle()
	}

	@Test
	fun highCloudsRenderAsUpperSkyCirrus() {
		val layers = SceneCloudLayers(low = 0.05f, mid = 0.05f, high = 0.8f)
		val withoutClouds = renderForeground(0.8f, 0f, layers)
		val withClouds = renderForeground(0.8f, 1f, layers)
		val bounds = differenceBounds(withoutClouds, withClouds)
		assertTrue("High cloud must render visible upper-sky cirrus", bounds.width >= (WIDTH * 0.35f).roundToInt())
		assertTrue("Cirrus must remain above lower cumulus", bounds.bottom <= (HEIGHT * 0.42f).roundToInt())
		withoutClouds.recycle()
		withClouds.recycle()
	}

	@Test
	fun overcastFieldReachesLowerPortraitSky() {
		val earlyBanks = renderForeground(0.75f, 1f)
		val fullBanks = renderForeground(0.9f, 1f)
		val bounds = differenceBounds(earlyBanks, fullBanks, minY = (HEIGHT * 0.55f).toInt())
		assertTrue("Overcast must cover most of the portrait width", bounds.width >= (WIDTH * 0.82f).roundToInt())
		assertTrue("Overcast texture must reach the lower sky, saw ${bounds.bottom}", bounds.bottom >= (HEIGHT * 0.80f).roundToInt())
		earlyBanks.recycle()
		fullBanks.recycle()
	}

	@Test
	fun cloudCrossingsAttenuateTheSunAcrossHandoff() {
		var sawCrossing = false
		for (cover in listOf(0.20f, 0.45f, 0.70f, 0.75f, 0.80f, 0.85f)) {
			for (time in listOf(0f, 125f, 250f, 375f)) {
				val renderer = SceneRenderer(resources, CloudEpochDaySource { 20000 })
				val params = scene(cover, 1f)
				val clouds = frame(renderer, params, time)
				val withSun = frame(renderer, params.copy(sunVisible = true), time)
				val clear = frame(renderer, params.copy(cloudScale = 0f), time)
				val clearSun = frame(renderer, params.copy(cloudScale = 0f, sunVisible = true), time)
				val x = (WIDTH * 0.72f).toInt()
				val y = (HEIGHT * celestialHeightFraction(DayPhase.DAY, 0.5f)).toInt()
				if (Color.alpha(clouds.getPixel(x, y)) >= 192 && clear.getPixel(x, y) != clouds.getPixel(x, y)) {
					val blockedGain = rgbGain(withSun.getPixel(x, y), clouds.getPixel(x, y))
					val clearGain = rgbGain(clearSun.getPixel(x, y), clear.getPixel(x, y))
					if (clearGain > 0) {
						assertTrue("A drawn cloud crossing must attenuate direct sunlight", blockedGain < clearGain)
						sawCrossing = true
					}
				}

				clouds.recycle()
				withSun.recycle()
				clear.recycle()
				clearSun.recycle()
			}
		}

		assertTrue("Fixture must exercise a real illuminated cloud crossing", sawCrossing)
	}

	@Test
	fun sunShaftsFollowRenderedCloudSizeInClearSky() {
		var strongestClearSkyChange = 0
		for (time in listOf(0f, 62.5f, 125f, 187.5f, 250f, 312.5f, 375f, 437.5f)) {
			val renderer = SceneRenderer(resources, CloudEpochDaySource { 20000 })
			val params = scene(0.45f, 1f, SceneCloudLayers(0.20f, 0.45f, 0f)).copy(lensFlareEnabled = false)
			val smallParams = params.copy(cloudSizeScale = 0.5f)
			val largeParams = params.copy(cloudSizeScale = 1.5f)
			val smallMask = frame(renderer, smallParams, time)
			val largeMask = frame(renderer, largeParams, time)
			val smallSun = frame(renderer, smallParams.copy(sunVisible = true), time, includeBackdrop = true)
			val largeSun = frame(renderer, largeParams.copy(sunVisible = true), time, includeBackdrop = true)
			val smallCloudPixels = pixels(smallMask)
			val largeCloudPixels = pixels(largeMask)
			val smallSunPixels = pixels(smallSun)
			val largeSunPixels = pixels(largeSun)
			for (index in smallCloudPixels.indices) {
				if (Color.alpha(smallCloudPixels[index]) != 0 || Color.alpha(largeCloudPixels[index]) != 0) {
					continue
				}

				val first = smallSunPixels[index]
				val second = largeSunPixels[index]
				val lightChange = abs(Color.red(first) - Color.red(second)) + abs(Color.green(first) - Color.green(second)) + abs(Color.blue(first) - Color.blue(second))
				strongestClearSkyChange = maxOf(strongestClearSkyChange, lightChange)
			}

			smallMask.recycle()
			largeMask.recycle()
			smallSun.recycle()
			largeSun.recycle()
		}

		assertTrue("Cloud-size changes must redirect sampled shafts in clear sky, saw RGB lift $strongestClearSkyChange", strongestClearSkyChange > 0)
	}

	@Test
	fun overcastBankHeightStaysBoundedInLandscape() {
		val bankHeight = SceneRenderer.overcastBankHeight(780f, 360f, 1.03f, 1.10f)
		assertTrue("Banks must stay within 55% of a landscape surface", bankHeight <= 360f * 0.55f)
	}

	@Test
	fun portraitBanksPreserveSourceAspectAcrossViewportShapes() {
		for ((width, height) in listOf(540f to 720f, 640f to 660f, 1080f to 2340f, 1440f to 3200f)) {
			val bankHeight = SceneRenderer.overcastBankHeight(width, height, 3f, 1f)
			assertEquals("Portrait banks must retain their native aspect in ${width}x$height", 3f, width * 3f / bankHeight, 0.0001f)
		}
	}

	@Test
	fun denseDryFogForegroundDoesNotDependOnCloudOpacity() {
		val params = scene(0.9f, 0f).copy(fogDensity = 1f)
		val renderer = SceneRenderer(resources, CloudEpochDaySource { 20000 })
		val disabled = frame(renderer, params)
		val enabled = frame(renderer, params.copy(cloudScale = 1f))
		assertTrue("Dense dry fog must not contain either cloud family", disabled.sameAs(enabled))
		disabled.recycle()
		enabled.recycle()
	}

	private fun scene(cover: Float, opacity: Float, layers: SceneCloudLayers? = null) = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = cover,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f,
		cloudLayers = layers,
		cloudScale = opacity,
		sunVisible = false,
		moonVisible = false
	)

	private fun renderForeground(cloudiness: Float, cloudScale: Float, cloudLayers: SceneCloudLayers? = null, timeSeconds: Float = 17f) = frame(SceneRenderer(resources, CloudEpochDaySource { 20000 }), scene(cloudiness, cloudScale, cloudLayers), timeSeconds)

	private fun frame(renderer: SceneRenderer, params: SceneParams, timeSeconds: Float = 17f, includeBackdrop: Boolean = false): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		renderer.prewarmCloudTextures()
		if (includeBackdrop) {
			renderer.renderBackdrop(Canvas(bitmap), WIDTH, HEIGHT, params)
		}

		renderer.renderForeground(Canvas(bitmap), WIDTH, HEIGHT, params, timeSeconds, includeOverlayLabels = false)
		return bitmap
	}

	private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
		bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
	}

	private fun rgbGain(lit: Int, unlit: Int) = Color.red(lit) + Color.green(lit) + Color.blue(lit) - Color.red(unlit) - Color.green(unlit) - Color.blue(unlit)

	private fun differenceBounds(first: Bitmap, second: Bitmap, minY: Int = 0): PixelBounds {
		val before = pixels(first)
		val after = pixels(second)
		var left = first.width
		var right = -1
		var bottom = -1
		for (index in before.indices) {
			if (index / first.width < minY) {
				continue
			}

			if (before[index] == after[index]) {
				continue
			}

			val x = index % first.width
			val y = index / first.width
			left = minOf(left, x)
			right = maxOf(right, x)
			bottom = maxOf(bottom, y)
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
	}
}
