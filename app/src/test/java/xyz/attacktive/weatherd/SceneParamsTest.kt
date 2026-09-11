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
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.render.OverlayLabels
import xyz.attacktive.weatherd.domain.render.SceneParams
import xyz.attacktive.weatherd.domain.render.backdropSignature
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

		// The wallpaper caches its backdrop against backdropSignature and the preview remembers against it too, so both redraw on exactly this inequality and nothing else.
		assertNotEquals(before, after)
		assertNotEquals(backdropSignature(before), backdropSignature(after))

		// The same weather with no photo involved must stay at rest, which is what leaves every scene but PHOTO rasterizing exactly as often as it did before.
		assertEquals(0, sceneParamsFor(snapshot, NOW).photoRevision)
		assertEquals(sceneParamsFor(snapshot, NOW), sceneParamsFor(snapshot, NOW))
	}

	@Test
	fun `the backdrop signature zeroes the moon and the celestial arc and nothing else`() {
		val params = fullyPopulatedParams()

		val signature = backdropSignature(params)

		assertEquals(0f, signature.moonPhase, 0.0001f)
		assertEquals(0f, signature.celestialProgress, 0.0001f)

		// Putting just those two back has to reproduce the original, which is what proves nothing else was touched.
		assertEquals(params, signature.copy(moonPhase = params.moonPhase, celestialProgress = params.celestialProgress))
	}

	@Test
	fun `params differing only in the moon and the celestial arc share one signature`() {
		val params = fullyPopulatedParams()

		// A celestial tick a few minutes into dusk: the foreground moves, the backdrop cannot show any of it.
		val ticked = params.copy(moonPhase = 0.93f, celestialProgress = 0.78f)

		assertNotEquals(params, ticked)
		assertEquals(backdropSignature(params), backdropSignature(ticked))
	}

	@Test
	fun `every other field still separates two signatures`() {
		val params = fullyPopulatedParams()

		// Deliberately the whole rest of the type, not just what renderBackdrop reads today: a field added later invalidates the backdrop until someone lists it as foreground-only.
		val changed = listOf(
			"dayPhase" to params.copy(dayPhase = DayPhase.NIGHT),
			"cloudiness" to params.copy(cloudiness = 0.95f),
			"fogDensity" to params.copy(fogDensity = 0.95f),
			"precipitation" to params.copy(precipitation = null),
			"thunder" to params.copy(thunder = false),
			"windFactor" to params.copy(windFactor = 0.95f),
			"precipitationScale" to params.copy(precipitationScale = 2f),
			"windScale" to params.copy(windScale = 2f),
			"backdropScene" to params.copy(backdropScene = BackdropScene.NONE),
			"photoRevision" to params.copy(photoRevision = params.photoRevision + 1),
			"overlayLabels" to params.copy(overlayLabels = null)
		)

		for ((field, mutated) in changed) {
			assertNotEquals("a changed $field must invalidate the backdrop", backdropSignature(params), backdropSignature(mutated))
		}
	}

	/** A scene with every field at a distinctive value, so a signature that dropped one would show up as an equality that should not hold. */
	private fun fullyPopulatedParams() = SceneParams(
		dayPhase = DayPhase.DUSK,
		cloudiness = 0.4f,
		fogDensity = 0.2f,
		precipitation = Precipitation(kind = PrecipitationKind.RAIN, severity = SEVERITY_STEADY, observed = 0.45f),
		thunder = true,
		windFactor = 0.3f,
		precipitationScale = 1.5f,
		windScale = 0.5f,
		moonPhase = 0.17f,
		celestialProgress = 0.62f,
		backdropScene = BackdropScene.PHOTO,
		photoRevision = 7,
		overlayLabels = OverlayLabels(weather = "Rain · 10°", location = "Seoul")
	)

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
