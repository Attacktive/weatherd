package xyz.attacktive.weatherd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.render.sceneParamsFor
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STEADY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

class SceneParamsTest {
	@Test
	fun `derives scene features from a snowy snapshot`() {
		val snapshot = snapshot(weatherCode = 71, precipitationMillimeters = 3.0, windSpeedKilometersPerHour = 20.0, cloudCoverPercent = 100)

		val params = sceneParamsFor(snapshot, NOW)

		assertEquals(DayPhase.DAY, params.dayPhase)
		val precipitation = params.precipitation
		assertNotNull(precipitation)
		assertEquals(PrecipitationKind.SNOW, precipitation!!.kind)
		assertEquals(SEVERITY_STEADY, precipitation.severity, 0.0001f)
		assertEquals(0.3f, precipitation.observed, 0.0001f)
		assertEquals(1f, params.cloudiness, 0.0001f)
		assertEquals(0f, params.fogDensity, 0.0001f)
		assertFalse(params.thunder)
		assertEquals(0.5f, params.windFactor, 0.0001f)
	}

	@Test
	fun `dry snapshot has no precipitation and keeps cloud cover`() {
		val snapshot = snapshot(weatherCode = 2, precipitationMillimeters = 0.0, windSpeedKilometersPerHour = 10.0, cloudCoverPercent = 40)

		val params = sceneParamsFor(snapshot, NOW)

		assertNull(params.precipitation)
		assertEquals(0.4f, params.cloudiness, 0.0001f)
		assertEquals(0f, params.fogDensity, 0.0001f)
		assertFalse(params.thunder)
	}

	@Test
	fun `thunderstorm combines rain severity with thunder and independent cloud cover`() {
		val snapshot = snapshot(weatherCode = 95, precipitationMillimeters = 8.0, windSpeedKilometersPerHour = 36.0, cloudCoverPercent = 90)

		val params = sceneParamsFor(snapshot, NOW)

		val precipitation = params.precipitation
		assertNotNull(precipitation)
		assertEquals(PrecipitationKind.RAIN, precipitation!!.kind)
		assertEquals(SEVERITY_STORM, precipitation.severity, 0.0001f)
		assertEquals(0.8f, precipitation.observed, 0.0001f)
		assertTrue(params.thunder)
		assertEquals(0.9f, params.cloudiness, 0.0001f)
		assertEquals(0f, params.fogDensity, 0.0001f)
		assertEquals(0.9f, params.windFactor, 0.0001f)
	}

	@Test
	fun `fog sets density without inventing precipitation`() {
		val snapshot = snapshot(weatherCode = 45, precipitationMillimeters = 0.0, windSpeedKilometersPerHour = 4.0, cloudCoverPercent = 100)

		val params = sceneParamsFor(snapshot, NOW)

		assertNull(params.precipitation)
		assertEquals(1f, params.fogDensity, 0.0001f)
		assertEquals(1f, params.cloudiness, 0.0001f)
		assertFalse(params.thunder)
	}

	@Test
	fun `drizzle keeps rain severity while cloud cover stays independent`() {
		// A light shower under a broken sky: the WMO code only sets precip; cover comes from the API field.
		val snapshot = snapshot(weatherCode = 51, precipitationMillimeters = 1.0, windSpeedKilometersPerHour = 12.0, cloudCoverPercent = 35)

		val params = sceneParamsFor(snapshot, NOW)

		val precipitation = params.precipitation
		assertNotNull(precipitation)
		assertEquals(PrecipitationKind.RAIN, precipitation!!.kind)
		assertEquals(SEVERITY_DRIZZLE, precipitation.severity, 0.0001f)
		assertEquals(0.1f, precipitation.observed, 0.0001f)
		assertEquals(0.35f, params.cloudiness, 0.0001f)
		assertEquals(0f, params.fogDensity, 0.0001f)
		assertFalse(params.thunder)
	}

	private fun snapshot(weatherCode: Int, precipitationMillimeters: Double, windSpeedKilometersPerHour: Double, cloudCoverPercent: Int) = WeatherSnapshot(
		observation = WeatherObservation(
			weatherCode = weatherCode,
			isDay = false,
			temperatureCelsius = -2.0,
			precipitationMillimeters = precipitationMillimeters,
			windSpeedKilometersPerHour = windSpeedKilometersPerHour,
			cloudCoverPercent = cloudCoverPercent
		),
		observedAtEpochSeconds = NOW,
		sunriseEpochSeconds = 1_000_000L,
		sunsetEpochSeconds = 1_050_000L
	)

	companion object {
		private const val NOW = 1_025_000L
	}
}
