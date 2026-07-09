package xyz.attacktive.weatherd.domain.render

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.repository.LocationRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.repository.WeatherRepository
import xyz.attacktive.weatherd.util.AppLogger

class WeatherSceneProviderTest {
	private val locationRepository = mockk<LocationRepository>()
	private val weatherRepository = mockk<WeatherRepository>()
	private val settingsRepository = mockk<SettingsRepository>()
	private val logger = mockk<AppLogger>(relaxed = true)

	private val provider = WeatherSceneProvider(locationRepository, weatherRepository, settingsRepository, logger)

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
