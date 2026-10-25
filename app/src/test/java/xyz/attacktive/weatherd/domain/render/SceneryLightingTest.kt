package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

class SceneryLightingTest {
	private val clear = SceneParams(DayPhase.DAY, 0f, 0f, null, false, 0f)

	@Test
	fun clearDayPreservesOriginalArtworkAndAlpha() {
		for (plane in SceneryPlane.entries) {
			val matrix = matrix(clear, plane)
			assertArrayEquals(floatArrayOf(35f, 90f, 170f), transform(matrix, 35f, 90f, 170f), 0.0001f)
			assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f), matrix.copyOfRange(15, 20), 0f)
		}
	}

	@Test
	fun lowSunWarmsMountainHighlightsWithoutWarmingShadowOrFoliageColors() {
		for (phase in listOf(DayPhase.DAWN, DayPhase.DUSK)) {
			val params = clear.copy(dayPhase = phase, celestialProgress = 0.5f)
			val mountains = matrix(params, SceneryPlane.FAR)
			val highlight = transform(mountains, 180f, 210f, 230f)
			val shadow = transform(mountains, 65f, 110f, 155f)
			assertTrue("$phase mountain highlights must turn peach instead of cyan", highlight[0] > highlight[1] && highlight[1] > highlight[2])
			assertTrue("$phase mountain shadows must retain cool separation", shadow[2] > shadow[0] && shadow[2] > shadow[1])
			assertTrue("$phase must retain mountain relief", highlight.sum() > shadow.sum())
			assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f), mountains.copyOfRange(15, 20), 0f)
			val foliage = transform(matrix(params, SceneryPlane.NEAR), 32f, 58f, 22f)
			assertTrue("$phase foliage must retain its green material instead of receiving mountain alpenglow", foliage[1] > foliage[0] && foliage[1] > foliage[2])
		}
	}

	@Test
	fun opaqueWeatherSuppressesMountainAlpenglow() {
		for (weather in listOf(clear.copy(cloudiness = 1f), clear.copy(thunder = true))) {
			val daylight = matrix(weather, SceneryPlane.FAR)
			for (phase in listOf(DayPhase.DAWN, DayPhase.DUSK)) {
				assertArrayEquals(daylight, matrix(weather.copy(dayPhase = phase, celestialProgress = 0.5f), SceneryPlane.FAR), 0.0001f)
			}
		}
	}

	@Test
	fun snowStaysBrighterThanRainAndThunderWhileFogSoftensDistantContrastMore() {
		val snow = clear.copy(precipitation = Precipitation(PrecipitationKind.SNOW, 1f, 1f))
		val rain = clear.copy(precipitation = Precipitation(PrecipitationKind.RAIN, 1f, 1f))
		for (plane in SceneryPlane.entries) {
			val snowPixel = transform(matrix(snow, plane), 100f, 120f, 160f)
			val rainPixel = transform(matrix(rain, plane), 100f, 120f, 160f)
			val stormPixel = transform(matrix(rain.copy(thunder = true), plane), 100f, 120f, 160f)
			assertTrue(snowPixel.sum() > rainPixel.sum())
			assertTrue(rainPixel.sum() > stormPixel.sum())
		}

		val fog = clear.copy(fogDensity = 1f)
		assertTrue(contrast(matrix(fog, SceneryPlane.FAR)) < contrast(matrix(fog, SceneryPlane.NEAR)))
	}

	@Test
	fun nightNeutralizesBlueMountainsAndRetainsDifferentDarkFoliageTones() {
		val night = clear.copy(dayPhase = DayPhase.NIGHT)
		val mountain = transform(matrix(night, SceneryPlane.FAR), 35f, 90f, 170f)
		assertTrue("Night mountain blue must not retain daylight chroma", mountain.max() - mountain.min() < 12f)
		val trees = matrix(night, SceneryPlane.NEAR)
		val shadow = transform(trees, 8f, 16f, 6f)
		val crown = transform(trees, 32f, 58f, 22f)
		assertTrue("Ambient illumination must retain shadow detail", shadow.min() > 5f)
		assertTrue("Ambient illumination must not flatten foliage", crown.sum() - shadow.sum() > 15f)
		assertTrue("Near foliage must stay darker than the mountain ridge", crown.sum() < mountain.sum())
	}

	@Test
	fun nightBrightnessIsContinuousAtEveryPhaseBoundaryIncludingBlackNight() {
		for (scale in listOf(0f, 0.2f, 1f)) {
			for (plane in SceneryPlane.entries) {
				val night = clear.copy(dayPhase = DayPhase.NIGHT, nightBrightnessScale = scale)
				val dawn = night.copy(dayPhase = DayPhase.DAWN, celestialProgress = 0f)
				val day = night.copy(dayPhase = DayPhase.DAY)
				val dusk = night.copy(dayPhase = DayPhase.DUSK, celestialProgress = 0f)
				assertArrayEquals(matrix(night, plane), matrix(dawn, plane), 0.0001f)
				assertArrayEquals(matrix(day, plane), matrix(dawn.copy(celestialProgress = 1f), plane), 0.0001f)
				assertArrayEquals(matrix(day, plane), matrix(dusk, plane), 0.0001f)
				assertArrayEquals(matrix(night, plane), matrix(dusk.copy(celestialProgress = 1f), plane), 0.0001f)
				for (phase in listOf(DayPhase.DAWN, DayPhase.DUSK)) {
					for (step in 0..100) {
						val progress = step / 100f
						val before = night.copy(dayPhase = phase, celestialProgress = (progress - 0.00001f).coerceAtLeast(0f))
						val after = night.copy(dayPhase = phase, celestialProgress = (progress + 0.00001f).coerceAtMost(1f))
						val left = transform(matrix(before, plane), 35f, 90f, 170f)
						val right = transform(matrix(after, plane), 35f, 90f, 170f)
						for (channel in 0..2) {
							assertTrue("$phase at $progress must not have a visible discontinuity", abs(right[channel] - left[channel]) < 1f)
						}
					}
				}
			}
		}
	}

	@Test
	fun cirrusAndHumidityHazeDoNotBecomeOpaqueOvercastOrSemanticFog() {
		val highClouds = clear.copy(cloudiness = 1f, cloudLayers = SceneCloudLayers(0f, 0f, 1f))
		for (plane in SceneryPlane.entries) {
			assertArrayEquals(matrix(clear, plane), matrix(highClouds, plane), 0f)
			assertArrayEquals(matrix(clear, plane), matrix(clear.copy(humidityHazeDensity = 0.25f), plane), 0f)
		}
	}

	private fun matrix(params: SceneParams, plane: SceneryPlane) = FloatArray(20).also { writeSceneryColorMatrix(it, params, plane) }

	private fun transform(matrix: FloatArray, red: Float, green: Float, blue: Float) = FloatArray(3) { row ->
		val start = row * 5
		matrix[start] * red + matrix[start + 1] * green + matrix[start + 2] * blue + matrix[start + 4]
	}

	private fun contrast(matrix: FloatArray) = transform(matrix, 180f, 180f, 180f).sum() - transform(matrix, 40f, 40f, 40f).sum()
}
