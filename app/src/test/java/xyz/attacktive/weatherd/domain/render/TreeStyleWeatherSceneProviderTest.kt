package xyz.attacktive.weatherd.domain.render

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.CloudCover
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.TreeStyle
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.model.WeatherSource
import xyz.attacktive.weatherd.domain.repository.LocationRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundState
import xyz.attacktive.weatherd.domain.repository.ReverseGeocodingRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.repository.WeatherRepository
import xyz.attacktive.weatherd.domain.weather.conditionForWmoCode
import xyz.attacktive.weatherd.util.AppLogger

@OptIn(ExperimentalCoroutinesApi::class)
class TreeStyleWeatherSceneProviderTest {
	private val persistedSettings = MutableStateFlow(
		AppSettings(
			useDeviceLocation = false,
			manualLatitude = 52.52,
			manualLongitude = 13.40,
			treeStyle = TreeStyle.CONIFER
		)
	)

	private val settingsRepository = mockk<SettingsRepository> {
		every { defaults } returns AppSettings()
		every { settings } returns persistedSettings
	}

	private val locationRepository = mockk<LocationRepository>()
	private val weatherRepository = mockk<WeatherRepository>()
	private val reverseGeocodingRepository = mockk<ReverseGeocodingRepository>()
	private val photoBackgroundRepository = mockk<PhotoBackgroundRepository> {
		every { state } returns MutableStateFlow(PhotoBackgroundState(emptySet()))
	}

	private val applicationScope = CoroutineScope(UnconfinedTestDispatcher())
	private val provider = WeatherSceneProvider(
		context = mockk<Context>(relaxed = true),
		locationRepository = locationRepository,
		weatherRepository = weatherRepository,
		reverseGeocodingRepository = reverseGeocodingRepository,
		settingsRepository = settingsRepository,
		photoBackgroundRepository = photoBackgroundRepository,
		logger = mockk<AppLogger>(relaxed = true),
		applicationScope = applicationScope
	)

	@After
	fun cancelScope() {
		applicationScope.cancel()
	}

	@Test
	fun `tree style reaches live simulator and fallback params`() = runTest {
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(clearSnapshot())

		provider.refresh(1_000_000L)

		assertEquals(TreeStyle.CONIFER, provider.paramsFor(1_000_030L).treeStyle)

		persistedSettings.value = persistedSettings.value.copy(
			sceneSimulatorEnabled = true,
			sceneSimulatorActive = true,
			sceneSimulatorPresetIndex = SCENE_PRESETS.indexOfFirst { it.name == "CLEAR" },
			sceneSimulatorDayPhase = DayPhase.DAY
		)

		assertEquals(TreeStyle.CONIFER, provider.paramsFor(1_000_060L, debugToolsAvailable = false).treeStyle)

		val fallbackState = WeatherSceneState(
			settings = AppSettings(treeStyle = TreeStyle.CONIFER)
		)

		assertEquals(TreeStyle.CONIFER, provider.paramsFor(1_000_090L, state = fallbackState, debugToolsAvailable = false).treeStyle)
	}

	private fun clearSnapshot() = WeatherSnapshot(
		observation = WeatherObservation(
			condition = conditionForWmoCode(0),
			isDay = true,
			temperatureCelsius = 20.0,
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
