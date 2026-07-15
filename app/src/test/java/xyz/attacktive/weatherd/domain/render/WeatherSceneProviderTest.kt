package xyz.attacktive.weatherd.domain.render

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.domain.model.TemperatureUnit
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.repository.LocationRepository
import xyz.attacktive.weatherd.domain.repository.ReverseGeocodingRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.repository.WeatherRepository
import xyz.attacktive.weatherd.util.AppLogger

class WeatherSceneProviderTest {
	private val locationRepository = mockk<LocationRepository>()
	private val weatherRepository = mockk<WeatherRepository>()
	private val reverseGeocodingRepository = mockk<ReverseGeocodingRepository>()
	private val settingsRepository = mockk<SettingsRepository>()
	private val logger = mockk<AppLogger>(relaxed = true)

	private val provider = WeatherSceneProvider(locationRepository, weatherRepository, reverseGeocodingRepository, settingsRepository, logger)

	@Test
	fun `changing location settings refetches within the throttle window`() = runTest {
		val munich = AppSettings(useDeviceLocation = false, manualLatitude = 48.14, manualLongitude = 11.58)
		every { settingsRepository.settings } returns flowOf(munich)
		coEvery { weatherRepository.current(48.14, 11.58) } returns Result.success(snapshotWith(weatherCode = 61))

		provider.refresh(1_000_000L)

		val device = AppSettings(useDeviceLocation = true)
		every { settingsRepository.settings } returns flowOf(device)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_060L)

		coVerify(exactly = 1) { weatherRepository.current(52.52, 13.40) }
	}

	@Test
	fun `an unchanged location is throttled within the interval`() = runTest {
		val device = AppSettings(useDeviceLocation = true, updateIntervalMinutes = 30)
		every { settingsRepository.settings } returns flowOf(device)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_000L)
		provider.refresh(1_000_060L)

		coVerify(exactly = 1) { weatherRepository.current(52.52, 13.40) }
	}

	@Test
	fun `the backdrop choice reaches the scene params even when the refresh is throttled`() = runTest {
		val device = AppSettings(useDeviceLocation = true, backdropScene = BackdropScene.MOUNTAINS)
		every { settingsRepository.settings } returns flowOf(device)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_000L)
		assertEquals(BackdropScene.MOUNTAINS, provider.paramsFor(1_000_030L).backdropScene)

		// The user switches scenes; the next refresh is inside the throttle window but must still pick the new choice up.
		every { settingsRepository.settings } returns flowOf(device.copy(backdropScene = BackdropScene.BEACH))
		provider.refresh(1_000_060L)

		assertEquals(BackdropScene.BEACH, provider.paramsFor(1_000_090L).backdropScene)
	}

	@Test
	fun `the weather label with temperature reaches the scene params`() = runTest {
		every { settingsRepository.settings } returns flowOf(AppSettings(useDeviceLocation = true))
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))

		provider.refresh(1_000_000L)

		assertEquals(OverlayLabels(weather = "Rain · 10°", location = null), provider.paramsFor(1_000_030L).overlayLabels)
	}

	@Test
	fun `the weather label falls back to the bare temperature for an unknown code`() = runTest {
		every { settingsRepository.settings } returns flowOf(AppSettings(useDeviceLocation = true))
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 1234))

		provider.refresh(1_000_000L)

		assertEquals("10°", provider.paramsFor(1_000_030L).overlayLabels?.weather)
	}

	@Test
	fun `no labels are drawn when both toggles are off`() = runTest {
		every { settingsRepository.settings } returns flowOf(AppSettings(useDeviceLocation = true, showWeatherLabel = false, showLocationLabel = false))
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))

		provider.refresh(1_000_000L)

		assertNull(provider.paramsFor(1_000_030L).overlayLabels)
	}

	@Test
	fun `the manual city label is used without reverse geocoding`() = runTest {
		val munich = AppSettings(useDeviceLocation = false, manualLatitude = 48.14, manualLongitude = 11.58, manualLocationLabel = "Munich, Germany", showLocationLabel = true)
		every { settingsRepository.settings } returns flowOf(munich)
		coEvery { weatherRepository.current(48.14, 11.58) } returns Result.success(snapshotWith(weatherCode = 63))

		provider.refresh(1_000_000L)

		assertEquals(OverlayLabels(weather = "Rain · 10°", location = "Munich, Germany"), provider.paramsFor(1_000_030L).overlayLabels)
		coVerify(exactly = 0) { reverseGeocodingRepository.placeName(any(), any()) }
	}

	@Test
	fun `the device place name is reverse geocoded when the location label is on`() = runTest {
		every { settingsRepository.settings } returns flowOf(AppSettings(useDeviceLocation = true, showLocationLabel = true))
		coEvery { locationRepository.currentLocation() } returns GeoLocation(37.57, 126.98)
		coEvery { weatherRepository.current(37.57, 126.98) } returns Result.success(snapshotWith(weatherCode = 63))
		coEvery { reverseGeocodingRepository.placeName(37.57, 126.98) } returns "Seoul"

		provider.refresh(1_000_000L)

		assertEquals("Seoul", provider.paramsFor(1_000_030L).overlayLabels?.location)
	}

	@Test
	fun `a failed reverse geocode hides only the location line`() = runTest {
		every { settingsRepository.settings } returns flowOf(AppSettings(useDeviceLocation = true, showLocationLabel = true))
		coEvery { locationRepository.currentLocation() } returns GeoLocation(37.57, 126.98)
		coEvery { weatherRepository.current(37.57, 126.98) } returns Result.success(snapshotWith(weatherCode = 63))
		coEvery { reverseGeocodingRepository.placeName(37.57, 126.98) } returns null

		provider.refresh(1_000_000L)

		assertEquals(OverlayLabels(weather = "Rain · 10°", location = null), provider.paramsFor(1_000_030L).overlayLabels)
	}

	@Test
	fun `the reverse geocode is cached per location fix`() = runTest {
		every { settingsRepository.settings } returns flowOf(AppSettings(useDeviceLocation = true, showLocationLabel = true))
		coEvery { locationRepository.currentLocation() } returns GeoLocation(37.57, 126.98)
		coEvery { weatherRepository.current(37.57, 126.98) } returns Result.success(snapshotWith(weatherCode = 63))
		coEvery { reverseGeocodingRepository.placeName(37.57, 126.98) } returns "Seoul"

		provider.refresh(1_000_000L)
		provider.refresh(1_000_060L, force = true)

		coVerify(exactly = 1) { reverseGeocodingRepository.placeName(37.57, 126.98) }
	}

	@Test
	fun `label settings reach the scene params even when the refresh is throttled`() = runTest {
		val device = AppSettings(useDeviceLocation = true)
		every { settingsRepository.settings } returns flowOf(device)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))

		provider.refresh(1_000_000L)
		assertEquals("Rain · 10°", provider.paramsFor(1_000_030L).overlayLabels?.weather)

		// The user switches to Fahrenheit; the next refresh is inside the throttle window but must still pick the new unit up.
		every { settingsRepository.settings } returns flowOf(device.copy(temperatureUnit = TemperatureUnit.FAHRENHEIT))
		provider.refresh(1_000_060L)

		assertEquals("Rain · 50°", provider.paramsFor(1_000_090L).overlayLabels?.weather)
	}

	@Test
	fun `enabling the location label mid-interval geocodes the cached fix`() = runTest {
		val device = AppSettings(useDeviceLocation = true)
		every { settingsRepository.settings } returns flowOf(device)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(37.57, 126.98)
		coEvery { weatherRepository.current(37.57, 126.98) } returns Result.success(snapshotWith(weatherCode = 63))

		provider.refresh(1_000_000L)

		// The toggle flips inside the throttle window: no weather refetch, but the place name must still appear.
		every { settingsRepository.settings } returns flowOf(device.copy(showLocationLabel = true))
		coEvery { reverseGeocodingRepository.placeName(37.57, 126.98) } returns "Seoul"
		provider.refresh(1_000_060L)

		assertEquals("Seoul", provider.paramsFor(1_000_090L).overlayLabels?.location)
		coVerify(exactly = 1) { weatherRepository.current(37.57, 126.98) }
	}

	private fun snapshotWith(weatherCode: Int) = WeatherSnapshot(
		observation = WeatherObservation(
			weatherCode = weatherCode,
			isDay = true,
			temperatureCelsius = 10.0,
			precipitationMillimeters = 0.0,
			windSpeedKilometersPerHour = 5.0,
			cloudCoverPercent = 50
		),
		observedAtEpochSeconds = 1_000_000L,
		sunriseEpochSeconds = null,
		sunsetEpochSeconds = null
	)
}
