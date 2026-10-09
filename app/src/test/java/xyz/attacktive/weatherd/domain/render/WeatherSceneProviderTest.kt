package xyz.attacktive.weatherd.domain.render

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SunColorPreset
import xyz.attacktive.weatherd.domain.model.TemperatureUnit
import xyz.attacktive.weatherd.domain.model.CloudCover
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
import xyz.attacktive.weatherd.domain.weather.moonPhaseFor
import xyz.attacktive.weatherd.util.AppLogger

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherSceneProviderTest {
	@Test
	fun `scene simulator override requires available controls`() {
		assertFalse(
			sceneSimulatorOverridesWeather(
				sceneSimulatorActive = true,
				sceneSimulatorEnabled = false,
				debugToolsEnabled = false
			)
		)

		assertTrue(
			sceneSimulatorOverridesWeather(
				sceneSimulatorActive = true,
				sceneSimulatorEnabled = true,
				debugToolsEnabled = false
			)
		)

		assertTrue(
			sceneSimulatorOverridesWeather(
				sceneSimulatorActive = true,
				sceneSimulatorEnabled = false,
				debugToolsEnabled = true
			)
		)
	}

	@Test
	fun `hidden active simulator returns to live weather when debug tools are unavailable`() = runTest {
		persistedSettings.value = AppSettings(
			useDeviceLocation = true,
			sceneSimulatorEnabled = false,
			sceneSimulatorActive = true,
			sceneSimulatorDayPhase = DayPhase.DAY
		)

		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3, isDay = false))

		provider.refreshWithResult(1_000_000L, debugToolsAvailable = false)

		assertEquals(DayPhase.NIGHT, provider.paramsFor(1_000_030L, debugToolsAvailable = false).dayPhase)
		coVerify(exactly = 1) { locationRepository.currentLocation() }
		coVerify(exactly = 1) { weatherRepository.current(52.52, 13.40) }
	}

	@Test
	fun `enabled simulator still overrides weather when debug tools are unavailable`() = runTest {
		persistedSettings.value = AppSettings(
			useDeviceLocation = true,
			sceneSimulatorEnabled = true,
			sceneSimulatorActive = true,
			sceneSimulatorDayPhase = DayPhase.DAY
		)

		provider.refreshWithResult(1_000_000L, debugToolsAvailable = false)

		assertEquals(DayPhase.DAY, provider.paramsFor(1_000_030L, debugToolsAvailable = false).dayPhase)
		coVerify(exactly = 0) { locationRepository.currentLocation() }
		coVerify(exactly = 0) { weatherRepository.current(any(), any()) }
	}

	private val context = mockk<Context>(relaxed = true) {
		every { getString(R.string.weather_rain) } returns "Rain"
	}

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
	private val provider by lazy {
		WeatherSceneProvider(context, locationRepository, weatherRepository, reverseGeocodingRepository, settingsRepository, photoBackgroundRepository, logger, applicationScope)
	}

	@After
	fun cancelSettingsCollection() {
		applicationScope.cancel()
	}

	@Test
	fun `scene simulator override replaces weather without fetching`() = runTest {
		val presetIndex = SCENE_PRESETS.indexOfFirst { it.name == "SNOW" }
		val preset = SCENE_PRESETS[presetIndex]
		persistedSettings.value = AppSettings(
			backdropScene = BackdropScene.BEACH,
			precipitationIntensityScale = 1.5f,
			windIntensityScale = 0.5f,
			cloudIntensityScale = 0.8f,
			cloudSizeScale = 1.6f,
			cloudCountScale = 0.7f,
			cloudContrastScale = 1.4f,
			skyBrightnessScale = 0.8f,
			nightBrightnessScale = 0.35f,
			skySaturationScale = 1.2f,
			skyColorPreset = SkyColorPreset.PASTEL,
			sunVisible = false,
			moonVisible = false,
			sunSizeScale = 1.6f,
			sunColorPreset = SunColorPreset.GOLDEN,
			lensFlareEnabled = false,
			sceneSimulatorActive = true,
			sceneSimulatorPresetIndex = presetIndex,
			sceneSimulatorDayPhase = DayPhase.DUSK,
			sceneSimulatorCelestialProgress = 0.73f
		)

		provider.refresh(1_000_000L)

		val params = provider.paramsFor(1_000_030L)
		assertEquals(DayPhase.DUSK, params.dayPhase)
		assertEquals(preset.cloudiness, params.cloudiness, 0.0001f)
		assertEquals(preset.precipitation, params.precipitation)
		assertEquals(0.73f, params.celestialProgress, 0.0001f)
		assertEquals(BackdropScene.BEACH, params.backdropScene)
		assertEquals(1.5f, params.precipitationScale, 0.0001f)
		assertEquals(0.5f, params.windScale, 0.0001f)
		assertEquals(0.8f, params.cloudScale, 0.0001f)
		assertEquals(1.6f, params.cloudSizeScale, 0.0001f)
		assertEquals(0.7f, params.cloudCountScale, 0.0001f)
		assertEquals(1.4f, params.cloudContrastScale, 0.0001f)
		assertEquals(0.8f, params.skyBrightnessScale, 0.0001f)
		assertEquals(0.35f, params.nightBrightnessScale, 0.0001f)
		assertEquals(1.2f, params.skySaturationScale, 0.0001f)
		assertEquals(SkyColorPreset.PASTEL, params.skyColorPreset)
		assertFalse(params.sunVisible)
		assertFalse(params.moonVisible)
		assertEquals(1.6f, params.sunSizeScale, 0.0001f)
		assertEquals(SunColorPreset.GOLDEN, params.sunColorPreset)
		assertFalse(params.lensFlareEnabled)
		assertEquals(moonPhaseFor(1_000_030L), params.moonPhase, 0.0001f)
		coVerify(exactly = 0) { locationRepository.currentLocation() }
		coVerify(exactly = 0) { weatherRepository.current(any(), any()) }
	}

	@Test
	fun `scene simulator adopts the real calendar lunar phase at the evaluated moment`() = runTest {
		persistedSettings.value = AppSettings(
			useDeviceLocation = true,
			sceneSimulatorActive = true,
			sceneSimulatorPresetIndex = 0,
			sceneSimulatorDayPhase = DayPhase.NIGHT
		)

		provider.refresh(1_000_000L)

		val epoch = 1_791_225_840L
		val params = provider.paramsFor(epoch)

		assertEquals(moonPhaseFor(epoch), params.moonPhase, 0.0001f)
		assertNotEquals(0.5f, params.moonPhase)
	}

	@Test
	fun `disabling scene simulator returns to cached live weather`() = runTest {
		val live = AppSettings(useDeviceLocation = true)
		persistedSettings.value = live
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_000L)
		val liveParams = provider.paramsFor(1_000_030L)

		persistedSettings.value = live.copy(sceneSimulatorActive = true, sceneSimulatorPresetIndex = 0, sceneSimulatorDayPhase = DayPhase.NIGHT)
		provider.refresh(1_000_060L)
		assertEquals(DayPhase.NIGHT, provider.paramsFor(1_000_060L).dayPhase)

		persistedSettings.value = live
		provider.refresh(1_000_090L)

		assertEquals(liveParams.cloudiness, provider.paramsFor(1_000_090L).cloudiness, 0.0001f)
		coVerify(exactly = 1) { weatherRepository.current(52.52, 13.40) }
	}

	@Test
	fun `simulator selection changes while a live weather request remains pending`() = runTest {
		val settings = MutableStateFlow(AppSettings(useDeviceLocation = true, backdropScene = BackdropScene.BEACH))
		every { settingsRepository.settings } returns settings
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(any(), any()) } returns Result.success(snapshotWith(weatherCode = 63))
		provider.refresh(1_000_000L)

		val response = CompletableDeferred<Result<WeatherSnapshot>>()
		coEvery { weatherRepository.current(any(), any()) } coAnswers { response.await() }

		val refresh = async { provider.refresh(1_000_060L, force = true) }

		runCurrent()

		try {
			settings.value = settings.value.copy(sceneSimulatorActive = true, sceneSimulatorDayPhase = DayPhase.DUSK)
			runCurrent()
			assertEquals(DayPhase.DUSK, provider.paramsFor(1_000_060L).dayPhase)

			settings.value = settings.value.copy(sceneSimulatorActive = false)
			runCurrent()
			val live = provider.paramsFor(1_000_060L)
			assertEquals(DayPhase.DAY, live.dayPhase)
			assertEquals(BackdropScene.BEACH, live.backdropScene)
			assertEquals(PrecipitationKind.RAIN, live.precipitation?.kind)
			assertFalse(refresh.isCompleted)

			settings.value = settings.value.copy(sceneSimulatorActive = true, sceneSimulatorDayPhase = DayPhase.NIGHT)
			runCurrent()
			response.complete(Result.success(snapshotWith(weatherCode = 3)))
			refresh.await()
			assertEquals(DayPhase.NIGHT, provider.paramsFor(1_000_090L).dayPhase)

			settings.value = settings.value.copy(sceneSimulatorActive = false)
			runCurrent()
			assertNull(provider.paramsFor(1_000_090L).precipitation)
		} finally {
			refresh.cancel()
		}
	}

	@Test
	fun `first launch can leave simulation while location remains pending`() = runTest {
		val settings = MutableStateFlow(AppSettings(useDeviceLocation = true))
		every { settingsRepository.settings } returns settings
		val location = CompletableDeferred<GeoLocation?>()
		coEvery { locationRepository.currentLocation() } coAnswers { location.await() }

		val refresh = async { provider.refresh(1_000_000L) }

		runCurrent()

		try {
			settings.value = settings.value.copy(sceneSimulatorActive = true, sceneSimulatorPresetIndex = SCENE_PRESETS.indexOfFirst { it.name == "SNOW" })
			runCurrent()
			assertEquals(PrecipitationKind.SNOW, provider.paramsFor(1_000_030L).precipitation?.kind)

			settings.value = settings.value.copy(sceneSimulatorActive = false, windIntensityScale = 1.5f)
			runCurrent()
			val fallback = provider.paramsFor(1_000_030L)
			assertNull(fallback.precipitation)
			assertEquals(1.5f, fallback.windScale, 0.0001f)
			assertFalse(refresh.isCompleted)

			coEvery { weatherRepository.current(any(), any()) } returns Result.success(snapshotWith(weatherCode = 63))
			location.complete(GeoLocation(52.52, 13.40))
			refresh.await()
			assertEquals(PrecipitationKind.RAIN, provider.paramsFor(1_000_030L).precipitation?.kind)
		} finally {
			refresh.cancel()
		}
	}

	@Test
	fun `changing location settings refetches within the throttle window`() = runTest {
		val munich = AppSettings(useDeviceLocation = false, manualLatitude = 48.14, manualLongitude = 11.58)
		persistedSettings.value = munich
		coEvery { weatherRepository.current(48.14, 11.58) } returns Result.success(snapshotWith(weatherCode = 61))

		provider.refresh(1_000_000L)

		val device = AppSettings(useDeviceLocation = true)
		persistedSettings.value = device
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_060L)

		coVerify(exactly = 1) { weatherRepository.current(52.52, 13.40) }
	}

	@Test
	fun `changing weather provider refetches within the throttle window`() = runTest {
		val openMeteo = AppSettings(useDeviceLocation = true, weatherProvider = WeatherProviderType.OPEN_METEO)
		persistedSettings.value = openMeteo
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_000L)

		persistedSettings.value = openMeteo.copy(weatherProvider = WeatherProviderType.MET_NORWAY)
		provider.refresh(1_000_060L)

		coVerify(exactly = 2) { weatherRepository.current(52.52, 13.40) }
	}

	@Test
	fun `an obsolete location response cannot replace a newer request`() = runTest {
		val settings = MutableStateFlow(
			AppSettings(
				useDeviceLocation = false,
				manualLatitude = 44.5,
				manualLongitude = 11.34,
				weatherProvider = WeatherProviderType.ITALIA_METEO
			)
		)
		every { settingsRepository.settings } returns settings
		val firstStarted = CompletableDeferred<Unit>()
		val releaseFirst = CompletableDeferred<Unit>()
		coEvery { weatherRepository.current(44.5, 11.34) } coAnswers {
			firstStarted.complete(Unit)
			releaseFirst.await()
			Result.success(snapshotWith(weatherCode = 3, source = WeatherSource(WeatherProviderType.ITALIA_METEO)))
		}

		coEvery { weatherRepository.current(55.75, 37.61) } returns Result.success(snapshotWith(weatherCode = 61, source = WeatherSource(WeatherProviderType.OPEN_METEO)))

		val obsoleteRefresh = async { provider.refresh(1_000_000L, force = true) }
		firstStarted.await()

		settings.value = settings.value.copy(manualLatitude = 55.75, manualLongitude = 37.61)
		provider.refresh(1_000_060L, force = true)
		releaseFirst.complete(Unit)
		obsoleteRefresh.await()

		assertEquals(1_000_060L, provider.status.value.lastRefreshEpochSeconds)
		assertEquals(WeatherProviderType.OPEN_METEO, provider.status.value.weatherSource?.provider)
	}

	@Test
	fun `obsolete device settings cannot invalidate a newer manual-location request`() = runTest {
		val settings = MutableStateFlow(AppSettings(useDeviceLocation = true))
		every { settingsRepository.settings } returns settings
		val deviceLocationStarted = CompletableDeferred<Unit>()
		val releaseDeviceLocation = CompletableDeferred<Unit>()
		coEvery { locationRepository.currentLocation() } coAnswers {
			deviceLocationStarted.complete(Unit)
			releaseDeviceLocation.await()
			GeoLocation(37.57, 126.98)
		}

		val manualRequestStarted = CompletableDeferred<Unit>()
		val releaseManualRequest = CompletableDeferred<Unit>()
		coEvery { weatherRepository.current(44.5, 11.34) } coAnswers {
			manualRequestStarted.complete(Unit)
			releaseManualRequest.await()
			Result.success(snapshotWith(weatherCode = 3, source = WeatherSource(WeatherProviderType.ITALIA_METEO)))
		}

		val obsoleteRefresh = async { provider.refreshWithResult(1_000_000L, force = true) }
		deviceLocationStarted.await()
		settings.value = settings.value.copy(
			useDeviceLocation = false,
			manualLatitude = 44.5,
			manualLongitude = 11.34,
			weatherProvider = WeatherProviderType.ITALIA_METEO
		)
		val currentRefresh = async { provider.refreshWithResult(1_000_060L, force = true) }
		manualRequestStarted.await()
		releaseDeviceLocation.complete(Unit)
		val obsoleteResult = obsoleteRefresh.await()
		releaseManualRequest.complete(Unit)
		val currentResult = currentRefresh.await()

		assertTrue(obsoleteResult.isSuccess)
		assertTrue(currentResult.isSuccess)
		assertEquals(1_000_060L, provider.status.value.lastRefreshEpochSeconds)
		assertEquals(WeatherProviderType.ITALIA_METEO, provider.status.value.weatherSource?.provider)
		coVerify(exactly = 0) { weatherRepository.current(37.57, 126.98) }
		coVerify(exactly = 1) { weatherRepository.current(44.5, 11.34) }
	}

	@Test
	fun `settings changed during reverse geocoding cannot invalidate a newer request`() = runTest {
		val settings = MutableStateFlow(AppSettings(useDeviceLocation = true))
		every { settingsRepository.settings } returns settings
		coEvery { locationRepository.currentLocation() } returns GeoLocation(37.57, 126.98)
		val reverseGeocodingStarted = CompletableDeferred<Unit>()
		val releaseReverseGeocoding = CompletableDeferred<Unit>()
		coEvery { reverseGeocodingRepository.placeName(37.57, 126.98) } coAnswers {
			reverseGeocodingStarted.complete(Unit)
			releaseReverseGeocoding.await()
			"Seoul"
		}

		val manualRequestStarted = CompletableDeferred<Unit>()
		val releaseManualRequest = CompletableDeferred<Unit>()
		coEvery { weatherRepository.current(44.5, 11.34) } coAnswers {
			manualRequestStarted.complete(Unit)
			releaseManualRequest.await()
			Result.success(snapshotWith(weatherCode = 3, source = WeatherSource(WeatherProviderType.ITALIA_METEO)))
		}

		val obsoleteRefresh = async { provider.refreshWithResult(1_000_000L, force = true, resolveLocationName = true) }
		reverseGeocodingStarted.await()
		settings.value = settings.value.copy(
			useDeviceLocation = false,
			manualLatitude = 44.5,
			manualLongitude = 11.34,
			manualLocationLabel = "Bologna",
			weatherProvider = WeatherProviderType.ITALIA_METEO
		)
		val currentRefresh = async { provider.refreshWithResult(1_000_060L, force = true) }
		manualRequestStarted.await()
		releaseReverseGeocoding.complete(Unit)
		val obsoleteResult = obsoleteRefresh.await()
		releaseManualRequest.complete(Unit)
		val currentResult = currentRefresh.await()

		assertTrue(obsoleteResult.isSuccess)
		assertTrue(currentResult.isSuccess)
		assertEquals(1_000_060L, provider.status.value.lastRefreshEpochSeconds)
		assertEquals(WeatherProviderType.ITALIA_METEO, provider.status.value.weatherSource?.provider)
		coVerify(exactly = 0) { weatherRepository.current(37.57, 126.98) }
		coVerify(exactly = 1) { weatherRepository.current(44.5, 11.34) }
	}

	@Test
	fun `a newer failed same-target request does not discard an older success`() = runTest {
		val settings = AppSettings(useDeviceLocation = true)
		persistedSettings.value = settings
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		val firstStarted = CompletableDeferred<Unit>()
		val releaseFirst = CompletableDeferred<Unit>()
		var requestCount = 0
		coEvery { weatherRepository.current(52.52, 13.40) } coAnswers {
			requestCount += 1
			if (requestCount == 1) {
				firstStarted.complete(Unit)
				releaseFirst.await()
				Result.success(snapshotWith(weatherCode = 3, source = WeatherSource(WeatherProviderType.OPEN_METEO)))
			} else {
				Result.failure(IllegalStateException("provider unavailable"))
			}
		}

		val firstRefresh = async { provider.refreshWithResult(1_000_000L, force = true) }
		firstStarted.await()
		val newerFailure = provider.refreshWithResult(1_000_060L, force = true)
		releaseFirst.complete(Unit)
		val olderSuccess = firstRefresh.await()

		assertTrue(newerFailure.isFailure)
		assertTrue(olderSuccess.isSuccess)
		assertEquals(1_000_000L, provider.status.value.lastRefreshEpochSeconds)
		assertEquals(WeatherProviderType.OPEN_METEO, provider.status.value.weatherSource?.provider)
		coVerify(exactly = 2) { weatherRepository.current(52.52, 13.40) }
	}

	@Test
	fun `a failed provider switch keeps the cached snapshot attribution`() = runTest {
		val settings = MutableStateFlow(AppSettings(useDeviceLocation = true, weatherProvider = WeatherProviderType.OPEN_METEO))
		every { settingsRepository.settings } returns settings
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(
			snapshotWith(
				weatherCode = 3,
				source = WeatherSource(WeatherProviderType.OPEN_METEO)
			)
		) andThen Result.failure(IllegalStateException("provider unavailable"))

		provider.refresh(1_000_000L)
		settings.value = settings.value.copy(weatherProvider = WeatherProviderType.MET_NORWAY)
		provider.refresh(1_000_060L)

		assertEquals(WeatherProviderType.OPEN_METEO, provider.status.value.weatherSource?.provider)
	}

	@Test
	fun `a failed provider switch only bypasses the throttle once`() = runTest {
		val openMeteo = AppSettings(useDeviceLocation = true, weatherProvider = WeatherProviderType.OPEN_METEO)
		persistedSettings.value = openMeteo
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_000L)

		persistedSettings.value = openMeteo.copy(weatherProvider = WeatherProviderType.MET_NORWAY)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.failure(IllegalStateException("provider unavailable"))

		provider.refresh(1_000_060L)
		provider.refresh(1_000_120L)

		coVerify(exactly = 2) { weatherRepository.current(52.52, 13.40) }
	}

	@Test
	fun `an unchanged location is throttled within the interval`() = runTest {
		val device = AppSettings(useDeviceLocation = true, updateIntervalMinutes = 30)
		persistedSettings.value = device
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_000L)
		provider.refresh(1_000_060L)

		coVerify(exactly = 1) { weatherRepository.current(52.52, 13.40) }
	}

	@Test
	fun `celestial appearance settings reach scene params even when refresh is throttled`() = runTest {
		val device = AppSettings(useDeviceLocation = true)
		persistedSettings.value = device
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_000L)

		persistedSettings.value = device.copy(sunVisible = false, moonVisible = false, sunSizeScale = 1.8f, sunColorPreset = SunColorPreset.ORANGE)
		provider.refresh(1_000_060L)

		val params = provider.paramsFor(1_000_090L)
		assertFalse(params.sunVisible)
		assertFalse(params.moonVisible)
		assertEquals(1.8f, params.sunSizeScale, 0.0001f)
		assertEquals(SunColorPreset.ORANGE, params.sunColorPreset)
		coVerify(exactly = 1) { weatherRepository.current(52.52, 13.40) }
	}

	@Test
	fun `lens flare setting reaches scene params even when refresh is throttled`() = runTest {
		val device = AppSettings(useDeviceLocation = true, lensFlareEnabled = true)
		persistedSettings.value = device
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_000L)
		assertTrue(provider.paramsFor(1_000_030L).lensFlareEnabled)

		persistedSettings.value = device.copy(lensFlareEnabled = false)
		provider.refresh(1_000_060L)

		assertFalse(provider.paramsFor(1_000_090L).lensFlareEnabled)
	}

	@Test
	fun `the backdrop choice reaches the scene params even when the refresh is throttled`() = runTest {
		val device = AppSettings(useDeviceLocation = true, backdropScene = BackdropScene.MOUNTAINS)
		persistedSettings.value = device
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_000L)
		assertEquals(BackdropScene.MOUNTAINS, provider.paramsFor(1_000_030L).backdropScene)

		// The user switches scenes; the next refresh is inside the throttle window but must still pick the new choice up.
		persistedSettings.value = device.copy(backdropScene = BackdropScene.BEACH)
		provider.refresh(1_000_060L)

		assertEquals(BackdropScene.BEACH, provider.paramsFor(1_000_090L).backdropScene)
	}

	@Test
	fun `a photo import reaches the scene params even when the refresh is throttled`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, backdropScene = BackdropScene.PHOTO)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_000L)
		val initialParams = provider.paramsFor(1_000_030L)

		// The user replaces the photo behind the same bucket: nothing the settings know about changes, so the revision is the only thing that can tell the backdrop cache to redraw.
		photoState.value = photoState.value.copy(revision = 1)
		provider.refresh(1_000_060L)

		val throttledParams = provider.paramsFor(1_000_090L)
		assertEquals(0, initialParams.photoRevision)
		assertEquals(1, throttledParams.photoRevision)
		assertNotEquals(initialParams, throttledParams)
	}

	@Test
	fun `the photo revision reaches the fallback scene before any weather loads`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, backdropScene = BackdropScene.PHOTO)
		coEvery { locationRepository.currentLocation() } returns null
		photoState.value = photoState.value.copy(revision = 7)

		provider.refresh(1_000_000L)

		// No fix, so no snapshot; the clock-lit fallback still has to carry the revision or the very first photo import would never draw.
		assertEquals(7, provider.paramsFor(1_000_030L).photoRevision)
	}

	@Test
	fun `the photo revision stays out of the params while another scene draws`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, backdropScene = BackdropScene.MOUNTAINS)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))
		photoState.value = photoState.value.copy(revision = 9)

		provider.refresh(1_000_000L)

		// Filling a bucket while the mountains draw must leave the backdrop signature alone, or the wallpaper would throw its cache away to rasterize the same mountains again and crossfade between two identical images.
		assertEquals(0, provider.paramsFor(1_000_030L).photoRevision)

		// Switching into PHOTO is what adopts it, and backdropScene has moved by then, so that frame re-rasterizes either way.
		persistedSettings.value = AppSettings(useDeviceLocation = true, backdropScene = BackdropScene.PHOTO)
		provider.refresh(1_000_060L)

		assertEquals(9, provider.paramsFor(1_000_090L).photoRevision)
	}

	@Test
	fun `the weather label with temperature reaches the scene params`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, showWeatherLabel = true)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))

		provider.refresh(1_000_000L)

		assertEquals(OverlayLabels(weather = "Rain · 10°", location = null), provider.paramsFor(1_000_030L).overlayLabels)
	}

	@Test
	fun `the weather label falls back to the bare temperature for an unknown code`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, showWeatherLabel = true)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 1234))

		provider.refresh(1_000_000L)

		assertEquals("10°", provider.paramsFor(1_000_030L).overlayLabels?.weather)
	}

	@Test
	fun `no labels are drawn when both toggles are off`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, showWeatherLabel = false, showLocationLabel = false)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))

		provider.refresh(1_000_000L)

		assertNull(provider.paramsFor(1_000_030L).overlayLabels)
	}

	@Test
	fun `a provider failure is returned to background refresh callers`() = runTest {
		val failure = IllegalStateException("provider unavailable")
		persistedSettings.value = AppSettings(useDeviceLocation = false, manualLatitude = 34.06, manualLongitude = -117.65)
		coEvery { weatherRepository.current(34.06, -117.65) } returns Result.failure(failure)

		val result = provider.refreshWithResult(1_000_000L, force = true)

		assertTrue(result.isFailure)
		assertEquals(failure, result.exceptionOrNull())
	}

	@Test
	fun `the manual location label survives fallback when weather refresh fails`() = runTest {
		val ontario = AppSettings(useDeviceLocation = false, manualLatitude = 34.06, manualLongitude = -117.65, manualLocationLabel = "Ontario, California, United States", showLocationLabel = true)
		persistedSettings.value = ontario
		coEvery { weatherRepository.current(34.06, -117.65) } returns Result.failure(IllegalStateException("provider unavailable"))

		provider.refresh(1_000_000L, force = true)

		assertEquals(
			OverlayLabels(weather = null, location = "Ontario, California, United States"),
			provider.paramsFor(1_000_030L).overlayLabels
		)
	}

	@Test
	fun `the manual city label is used without reverse geocoding`() = runTest {
		val munich = AppSettings(useDeviceLocation = false, manualLatitude = 48.14, manualLongitude = 11.58, manualLocationLabel = "Munich, Germany", showWeatherLabel = true, showLocationLabel = true)
		persistedSettings.value = munich
		coEvery { weatherRepository.current(48.14, 11.58) } returns Result.success(snapshotWith(weatherCode = 63))

		provider.refresh(1_000_000L)

		assertEquals(OverlayLabels(weather = "Rain · 10°", location = "Munich, Germany"), provider.paramsFor(1_000_030L).overlayLabels)
		coVerify(exactly = 0) { reverseGeocodingRepository.placeName(any(), any()) }
	}

	@Test
	fun `the device place name is reverse geocoded when the location label is on`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, showLocationLabel = true)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(37.57, 126.98)
		coEvery { weatherRepository.current(37.57, 126.98) } returns Result.success(snapshotWith(weatherCode = 63))
		coEvery { reverseGeocodingRepository.placeName(37.57, 126.98) } returns "Seoul"

		provider.refresh(1_000_000L)

		assertEquals("Seoul", provider.paramsFor(1_000_030L).overlayLabels?.location)
	}

	@Test
	fun `a failed reverse geocode hides only the location line`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, showWeatherLabel = true, showLocationLabel = true)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(37.57, 126.98)
		coEvery { weatherRepository.current(37.57, 126.98) } returns Result.success(snapshotWith(weatherCode = 63))
		coEvery { reverseGeocodingRepository.placeName(37.57, 126.98) } returns null

		provider.refresh(1_000_000L)

		assertEquals(OverlayLabels(weather = "Rain · 10°", location = null), provider.paramsFor(1_000_030L).overlayLabels)
	}

	@Test
	fun `the reverse geocode is cached per location fix`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, showLocationLabel = true)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(37.57, 126.98)
		coEvery { weatherRepository.current(37.57, 126.98) } returns Result.success(snapshotWith(weatherCode = 63))
		coEvery { reverseGeocodingRepository.placeName(37.57, 126.98) } returns "Seoul"

		provider.refresh(1_000_000L)
		provider.refresh(1_000_060L, force = true)

		coVerify(exactly = 1) { reverseGeocodingRepository.placeName(37.57, 126.98) }
	}

	@Test
	fun `settings status resolves a device place name even when the wallpaper label is off`() = runTest {
		assertSettingsLocationStatus(AppSettings(useDeviceLocation = true, showLocationLabel = false))
	}

	@Test
	fun `manual mode without a selected city reports the fallback device location`() = runTest {
		assertSettingsLocationStatus(AppSettings(useDeviceLocation = false, showLocationLabel = false))
	}

	@Test
	fun `clearing a manual city clears status when no device fix is available`() = runTest {
		val manual = AppSettings(
			useDeviceLocation = false,
			manualLatitude = 37.57,
			manualLongitude = 126.98,
			manualLocationLabel = "Seoul"
		)
		persistedSettings.value = manual
		coEvery { weatherRepository.current(37.57, 126.98) } returns Result.success(snapshotWith(weatherCode = 63))

		provider.refresh(1_000_000L, resolveLocationName = true)

		persistedSettings.value = manual.copy(
			manualLatitude = null,
			manualLongitude = null,
			manualLocationLabel = null
		)

		coEvery { locationRepository.currentLocation() } returns null

		provider.refresh(1_000_060L, resolveLocationName = true)

		assertEquals(WeatherSceneStatus(), provider.status.value)
	}

	@Test
	fun `a forced refresh bypasses the weather interval without forcing a device fix`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, updateIntervalMinutes = 30)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(37.57, 126.98)
		coEvery { weatherRepository.current(37.57, 126.98) } returns Result.success(snapshotWith(weatherCode = 63)) andThen Result.success(snapshotWith(weatherCode = 3))
		coEvery { reverseGeocodingRepository.placeName(37.57, 126.98) } returns "Seoul"

		provider.refresh(1_000_000L, resolveLocationName = true)
		provider.refresh(1_000_060L, force = true, resolveLocationName = true)

		coVerify(exactly = 2) { locationRepository.currentLocation() }
		coVerify(exactly = 0) { locationRepository.currentLocation(force = true) }
		coVerify(exactly = 2) { weatherRepository.current(37.57, 126.98) }
		assertEquals(
			WeatherSceneStatus(locationLabel = "Seoul", lastRefreshEpochSeconds = 1_000_060L, weatherSource = WeatherSource(WeatherProviderType.OPEN_METEO)),
			provider.status.value
		)
	}

	@Test
	fun `label settings reach the scene params even when the refresh is throttled`() = runTest {
		val device = AppSettings(useDeviceLocation = true, showWeatherLabel = true)
		persistedSettings.value = device
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))

		provider.refresh(1_000_000L)
		assertEquals("Rain · 10°", provider.paramsFor(1_000_030L).overlayLabels?.weather)

		// The user switches to Fahrenheit; the next refresh is inside the throttle window but must still pick the new unit up.
		persistedSettings.value = device.copy(temperatureUnit = TemperatureUnit.FAHRENHEIT)
		provider.refresh(1_000_060L)

		assertEquals("Rain · 50°", provider.paramsFor(1_000_090L).overlayLabels?.weather)
	}

	@Test
	fun `enabling the location label mid-interval geocodes the cached fix`() = runTest {
		val device = AppSettings(useDeviceLocation = true)
		persistedSettings.value = device
		coEvery { locationRepository.currentLocation() } returns GeoLocation(37.57, 126.98)
		coEvery { weatherRepository.current(37.57, 126.98) } returns Result.success(snapshotWith(weatherCode = 63))

		provider.refresh(1_000_000L)

		// The toggle flips inside the throttle window: no weather refetch, but the place name must still appear.
		persistedSettings.value = device.copy(showLocationLabel = true)
		coEvery { reverseGeocodingRepository.placeName(37.57, 126.98) } returns "Seoul"
		provider.refresh(1_000_060L)

		assertEquals("Seoul", provider.paramsFor(1_000_090L).overlayLabels?.location)
		coVerify(exactly = 1) { weatherRepository.current(37.57, 126.98) }
	}

	@Test
	fun `cloud composition settings reach scene params even when the refresh is throttled`() = runTest {
		val device = AppSettings(useDeviceLocation = true, cloudSizeScale = 1.5f, cloudCountScale = 0.75f)
		persistedSettings.value = device
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_000L)

		val initialParams = provider.paramsFor(1_000_030L)
		assertEquals(1.5f, initialParams.cloudSizeScale, 0.0001f)
		assertEquals(0.75f, initialParams.cloudCountScale, 0.0001f)

		persistedSettings.value = device.copy(cloudSizeScale = 0.6f, cloudCountScale = 1.8f)
		provider.refresh(1_000_060L)

		val throttledParams = provider.paramsFor(1_000_090L)
		assertEquals(0.6f, throttledParams.cloudSizeScale, 0.0001f)
		assertEquals(1.8f, throttledParams.cloudCountScale, 0.0001f)
		coVerify(exactly = 1) { weatherRepository.current(52.52, 13.40) }
	}

	@Test
	fun `intensity scale settings reach the scene params even when the refresh is throttled`() = runTest {
		val device = AppSettings(useDeviceLocation = true, precipitationIntensityScale = 1.5f, windIntensityScale = 0.5f, cloudIntensityScale = 0.8f, cloudContrastScale = 1.3f, skyBrightnessScale = 0.75f, nightBrightnessScale = 0.65f, skySaturationScale = 1.25f, skyColorPreset = SkyColorPreset.WARM)
		persistedSettings.value = device
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))

		provider.refresh(1_000_000L)

		// A wind of 5.0 km/h against the 40.0 km/h ceiling shapes to 0.2872; the 0.5x user preference rides on windScale.
		val initialParams = provider.paramsFor(1_000_030L)
		assertEquals(1.5f, initialParams.precipitationScale, 0.0001f)
		assertEquals(0.5f, initialParams.windScale, 0.0001f)
		assertEquals(0.8f, initialParams.cloudScale, 0.0001f)
		assertEquals(1.3f, initialParams.cloudContrastScale, 0.0001f)
		assertEquals(0.75f, initialParams.skyBrightnessScale, 0.0001f)
		assertEquals(0.65f, initialParams.nightBrightnessScale, 0.0001f)
		assertEquals(1.25f, initialParams.skySaturationScale, 0.0001f)
		assertEquals(SkyColorPreset.WARM, initialParams.skyColorPreset)
		assertEquals(0.2872f, initialParams.windFactor, 0.0001f)

		// The user adjusts the sliders; the next refresh is inside the throttle window but must still pick the new scales up.
		persistedSettings.value = device.copy(precipitationIntensityScale = 0.25f, windIntensityScale = 2f, cloudIntensityScale = 1.5f, cloudContrastScale = 0.7f, skyBrightnessScale = 1.3f, nightBrightnessScale = 0.2f, skySaturationScale = 0.6f, skyColorPreset = SkyColorPreset.CYBERPUNK)
		provider.refresh(1_000_060L)

		// The new wind scale reaches scene params while windFactor stays an honest observation reading.
		val throttledParams = provider.paramsFor(1_000_090L)
		assertEquals(0.25f, throttledParams.precipitationScale, 0.0001f)
		assertEquals(2f, throttledParams.windScale, 0.0001f)
		assertEquals(1.5f, throttledParams.cloudScale, 0.0001f)
		assertEquals(0.7f, throttledParams.cloudContrastScale, 0.0001f)
		assertEquals(1.3f, throttledParams.skyBrightnessScale, 0.0001f)
		assertEquals(0.2f, throttledParams.nightBrightnessScale, 0.0001f)
		assertEquals(0.6f, throttledParams.skySaturationScale, 0.0001f)
		assertEquals(SkyColorPreset.CYBERPUNK, throttledParams.skyColorPreset)
		assertEquals(0.2872f, throttledParams.windFactor, 0.0001f)
	}

	@Test
	fun `preview and wallpaper share a pending ordinary refresh`() = runTest {
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		val response = CompletableDeferred<Result<WeatherSnapshot>>()
		coEvery { weatherRepository.current(any(), any()) } coAnswers { response.await() }

		val preview = async { provider.refresh(1_000_000L) }

		val wallpaper = async { provider.refresh(1_000_000L) }

		runCurrent()

		try {
			coVerify(exactly = 1) { weatherRepository.current(52.52, 13.40) }

			response.complete(Result.failure(IllegalStateException("Offline")))
			preview.await()
			wallpaper.await()
			assertNull(provider.paramsFor(1_000_030L).precipitation)

			coEvery { weatherRepository.current(any(), any()) } returns Result.success(snapshotWith(weatherCode = 63))
			provider.refresh(1_000_060L)
			assertEquals(PrecipitationKind.RAIN, provider.paramsFor(1_000_060L).precipitation?.kind)
		} finally {
			response.complete(Result.failure(IllegalStateException("Canceled")))
			preview.cancel()
			wallpaper.cancel()
		}
	}

	@Test
	fun `returning to an earlier city does not join its obsolete refresh`() = runTest {
		val berlin = AppSettings(useDeviceLocation = false, manualLatitude = 52.52, manualLongitude = 13.40)
		persistedSettings.value = berlin
		val firstBerlinResponse = CompletableDeferred<Result<WeatherSnapshot>>()
		val munichResponse = CompletableDeferred<Result<WeatherSnapshot>>()
		var berlinCalls = 0
		coEvery { weatherRepository.current(52.52, 13.40) } coAnswers {
			if (berlinCalls++ == 0) {
				firstBerlinResponse.await()
			} else {
				Result.success(snapshotWith(weatherCode = 63))
			}
		}

		coEvery { weatherRepository.current(48.14, 11.58) } coAnswers { munichResponse.await() }

		val firstBerlin = async { provider.refresh(1_000_000L) }

		runCurrent()
		persistedSettings.value = berlin.copy(manualLatitude = 48.14, manualLongitude = 11.58)
		val munich = async { provider.refresh(1_000_010L) }

		runCurrent()
		persistedSettings.value = berlin
		val latestBerlin = async { provider.refresh(1_000_020L) }

		runCurrent()

		try {
			firstBerlinResponse.complete(Result.success(snapshotWith(weatherCode = 3)))
			munichResponse.complete(Result.success(snapshotWith(weatherCode = 71)))
			firstBerlin.await()
			munich.await()
			latestBerlin.await()
			assertEquals(PrecipitationKind.RAIN, provider.paramsFor(1_000_030L).precipitation?.kind)
		} finally {
			firstBerlinResponse.complete(Result.failure(IllegalStateException("Canceled")))
			munichResponse.complete(Result.failure(IllegalStateException("Canceled")))
			firstBerlin.cancel()
			munich.cancel()
			latestBerlin.cancel()
		}
	}

	@Test
	fun `leaving simulation after a city change uses fallback until matching weather arrives`() = runTest {
		val berlin = AppSettings(useDeviceLocation = false, manualLatitude = 52.52, manualLongitude = 13.40, manualLocationLabel = "Berlin", showWeatherLabel = true, showLocationLabel = true)
		persistedSettings.value = berlin
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))
		provider.refresh(1_000_000L)
		assertEquals(PrecipitationKind.RAIN, provider.paramsFor(1_000_030L).precipitation?.kind)

		persistedSettings.value = berlin.copy(sceneSimulatorActive = true)
		provider.refresh(1_000_060L)
		val munich = berlin.copy(manualLatitude = 48.14, manualLongitude = 11.58, manualLocationLabel = "Munich")
		persistedSettings.value = munich.copy(sceneSimulatorActive = true)
		provider.refresh(1_000_090L)
		persistedSettings.value = munich

		val fallback = provider.paramsFor(1_000_120L)
		assertNull(fallback.precipitation)
		assertEquals(OverlayLabels(weather = null, location = "Munich"), fallback.overlayLabels)

		val response = CompletableDeferred<Result<WeatherSnapshot>>()
		coEvery { weatherRepository.current(48.14, 11.58) } coAnswers { response.await() }

		val refresh = async { provider.refresh(1_000_120L) }

		runCurrent()
		try {
			assertNull(provider.paramsFor(1_000_120L).precipitation)
			assertEquals(OverlayLabels(weather = null, location = "Munich"), provider.paramsFor(1_000_120L).overlayLabels)
			response.complete(Result.success(snapshotWith(weatherCode = 71)))
			refresh.await()
			assertEquals(PrecipitationKind.SNOW, provider.paramsFor(1_000_150L).precipitation?.kind)
			assertEquals("Munich", provider.paramsFor(1_000_150L).overlayLabels?.location)
		} finally {
			response.complete(Result.failure(IllegalStateException("Canceled")))
			refresh.cancel()
		}
	}

	@Test
	fun `leaving simulation after a provider change accepts weather from its configured fallback`() = runTest {
		val berlin = AppSettings(useDeviceLocation = false, manualLatitude = 52.52, manualLongitude = 13.40)
		persistedSettings.value = berlin
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))
		provider.refresh(1_000_000L)
		persistedSettings.value = berlin.copy(sceneSimulatorActive = true, weatherProvider = WeatherProviderType.MET_NORWAY)
		provider.refresh(1_000_060L)
		persistedSettings.value = persistedSettings.value.copy(sceneSimulatorActive = false)

		assertNull(provider.paramsFor(1_000_090L).precipitation)
		provider.refresh(1_000_090L)
		assertEquals(PrecipitationKind.RAIN, provider.paramsFor(1_000_120L).precipitation?.kind)
		assertEquals(WeatherProviderType.OPEN_METEO, provider.status.value.weatherSource?.provider)
	}

	@Test
	fun `changing only the fallback provider invalidates cached weather on simulator exit`() = runTest {
		val berlin = AppSettings(useDeviceLocation = false, manualLatitude = 52.52, manualLongitude = 13.40)
		persistedSettings.value = berlin
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))
		provider.refresh(1_000_000L)
		persistedSettings.value = berlin.copy(sceneSimulatorActive = true, weatherFallbackProvider = WeatherProviderType.MET_NORWAY)
		provider.refresh(1_000_060L)
		persistedSettings.value = persistedSettings.value.copy(sceneSimulatorActive = false)

		assertNull(provider.paramsFor(1_000_090L).precipitation)
	}

	@Test
	fun `switching from a manual place to device location hides its weather and label`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = false, manualLatitude = 52.52, manualLongitude = 13.40, manualLocationLabel = "Berlin", showLocationLabel = true)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))
		provider.refresh(1_000_000L)
		persistedSettings.value = persistedSettings.value.copy(sceneSimulatorActive = true, useDeviceLocation = true)
		provider.refresh(1_000_060L)
		persistedSettings.value = persistedSettings.value.copy(sceneSimulatorActive = false)

		val fallback = provider.paramsFor(1_000_090L)
		assertNull(fallback.precipitation)
		assertNull(fallback.overlayLabels)
	}

	@Test
	fun `renaming a manual place updates its label without discarding matching weather`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = false, manualLatitude = 52.52, manualLongitude = 13.40, manualLocationLabel = "Berlin", showLocationLabel = true)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))
		provider.refresh(1_000_000L)
		persistedSettings.value = persistedSettings.value.copy(manualLocationLabel = "Home", cloudIntensityScale = 0.8f)

		val live = provider.paramsFor(1_000_030L)
		assertEquals(PrecipitationKind.RAIN, live.precipitation?.kind)
		assertEquals("Home", live.overlayLabels?.location)
		assertEquals(0.8f, live.cloudScale, 0.0001f)
	}

	@Test
	fun `a known device location change during simulation requires matching weather on exit`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, showWeatherLabel = true, showLocationLabel = true)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40) andThen GeoLocation(48.14, 11.58)
		coEvery { reverseGeocodingRepository.placeName(52.52, 13.40) } returns "Berlin"
		coEvery { reverseGeocodingRepository.placeName(48.14, 11.58) } returns "Munich"
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))
		coEvery { weatherRepository.current(48.14, 11.58) } returns Result.success(snapshotWith(weatherCode = 71))
		provider.refresh(1_000_000L)
		persistedSettings.value = persistedSettings.value.copy(sceneSimulatorActive = true)
		provider.refresh(1_000_060L, resolveLocationName = true)
		persistedSettings.value = persistedSettings.value.copy(sceneSimulatorActive = false)

		val fallback = provider.paramsFor(1_000_090L)
		assertNull(fallback.precipitation)
		assertEquals(OverlayLabels(weather = null, location = "Munich"), fallback.overlayLabels)
		provider.refresh(1_000_090L)
		assertEquals(PrecipitationKind.SNOW, provider.paramsFor(1_000_120L).precipitation?.kind)
	}

	@Test
	fun `a moved device does not join weather still pending for its earlier coordinates`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, showLocationLabel = true)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40) andThen GeoLocation(52.52, 13.40) andThen GeoLocation(48.14, 11.58)
		coEvery { reverseGeocodingRepository.placeName(52.52, 13.40) } returns "Berlin"
		coEvery { reverseGeocodingRepository.placeName(48.14, 11.58) } returns "Munich"
		val berlinResponse = CompletableDeferred<Result<WeatherSnapshot>>()
		var berlinCalls = 0
		coEvery { weatherRepository.current(52.52, 13.40) } coAnswers {
			if (berlinCalls++ == 0) {
				Result.success(snapshotWith(weatherCode = 63))
			} else {
				berlinResponse.await()
			}
		}

		coEvery { weatherRepository.current(48.14, 11.58) } returns Result.success(snapshotWith(weatherCode = 71))
		provider.refresh(1_000_000L)
		val oldRefresh = async { provider.refresh(1_003_600L) }

		runCurrent()
		persistedSettings.value = persistedSettings.value.copy(sceneSimulatorActive = true)
		provider.refresh(1_003_610L, resolveLocationName = true)
		persistedSettings.value = persistedSettings.value.copy(sceneSimulatorActive = false)
		val matchingRefresh = async { provider.refresh(1_003_620L) }

		runCurrent()
		try {
			berlinResponse.complete(Result.success(snapshotWith(weatherCode = 63)))
			oldRefresh.await()
			matchingRefresh.await()
			assertEquals(PrecipitationKind.SNOW, provider.paramsFor(1_003_630L).precipitation?.kind)
			assertEquals("Munich", provider.paramsFor(1_003_630L).overlayLabels?.location)
		} finally {
			berlinResponse.complete(Result.failure(IllegalStateException("Canceled")))
			oldRefresh.cancel()
			matchingRefresh.cancel()
		}
	}

	private suspend fun assertSettingsLocationStatus(settings: AppSettings) {
		persistedSettings.value = settings
		coEvery { locationRepository.currentLocation() } returns GeoLocation(37.57, 126.98)
		coEvery { weatherRepository.current(37.57, 126.98) } returns Result.success(snapshotWith(weatherCode = 63))
		coEvery { reverseGeocodingRepository.placeName(37.57, 126.98) } returns "Seoul"

		provider.refresh(1_000_000L, resolveLocationName = true)

		assertEquals(
			WeatherSceneStatus(locationLabel = "Seoul", lastRefreshEpochSeconds = 1_000_000L, weatherSource = WeatherSource(WeatherProviderType.OPEN_METEO)),
			provider.status.value
		)
	}

	private fun snapshotWith(weatherCode: Int, source: WeatherSource = WeatherSource(WeatherProviderType.OPEN_METEO), isDay: Boolean = true) = WeatherSnapshot(
		observation = WeatherObservation(
			condition = conditionForWmoCode(weatherCode),
			isDay = isDay,
			temperatureCelsius = 10.0,
			precipitationMillimeters = 0.0,
			windSpeedKilometersPerHour = 5.0,
			cloudCover = CloudCover(50)
		),
		observedAtEpochSeconds = 1_000_000L,
		sunriseEpochSeconds = null,
		sunsetEpochSeconds = null,
		source = source
	)
}
