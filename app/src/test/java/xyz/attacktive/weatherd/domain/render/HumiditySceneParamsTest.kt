package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.CloudCover
import xyz.attacktive.weatherd.domain.model.CloudLayers
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.weather.conditionForWmoCode

class HumiditySceneParamsTest {
	@Test
	fun `humidity haze has a smooth bounded onset`() {
		assertEquals(0f, humidityHazeDensityFor(null), TOLERANCE)
		assertEquals(0f, humidityHazeDensityFor(Double.NaN), TOLERANCE)
		assertEquals(0f, humidityHazeDensityFor(Double.NEGATIVE_INFINITY), TOLERANCE)
		assertEquals(0f, humidityHazeDensityFor(Double.POSITIVE_INFINITY), TOLERANCE)
		assertEquals(0f, humidityHazeDensityFor(-10.0), TOLERANCE)
		assertEquals(0f, humidityHazeDensityFor(85.0), TOLERANCE)
		assertTrue(humidityHazeDensityFor(85.001) > 0f)
		assertEquals(0.0648148f, humidityHazeDensityFor(90.0), TOLERANCE)
		assertEquals(0.125f, humidityHazeDensityFor(92.5), TOLERANCE)
		assertEquals(0.1851852f, humidityHazeDensityFor(95.0), TOLERANCE)
		assertEquals(0.25f, humidityHazeDensityFor(100.0), TOLERANCE)
		assertEquals(0.25f, humidityHazeDensityFor(120.0), TOLERANCE)

		var previous = humidityHazeDensityFor(85.0)
		for (humidity in 86..100) {
			val current = humidityHazeDensityFor(humidity.toDouble())

			assertTrue("haze density must be monotonic at $humidity%", current >= previous)
			previous = current
		}
	}

	@Test
	fun `unknown humidity does not invent haze`() {
		val humidities = listOf<Double?>(null, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0, 60.0, 85.0)

		for (humidity in humidities) {
			val params = sceneParamsFor(snapshot(weatherCode = 0, relativeHumidityPercent = humidity), NOW)

			assertEquals("humidity=$humidity", 0f, params.fogDensity, TOLERANCE)
			assertEquals("humidity=$humidity", 0f, params.humidityHazeDensity, TOLERANCE)
		}
	}

	@Test
	fun `reported fog overrides humidity haze`() {
		for (weatherCode in listOf(45, 48)) {
			for (humidity in listOf<Double?>(null, 50.0, 100.0)) {
				val params = sceneParamsFor(snapshot(weatherCode, humidity), NOW)

				assertEquals("code=$weatherCode humidity=$humidity", 1f, params.fogDensity, TOLERANCE)
				assertEquals("code=$weatherCode humidity=$humidity", 0f, params.humidityHazeDensity, TOLERANCE)
				assertEquals("code=$weatherCode humidity=$humidity", 1f, effectiveFogDensity(params), TOLERANCE)
			}
		}
	}

	@Test
	fun `humidity does not change precipitation or cloud observations`() {
		for (weatherCode in listOf(61, 71, 95)) {
			val ordinary = sceneParamsFor(snapshot(weatherCode, 40.0, precipitationMillimeters = 4.0), NOW)
			val humid = sceneParamsFor(snapshot(weatherCode, 100.0, precipitationMillimeters = 4.0), NOW)

			assertEquals(ordinary.precipitation, humid.precipitation)
			assertEquals(ordinary.thunder, humid.thunder)
			assertEquals(ordinary.cloudiness, humid.cloudiness, TOLERANCE)
			assertEquals(ordinary.cloudLayers, humid.cloudLayers)
			assertEquals(ordinary.windFactor, humid.windFactor, TOLERANCE)
			assertEquals(0f, ordinary.humidityHazeDensity, TOLERANCE)
			assertEquals(0.25f, humid.humidityHazeDensity, TOLERANCE)
		}
	}

	@Test
	fun `derived haze changes invalidate the backdrop without a clock change`() {
		val moderate = sceneParamsFor(snapshot(weatherCode = 0, relativeHumidityPercent = 92.5), NOW)
		val higher = sceneParamsFor(snapshot(weatherCode = 0, relativeHumidityPercent = 95.0), NOW)

		assertNotEquals(moderate.humidityHazeDensity, higher.humidityHazeDensity)
		assertNotEquals(backdropSignature(moderate), backdropSignature(higher))
	}

	@Test
	fun `below onset humidity changes retain the backdrop signature`() {
		val lower = sceneParamsFor(snapshot(weatherCode = 0, relativeHumidityPercent = 40.0), NOW)
		val higher = sceneParamsFor(snapshot(weatherCode = 0, relativeHumidityPercent = 60.0), NOW)
		val hazy = sceneParamsFor(snapshot(weatherCode = 0, relativeHumidityPercent = 95.0), NOW)
		val returned = sceneParamsFor(snapshot(weatherCode = 0, relativeHumidityPercent = 40.0), NOW)

		assertEquals(0f, lower.humidityHazeDensity, TOLERANCE)
		assertEquals(0f, higher.humidityHazeDensity, TOLERANCE)
		assertEquals(backdropSignature(lower), backdropSignature(higher))
		assertNotEquals(backdropSignature(lower), backdropSignature(hazy))
		assertEquals(backdropSignature(lower), backdropSignature(returned))
	}

	private fun snapshot(
		weatherCode: Int,
		relativeHumidityPercent: Double?,
		precipitationMillimeters: Double = 0.0
	) = WeatherSnapshot(
		observation = WeatherObservation(
			condition = conditionForWmoCode(weatherCode),
			isDay = true,
			temperatureCelsius = 18.0,
			precipitationMillimeters = precipitationMillimeters,
			windSpeedKilometersPerHour = 12.0,
			cloudCover = CloudCover(
				totalPercent = 70,
				layers = CloudLayers(lowPercent = 40, midPercent = 20, highPercent = 10)
			),
			relativeHumidityPercent = relativeHumidityPercent
		),
		observedAtEpochSeconds = NOW,
		sunriseEpochSeconds = NOW - 10_000L,
		sunsetEpochSeconds = NOW + 10_000L
	)

	companion object {
		private const val NOW = 1_800_000_000L
		private const val TOLERANCE = 0.0001f
	}
}
