package xyz.attacktive.weatherd.domain.render

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.CloudCover
import xyz.attacktive.weatherd.domain.model.DayPhase
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
class MissingSolarTimesWeatherSceneProviderTest {
	private val appSettings = AppSettings(
		useDeviceLocation = false,
		manualLatitude = 51.48,
		manualLongitude = 0.0,
		updateIntervalMinutes = 15
	)
	private val persistedSettings = MutableStateFlow(appSettings)
	private val photoState = MutableStateFlow(PhotoBackgroundState(emptySet()))
	private val context = mockk<Context>(relaxed = true)
	private val locationRepository = mockk<LocationRepository>()
	private val weatherRepository = mockk<WeatherRepository>()
	private val reverseGeocodingRepository = mockk<ReverseGeocodingRepository>()
	private val settingsRepository = mockk<SettingsRepository> {
		every { defaults } returns appSettings
		every { settings } returns persistedSettings
	}
	private val photoBackgroundRepository = mockk<PhotoBackgroundRepository> {
		every { state } returns photoState
	}
	private val logger = mockk<AppLogger>(relaxed = true)
	private val applicationScope = CoroutineScope(UnconfinedTestDispatcher())
	private val provider = WeatherSceneProvider(
		context,
		locationRepository,
		weatherRepository,
		reverseGeocodingRepository,
		settingsRepository,
		photoBackgroundRepository,
		logger,
		applicationScope,
		weatherCacheFixture()
	)

	@After
	fun cancelSettingsCollection() {
		applicationScope.cancel()
	}

	@Test
	fun `stale cached daytime weather without solar times follows the selected location clock after a failed refresh`() = runTest {
		val originalTimeZone = TimeZone.getDefault()
		TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))

		try {
			val snapshot = WeatherSnapshot(
				observation = WeatherObservation(
					condition = conditionForWmoCode(3),
					isDay = true,
					temperatureCelsius = 10.0,
					precipitationMillimeters = 0.0,
					windSpeedKilometersPerHour = 5.0,
					cloudCover = CloudCover(50)
				),
				observedAtEpochSeconds = NOON_EPOCH_SECONDS,
				sunriseEpochSeconds = null,
				sunsetEpochSeconds = null,
				locationLongitude = 0.0
			)
			coEvery { weatherRepository.current(51.48, 0.0) } returns Result.success(snapshot) andThen Result.failure(IllegalStateException("Offline"))

			val initialRefresh = provider.refreshWithResult(NOON_EPOCH_SECONDS, force = true)

			assertTrue(initialRefresh.isSuccess)
			assertEquals(DayPhase.DAY, provider.paramsFor(NOON_EPOCH_SECONDS).dayPhase)
			assertEquals(NOON_EPOCH_SECONDS, provider.status.value.lastRefreshEpochSeconds)

			val failedRefresh = provider.refreshWithResult(EVENING_EPOCH_SECONDS, force = true)

			assertTrue(failedRefresh.isFailure)
			assertEquals(NOON_EPOCH_SECONDS, provider.status.value.lastRefreshEpochSeconds)

			val evening = provider.paramsFor(EVENING_EPOCH_SECONDS)

			assertEquals(DayPhase.NIGHT, evening.dayPhase)
			assertEquals(0.5f, evening.cloudiness, 0.0001f)
			coVerify(exactly = 2) { weatherRepository.current(51.48, 0.0) }
		} finally {
			TimeZone.setDefault(originalTimeZone)
		}
	}

	private companion object {
		const val NOON_EPOCH_SECONDS = 1_791_806_400L // 2026-10-12T12:00:00Z
		const val EVENING_EPOCH_SECONDS = 1_791_835_200L // 2026-10-12T20:00:00Z
	}
}
