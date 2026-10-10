package xyz.attacktive.weatherd.domain.render

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.CloudCover
import xyz.attacktive.weatherd.domain.model.DayPhase
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
class HumidityWeatherSceneProviderTest {
	private val context = mockk<Context>(relaxed = true)
	private val locationRepository = mockk<LocationRepository>()
	private val weatherRepository = mockk<WeatherRepository>()
	private val reverseGeocodingRepository = mockk<ReverseGeocodingRepository>()
	private val persistedSettings = MutableStateFlow(AppSettings())
	private val photoState = MutableStateFlow(PhotoBackgroundState(emptySet()))
	private val settingsRepository = mockk<SettingsRepository> {
		every { defaults } returns AppSettings()
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
		applicationScope
	)

	@After
	fun cancelSettingsCollection() {
		applicationScope.cancel()
	}

	@Test
	fun `simulator pins haze while humid cached weather remains available`() {
		val liveSettings = manualSettings()
		val liveState = WeatherSceneState(
			settings = liveSettings,
			snapshot = snapshot(relativeHumidityPercent = 100.0),
			snapshotTarget = WeatherSceneTarget.from(liveSettings),
			photoRevision = 7
		)

		val live = provider.paramsFor(NOW, liveState, debugToolsAvailable = false)
		val clearSimulation = simulatedParams(liveSettings, liveState, "CLEAR")
		val hazeSimulation = simulatedParams(liveSettings, liveState, "HUMID HAZE")
		val fogSimulation = simulatedParams(liveSettings, liveState, "FOG")
		val returnedLive = provider.paramsFor(NOW, liveState, debugToolsAvailable = false)

		assertFogState(live, expectedFogDensity = 0f, expectedHumidityHazeDensity = 0.25f)
		assertEquals(7, live.photoRevision)
		assertFogState(clearSimulation, expectedFogDensity = 0f, expectedHumidityHazeDensity = 0f)
		assertEquals(7, clearSimulation.photoRevision)
		assertFogState(hazeSimulation, expectedFogDensity = 0f, expectedHumidityHazeDensity = 0.25f)
		assertFogState(fogSimulation, expectedFogDensity = 1f, expectedHumidityHazeDensity = 0f)
		assertFogState(returnedLive, expectedFogDensity = 0f, expectedHumidityHazeDensity = 0.25f)
		assertEquals(7, returnedLive.photoRevision)
	}

	@Test
	fun `fallback never uses humidity from a mismatched cached target`() {
		val seoulSettings = manualSettings(latitude = 37.57, longitude = 126.98)
		val seoulTarget = WeatherSceneTarget.from(seoulSettings)
		val humidSnapshot = snapshot(relativeHumidityPercent = 100.0)
		val busanSettings = manualSettings(latitude = 35.18, longitude = 129.08)
		val mismatchedState = WeatherSceneState(
			settings = busanSettings,
			snapshot = humidSnapshot,
			snapshotTarget = seoulTarget
		)

		val fallback = provider.paramsFor(NOW, mismatchedState, debugToolsAvailable = false)

		assertEquals(0f, fallback.fogDensity, TOLERANCE)
		assertEquals(0f, fallback.humidityHazeDensity, TOLERANCE)

		val matchingLowHumidityState = WeatherSceneState(
			settings = busanSettings,
			snapshot = snapshot(relativeHumidityPercent = 60.0),
			snapshotTarget = WeatherSceneTarget.from(busanSettings)
		)

		val matching = provider.paramsFor(NOW, matchingLowHumidityState, debugToolsAvailable = false)

		assertEquals(0f, matching.fogDensity, TOLERANCE)
		assertEquals(0f, matching.humidityHazeDensity, TOLERANCE)
	}

	private fun simulatedParams(liveSettings: AppSettings, liveState: WeatherSceneState, presetName: String) = provider.paramsFor(
		NOW,
		liveState.copy(
			settings = liveSettings.copy(
				sceneSimulatorEnabled = true,
				sceneSimulatorActive = true,
				sceneSimulatorPresetIndex = SCENE_PRESETS.indexOfFirst { it.name == presetName },
				sceneSimulatorDayPhase = DayPhase.DAY
			)
		),
		debugToolsAvailable = false
	)

	private fun assertFogState(params: SceneParams, expectedFogDensity: Float, expectedHumidityHazeDensity: Float) {
		assertEquals(expectedFogDensity, params.fogDensity, TOLERANCE)
		assertEquals(expectedHumidityHazeDensity, params.humidityHazeDensity, TOLERANCE)
	}

	private fun manualSettings(latitude: Double = 37.57, longitude: Double = 126.98) = AppSettings(
		useDeviceLocation = false,
		manualLatitude = latitude,
		manualLongitude = longitude,
		weatherProvider = WeatherProviderType.OPEN_METEO,
		weatherFallbackProvider = WeatherProviderType.OPEN_METEO
	)

	private fun snapshot(relativeHumidityPercent: Double) = WeatherSnapshot(
		observation = WeatherObservation(
			condition = conditionForWmoCode(0),
			isDay = true,
			temperatureCelsius = 20.0,
			precipitationMillimeters = 0.0,
			windSpeedKilometersPerHour = 8.0,
			cloudCover = CloudCover(totalPercent = 10),
			relativeHumidityPercent = relativeHumidityPercent
		),
		observedAtEpochSeconds = NOW,
		sunriseEpochSeconds = NOW - 10_000L,
		sunsetEpochSeconds = NOW + 10_000L,
		source = WeatherSource(WeatherProviderType.OPEN_METEO)
	)

	private companion object {
		const val NOW = 1_800_000_000L
		const val TOLERANCE = 0.0001f
	}
}
