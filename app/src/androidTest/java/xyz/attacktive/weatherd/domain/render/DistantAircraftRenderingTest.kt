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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.SunColorPreset
import xyz.attacktive.weatherd.domain.model.SunStyle

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

	@Test
	fun nightAircraftHasNavigationLightsWithoutALingeringContrail() {
		val params = fairWeatherParams().copy(dayPhase = DayPhase.NIGHT)
		val layer = DistantAircraftLayer()
		val passStart = findPassStart()
		val aircraft = renderAircraft(layer, params, passStart + 30f)
		val trailOnly = renderAircraft(layer, params, passStart + 65f)
		val disabled = renderAircraft(layer, params.copy(distantAircraftEnabled = false), passStart + 30f)

		try {
			val pixels = IntArray(WIDTH * HEIGHT)
			aircraft.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)

			assertTrue("A night aircraft must carry a red navigation light", pixels.any { Color.alpha(it) > 100 && Color.red(it) > Color.green(it) * 2 })
			assertTrue("A night aircraft must carry a green navigation light", pixels.any { Color.alpha(it) > 100 && Color.green(it) > Color.red(it) * 2 })
			assertTrue("A night aircraft must carry a white tail light", pixels.any { Color.alpha(it) > 100 && Color.red(it) > 200 && Color.green(it) > 200 && Color.blue(it) > 200 })
			assertEquals("Night flights must not leave a bright contrail", 0, nonTransparentPixelCount(trailOnly))
			assertEquals("Disabling aircraft must also remove their lights", 0, nonTransparentPixelCount(disabled))
		} finally {
			aircraft.recycle()
			trailOnly.recycle()
			disabled.recycle()
		}
	}

	@Test
	fun navigationLightsFollowBothFlightDirectionsAndStrobesRemainVisibleAtFiveFps() {
		val params = fairWeatherParams().copy(dayPhase = DayPhase.NIGHT)
		val frame = DistantAircraftFrame()
		val layer = DistantAircraftLayer()
		val directions = mutableSetOf<Boolean>()

		for (slot in 0 until 120) {
			frame.sample(WIDTH.toFloat(), HEIGHT.toFloat(), slot * 180f + 90f)

			val time = frame.passStartSeconds + 30f
			frame.sample(WIDTH.toFloat(), HEIGHT.toFloat(), time)

			if (!directions.add(frame.directionX > 0f)) {
				continue
			}

			val aircraft = renderAircraft(layer, params, time)
			try {
				val pixels = IntArray(WIDTH * HEIGHT)
				aircraft.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)

				val red = pixels.indices.filter { Color.alpha(pixels[it]) > 100 && Color.red(pixels[it]) > Color.green(pixels[it]) * 2 }
				val green = pixels.indices.filter { Color.alpha(pixels[it]) > 100 && Color.green(pixels[it]) > Color.red(pixels[it]) * 2 }

				val portOffsets = red.map { pixel ->
					(pixel % WIDTH + 0.5f - frame.planeX) * frame.directionY - (pixel / WIDTH + 0.5f - frame.planeY) * frame.directionX
				}

				val starboardOffsets = green.map { pixel ->
					(pixel % WIDTH + 0.5f - frame.planeX) * frame.directionY - (pixel / WIDTH + 0.5f - frame.planeY) * frame.directionX
				}

				assertTrue("Red navigation light must stay to port of the travel direction", portOffsets.isNotEmpty() && portOffsets.all { it > 0f })
				assertTrue("Green navigation light must stay to starboard of the travel direction", starboardOffsets.isNotEmpty() && starboardOffsets.all { it < 0f })

				for (pixel in red + green) {
					assertTrue("Navigation lights must travel with the aircraft", abs(pixel % WIDTH - frame.planeX) < WIDTH * 0.05f && abs(pixel / WIDTH - frame.planeY) < HEIGHT * 0.02f)
					assertTrue("Wing lights must mirror with the aircraft's heading", (pixel % WIDTH - frame.planeX) * frame.directionX < 0f)
				}
			} finally {
				aircraft.recycle()
			}

			val whiteStrengths = (0..9).map { step ->
				val bitmap = renderAircraft(layer, params, time + step * 0.2f)
				try {
					val pixels = IntArray(WIDTH * HEIGHT)

					bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)

					pixels.sumOf { pixel ->
						if (Color.red(pixel) > 200 && Color.green(pixel) > 200 && Color.blue(pixel) > 200) {
							Color.alpha(pixel)
						} else {
							0
						}
					}
				} finally {
					bitmap.recycle()
				}
			}

			assertTrue("A strobe must visibly brighten the steady tail light at five frames per second: $whiteStrengths", whiteStrengths.max() > whiteStrengths.min() * 1.5f)
			if (directions.size == 2) {
				return
			}
		}

		error("Both aircraft headings must be exercised")
	}

	@Test
	fun aircraftCrossesInFrontOfEverySunStyleInBothOrientations() {
		val params = fairWeatherParams().copy(
			celestialProgress = 0.15f,
			sunColorPreset = SunColorPreset.WHITE,
			lensFlareEnabled = false,
			birdFlockSize = BirdFlockSize.OFF
		)

		val renderer = sceneRenderer()
		for ((width, height) in listOf(WIDTH to HEIGHT, HEIGHT to WIDTH)) {
			val time = findSolarTransit(width, height)
			val frame = DistantAircraftFrame()
			frame.sample(width.toFloat(), height.toFloat(), time)

			val x = frame.planeX.roundToInt()
			val y = frame.planeY.roundToInt()

			for (style in SunStyle.entries) {
				renderer.prewarmSunTexture(style)
				val styled = params.copy(sunStyle = style)
				val withoutAircraft = renderScene(renderer, styled.copy(distantAircraftEnabled = false), time, width, height)
				val withAircraft = renderScene(renderer, styled, time, width, height)

				try {
					val solarPixel = withoutAircraft.getPixel(x, y)
					val transitPixel = withAircraft.getPixel(x, y)

					assertTrue("The transit fixture must cross the bright $style solar disc", Color.red(solarPixel) > 230)
					assertTrue("The aircraft must remain visible in front of the $style sun at ${width}x$height", Color.red(solarPixel) - Color.red(transitPixel) > 15)
				} finally {
					withoutAircraft.recycle()
					withAircraft.recycle()
				}
			}
		}
	}

	@Test
	fun denseCloudsStillOccludeAircraft() {
		val params = fairWeatherParams().copy(
			cloudiness = 0.5f,
			sunVisible = false,
			birdFlockSize = BirdFlockSize.OFF
		)

		val renderer = sceneRenderer()
		val frame = DistantAircraftFrame()
		var maxCloudAlpha = 0
		for (time in 0 until 21_600 step 5) {
			if (!frame.sample(WIDTH.toFloat(), HEIGHT.toFloat(), time.toFloat()) || frame.planeOpacity < 1f) {
				continue
			}

			val x = frame.planeX.roundToInt()
			val y = frame.planeY.roundToInt()
			if (x !in 0 until WIDTH || y !in 0 until HEIGHT) {
				continue
			}

			val clouds = renderScene(renderer, params.copy(distantAircraftEnabled = false), time.toFloat())
			try {
				maxCloudAlpha = maxOf(maxCloudAlpha, Color.alpha(clouds.getPixel(x, y)))
				if (Color.alpha(clouds.getPixel(x, y)) < 240) {
					continue
				}

				val scene = renderScene(renderer, params, time.toFloat())
				try {
					val cloudPixel = clouds.getPixel(x, y)
					val occludedPixel = scene.getPixel(x, y)
					DistantAircraftLayer().draw(Canvas(clouds), WIDTH.toFloat(), HEIGHT.toFloat(), params, time.toFloat())

					val exposedPixel = clouds.getPixel(x, y)
					val exposedContrast = colorDistance(cloudPixel, exposedPixel)
					if (exposedContrast <= 20) {
						continue
					}

					assertTrue("A dense cloud must cover the aircraft rather than leave it painted on top", colorDistance(cloudPixel, occludedPixel) < exposedContrast / 4)
				} finally {
					scene.recycle()
				}

				return
			} finally {
				clouds.recycle()
			}
		}

		error("No dense cloud crossing found in one scene-clock period; maximum cloud alpha was $maxCloudAlpha")
	}

	private fun sceneRenderer() = SceneRenderer(InstrumentationRegistry.getInstrumentation().targetContext.resources, epochDay = 20_000L).apply {
		prewarmCloudTextures()
	}

	private fun renderScene(renderer: SceneRenderer, params: SceneParams, timeSeconds: Float, width: Int = WIDTH, height: Int = HEIGHT): Bitmap {
		val bitmap = createBitmap(width, height)
		renderer.renderForeground(Canvas(bitmap), width, height, params, timeSeconds)

		return bitmap
	}

	private fun findSolarTransit(width: Int, height: Int): Float {
		val frame = DistantAircraftFrame()
		for (step in 0 until 86_400) {
			val time = step * 0.25f

			val hasAircraft = frame.sample(width.toFloat(), height.toFloat(), time)
			val nearSunX = abs(frame.planeX - width * 0.72f) < 1f
			val nearSunY = abs(frame.planeY - height * 0.2141f) < 1f
			if (hasAircraft && nearSunX && nearSunY) {
				return time
			}
		}

		error("No solar transit found in one scene-clock period")
	}

	private fun colorDistance(first: Int, second: Int) =
		abs(Color.red(first) - Color.red(second)) + abs(Color.green(first) - Color.green(second)) + abs(Color.blue(first) - Color.blue(second))

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
