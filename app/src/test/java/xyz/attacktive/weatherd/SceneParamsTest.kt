package xyz.attacktive.weatherd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.BackdropScene
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
		assertEquals(0.4856f, precipitation.observed, 0.0001f)
		assertEquals(1f, params.cloudiness, 0.0001f)
		assertEquals(0f, params.fogDensity, 0.0001f)
		assertFalse(params.thunder)
		assertEquals(0.6598f, params.windFactor, 0.0001f)
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
		assertEquals(0.8747f, precipitation.observed, 0.0001f)
		assertTrue(params.thunder)
		assertEquals(0.9f, params.cloudiness, 0.0001f)
		assertEquals(0f, params.fogDensity, 0.0001f)
		assertEquals(0.9387f, params.windFactor, 0.0001f)
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
		assertEquals(0.2512f, precipitation.observed, 0.0001f)
		assertEquals(0.35f, params.cloudiness, 0.0001f)
		assertEquals(0f, params.fogDensity, 0.0001f)
		assertFalse(params.thunder)
	}

	@Test
	fun `a light breeze reaches well past the raw linear fraction`() {
		// 10 km/h is a quarter of the 40 km/h ceiling, but a quarter-strength wind is invisible — the curve lifts it to nearly half.
		val snapshot = snapshot(weatherCode = 2, precipitationMillimeters = 0.0, windSpeedKilometersPerHour = 10.0, cloudCoverPercent = 40)

		val params = sceneParamsFor(snapshot, NOW)

		assertEquals(0.4353f, params.windFactor, 0.0001f)
	}

	@Test
	fun `a gale still reads as near maximum`() {
		// The curve must not eat the top of the range: 36 of 40 km/h stays close to 1.
		val snapshot = snapshot(weatherCode = 2, precipitationMillimeters = 0.0, windSpeedKilometersPerHour = 36.0, cloudCoverPercent = 40)

		val params = sceneParamsFor(snapshot, NOW)

		assertEquals(0.9387f, params.windFactor, 0.0001f)
	}

	@Test
	fun `wind past the ceiling saturates instead of overflowing`() {
		val snapshot = snapshot(weatherCode = 2, precipitationMillimeters = 0.0, windSpeedKilometersPerHour = 120.0, cloudCoverPercent = 40)

		val params = sceneParamsFor(snapshot, NOW)

		assertEquals(1f, params.windFactor, 0.0001f)
	}

	@Test
	fun `dead calm stays at zero`() {
		val snapshot = snapshot(weatherCode = 2, precipitationMillimeters = 0.0, windSpeedKilometersPerHour = 0.0, cloudCoverPercent = 40)

		val params = sceneParamsFor(snapshot, NOW)

		assertEquals(0f, params.windFactor, 0.0001f)
	}

	@Test
	fun `the wind scale rides through without touching the observation`() {
		val snapshot = snapshot(weatherCode = 2, precipitationMillimeters = 0.0, windSpeedKilometersPerHour = 12.0, cloudCoverPercent = 40)

		val params = sceneParamsFor(snapshot, NOW, windScale = 0.5f)

		assertEquals(0.4856f, params.windFactor, 0.0001f)
		assertEquals(0.5f, params.windScale, 0.0001f)
	}

	@Test
	fun `scaling the wind up does not clamp windFactor`() {
		val snapshot = snapshot(weatherCode = 95, precipitationMillimeters = 8.0, windSpeedKilometersPerHour = 36.0, cloudCoverPercent = 90)

		val params = sceneParamsFor(snapshot, NOW, windScale = 2f)

		assertEquals(0.9387f, params.windFactor, 0.0001f)
		assertEquals(2f, params.windScale, 0.0001f)
	}

	@Test
	fun `the precipitation scale rides through without touching the observation`() {
		// The observation stays an honest reading of the weather; the preference is applied later, at the drop count.
		val snapshot = snapshot(weatherCode = 51, precipitationMillimeters = 1.0, windSpeedKilometersPerHour = 12.0, cloudCoverPercent = 35)

		val params = sceneParamsFor(snapshot, NOW, precipitationScale = 0.25f)

		assertEquals(0.2512f, params.precipitation!!.observed, 0.0001f)
		assertEquals(0.25f, params.precipitationScale, 0.0001f)
	}

	@Test
	fun `the scales leave a dry calm snapshot alone`() {
		val snapshot = snapshot(weatherCode = 2, precipitationMillimeters = 0.0, windSpeedKilometersPerHour = 0.0, cloudCoverPercent = 40)

		val params = sceneParamsFor(snapshot, NOW, precipitationScale = 2f, windScale = 2f)

		assertNull(params.precipitation)
		assertEquals(0f, params.windFactor, 0.0001f)
		assertEquals(2f, params.precipitationScale, 0.0001f)
		assertEquals(2f, params.windScale, 0.0001f)
	}

	@Test
	fun `a changed photo revision makes otherwise identical params unequal`() {
		val snapshot = snapshot(weatherCode = 2, precipitationMillimeters = 0.0, windSpeedKilometersPerHour = 10.0, cloudCoverPercent = 40)

		val before = sceneParamsFor(snapshot, NOW, BackdropScene.PHOTO, photoRevision = 4)
		val after = sceneParamsFor(snapshot, NOW, BackdropScene.PHOTO, photoRevision = 5)

		// The wallpaper caches its backdrop against params.copy(moonPhase = 0f, celestialProgress = 0f) and the preview remembers against the params themselves, so both redraw on exactly this inequality and nothing else.
		assertNotEquals(before, after)
		assertNotEquals(before.copy(moonPhase = 0f, celestialProgress = 0f), after.copy(moonPhase = 0f, celestialProgress = 0f))

		// The same weather with no photo involved must stay at rest, which is what leaves every scene but PHOTO rasterizing exactly as often as it did before.
		assertEquals(0, sceneParamsFor(snapshot, NOW).photoRevision)
		assertEquals(sceneParamsFor(snapshot, NOW), sceneParamsFor(snapshot, NOW))
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
