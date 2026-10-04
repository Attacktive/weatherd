package xyz.attacktive.weatherd.domain.render

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.CloudCover
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.model.WeatherSource
import xyz.attacktive.weatherd.domain.repository.LocationRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.ReverseGeocodingRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.repository.WeatherRepository
import xyz.attacktive.weatherd.domain.weather.conditionForWmoCode
import xyz.attacktive.weatherd.util.AppLogger

class WeatherSceneProviderReleaseTest {
	private val context = mockk<Context>(relaxed = true)
	private val locationRepository = mockk<LocationRepository>()
	private val weatherRepository = mockk<WeatherRepository>()
	private val reverseGeocodingRepository = mockk<ReverseGeocodingRepository>()
	private val settingsRepository = mockk<SettingsRepository>()
	private val photoBackgroundRepository = mockk<PhotoBackgroundRepository>()
	private val logger = mockk<AppLogger>(relaxed = true)
	private val provider = WeatherSceneProvider(context, locationRepository, weatherRepository, reverseGeocodingRepository, settingsRepository, photoBackgroundRepository, logger)

	@Test
	fun `hidden active simulator returns to live weather in release`() = runTest {
		every {
			settingsRepository.settings
		} returns flowOf(
			AppSettings(
				useDeviceLocation = true,
				sceneSimulatorEnabled = false,
				sceneSimulatorActive = true,
				sceneSimulatorDayPhase = DayPhase.DAY
			)
		)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(nighttimeSnapshot())

		provider.refresh(1_000_000L)

		assertEquals(DayPhase.NIGHT, provider.paramsFor(1_000_030L).dayPhase)
		coVerify(exactly = 1) { locationRepository.currentLocation() }
		coVerify(exactly = 1) { weatherRepository.current(52.52, 13.40) }
	}

	@Test
	fun `enabled simulator still overrides live weather in release`() = runTest {
		every {
			settingsRepository.settings
		} returns flowOf(
			AppSettings(
				useDeviceLocation = true,
				sceneSimulatorEnabled = true,
				sceneSimulatorActive = true,
				sceneSimulatorDayPhase = DayPhase.DAY
			)
		)

		provider.refresh(1_000_000L)

		assertEquals(DayPhase.DAY, provider.paramsFor(1_000_030L).dayPhase)
		coVerify(exactly = 0) { locationRepository.currentLocation() }
		coVerify(exactly = 0) { weatherRepository.current(any(), any()) }
	}

	private fun nighttimeSnapshot() = WeatherSnapshot(
		observation = WeatherObservation(
			condition = conditionForWmoCode(0),
			isDay = false,
			temperatureCelsius = 10.0,
			precipitationMillimeters = 0.0,
			windSpeedKilometersPerHour = 5.0,
			cloudCover = CloudCover(0)
		),
		observedAtEpochSeconds = 1_000_000L,
		sunriseEpochSeconds = null,
		sunsetEpochSeconds = null,
		source = WeatherSource(WeatherProviderType.OPEN_METEO)
	)
}
