package xyz.attacktive.weatherd.domain.render

import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.CloudCover
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.repository.LocationRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundState
import xyz.attacktive.weatherd.domain.repository.ReverseGeocodingRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.repository.WeatherRepository
import xyz.attacktive.weatherd.domain.weather.conditionForWmoCode
import xyz.attacktive.weatherd.util.AppLogger

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSolarWeatherSceneProviderTest {
	private val romeSettings = AppSettings(useDeviceLocation = false, manualLatitude = 41.9028, manualLongitude = 12.4964)
	private val persistedSettings = MutableStateFlow(romeSettings)
	private val originalTimeZone = TimeZone.getDefault()
	private val locationRepository = mockk<LocationRepository>()
	private val weatherRepository = mockk<WeatherRepository>()
	private val settingsRepository = mockk<SettingsRepository> {
		every { defaults } returns romeSettings
		every { settings } returns persistedSettings
	}

	private val photoRepository = mockk<PhotoBackgroundRepository> {
		every { state } returns MutableStateFlow(PhotoBackgroundState(emptySet()))
	}

	private val applicationScope = CoroutineScope(UnconfinedTestDispatcher())
	private val provider = WeatherSceneProvider(
		mockk<Context>(relaxed = true),
		locationRepository,
		weatherRepository,
		mockk<ReverseGeocodingRepository>(),
		settingsRepository,
		photoRepository,
		mockk<AppLogger>(relaxed = true),
		applicationScope,
		weatherCacheFixture()
	)

	@After
	fun cancelCollection() {
		applicationScope.cancel()
		TimeZone.setDefault(originalTimeZone)
	}

	@Test
	fun `offline cold start before Rome dawn stays night after reconnect`() = runTest {
		val now = epoch("2026-10-15T04:03:14Z")
		assertEquals(DayPhase.NIGHT, provider.paramsFor(now).dayPhase)
		assertEquals(DayPhase.DAY, provider.paramsFor(epoch("2026-10-15T12:00:00Z")).dayPhase)

		coEvery { weatherRepository.current(41.9028, 12.4964) } returns Result.failure(IllegalStateException("Offline"))
		assertTrue(provider.refreshWithResult(now, force = true).isFailure)
		assertEquals(DayPhase.NIGHT, provider.paramsFor(now).dayPhase)

		coEvery { weatherRepository.current(41.9028, 12.4964) } returns Result.success(solarSnapshot(now))
		assertTrue(provider.refreshWithResult(now, force = true).isSuccess)
		assertEquals(DayPhase.NIGHT, provider.paramsFor(now).dayPhase)
	}

	@Test
	fun `a device fix acquired before a failed weather request supplies offline lighting`() = runTest {
		persistedSettings.value = romeSettings.copy(useDeviceLocation = true)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(41.9028, 12.4964)
		coEvery { weatherRepository.current(41.9028, 12.4964) } returns Result.failure(IllegalStateException("Offline"))

		assertTrue(provider.refreshWithResult(epoch("2026-10-15T04:03:14Z"), force = true).isFailure)
		assertEquals(DayPhase.NIGHT, provider.paramsFor(epoch("2026-10-15T04:03:14Z")).dayPhase)
		assertEquals(DayPhase.DAY, provider.paramsFor(epoch("2026-10-15T12:00:00Z")).dayPhase)
	}

	@Test
	fun `captured manual locations do not follow the phone timezone or later settings`() {
		TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
		val now = epoch("2026-10-15T12:00:00Z")
		val romeState = provider.sceneState.value
		persistedSettings.value = romeSettings.copy(manualLatitude = 37.7749, manualLongitude = -122.4194)

		assertEquals(DayPhase.NIGHT, provider.paramsFor(now).dayPhase)
		assertEquals(DayPhase.DAY, provider.paramsFor(now, romeState).dayPhase)
	}

	@Test
	fun `device mode ignores stored manual coordinates for offline lighting`() {
		val state = WeatherSceneState(
			settings = romeSettings.copy(useDeviceLocation = true),
			deviceLocation = GeoLocation(37.7749, -122.4194)
		)

		assertEquals(DayPhase.NIGHT, provider.paramsFor(epoch("2026-10-15T12:00:00Z"), state).dayPhase)
	}

	@Test
	fun `unknown location uses the supplied instant rather than the running wall clock`() {
		TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"))
		val state = WeatherSceneState(settings = AppSettings())

		assertEquals(DayPhase.NIGHT, provider.paramsFor(epoch("2026-10-15T00:00:00Z"), state).dayPhase)
		assertEquals(DayPhase.DAY, provider.paramsFor(epoch("2026-10-15T12:00:00Z"), state).dayPhase)
	}

	@Test
	fun `offline dawn advances celestial progress instead of pinning the sun at its midpoint`() {
		val early = provider.paramsFor(epoch("2026-10-15T04:50:00Z"))
		val late = provider.paramsFor(epoch("2026-10-15T05:40:00Z"))

		assertEquals(DayPhase.DAWN, early.dayPhase)
		assertEquals(DayPhase.DAWN, late.dayPhase)
		assertTrue(early.celestialProgress > 0f)
		assertTrue(late.celestialProgress < 1f)
		assertTrue(early.celestialProgress < late.celestialProgress)
	}

	@Test
	fun `polar event absence follows local summer and winter rather than the clock`() {
		persistedSettings.value = romeSettings.copy(manualLatitude = 78.0, manualLongitude = 15.0)

		assertEquals(DayPhase.DAY, provider.paramsFor(epoch("2026-06-21T00:00:00Z")).dayPhase)
		assertEquals(DayPhase.NIGHT, provider.paramsFor(epoch("2026-12-21T12:00:00Z")).dayPhase)
	}

	@Test
	fun `complete provider solar times retain precedence over local estimates`() = runTest {
		val now = epoch("2026-10-15T04:03:14Z")
		val providerSolarTimes = solarSnapshot(now).copy(
			sunriseEpochSeconds = epoch("2026-10-15T00:00:00Z"),
			sunsetEpochSeconds = epoch("2026-10-15T20:00:00Z")
		)

		coEvery { weatherRepository.current(41.9028, 12.4964) } returns Result.success(providerSolarTimes)

		provider.refreshWithResult(now, force = true)

		assertEquals(DayPhase.DAY, provider.paramsFor(now).dayPhase)
	}

	private fun solarSnapshot(now: Long) = WeatherSnapshot(
		observation = WeatherObservation(
			condition = conditionForWmoCode(0),
			isDay = false,
			temperatureCelsius = 10.0,
			precipitationMillimeters = 0.0,
			windSpeedKilometersPerHour = 5.0,
			cloudCover = CloudCover(0)
		),
		observedAtEpochSeconds = now,
		sunriseEpochSeconds = epoch("2026-10-15T05:22:00Z"),
		sunsetEpochSeconds = epoch("2026-10-15T16:28:00Z")
	)

	private fun epoch(value: String) = Instant.parse(value).epochSecond
}
