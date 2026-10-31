package xyz.attacktive.weatherd.domain.render

import java.time.Instant
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
import xyz.attacktive.weatherd.BuildConfig
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.BirdAppearanceInterval
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.HolidayCelebration
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SunColorPreset
import xyz.attacktive.weatherd.domain.model.SunStyle
import xyz.attacktive.weatherd.domain.model.SunbeamMode
import xyz.attacktive.weatherd.domain.model.TemperatureUnit
import xyz.attacktive.weatherd.domain.model.TreeStyle
import xyz.attacktive.weatherd.domain.model.TreeSeason
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
	fun `auto foliage follows selected latitude in live simulated and offline scenes`() {
		val now = Instant.parse("2026-10-31T12:00:00Z").epochSecond
		val settings = AppSettings(
			treesEnabled = true,
			treeSeason = TreeSeason.AUTO,
			useDeviceLocation = false,
			manualLatitude = 37.57,
			manualLongitude = 126.98
		)
		val fallback = WeatherSceneState(settings)
		val live = fallback.copy(snapshot = snapshotWith(weatherCode = 0))
		val simulated = fallback.copy(settings = settings.copy(sceneSimulatorEnabled = true, sceneSimulatorActive = true))
		for (scene in listOf(fallback, live, simulated)) {
			val north = provider.paramsFor(now, scene)
			assertEquals(TreeSeason.AUTUMN, north.treeSeason)
			val south = provider.paramsFor(now, scene.copy(settings = scene.settings.copy(manualLatitude = -33.9, manualLongitude = 151.2)))
			assertEquals(TreeSeason.SPRING, south.treeSeason)
			assertEquals(backdropSignature(north), backdropSignature(north.copy(treeSeason = south.treeSeason)))
		}

		val device = fallback.copy(settings = settings.copy(useDeviceLocation = true), deviceLocation = GeoLocation(-33.9, 151.2))
		assertEquals(TreeSeason.SPRING, provider.paramsFor(now, device).treeSeason)
		assertEquals(TreeSeason.SPRING, provider.paramsFor(now, device.copy(deviceLocation = null)).treeSeason)
		val winterOverride = device.copy(settings = device.settings.copy(treeSeason = TreeSeason.WINTER))
		assertEquals(TreeSeason.WINTER, provider.paramsFor(now, winterOverride).treeSeason)
	}

	@Test
	fun `seasonal celebrations share live simulated and offline eligibility without refreshing weather`() = runTest {
		val moments = listOf(
			"2026-10-31T22:00:00Z" to HolidayCelebration.HALLOWEEN,
			"2026-12-24T22:00:00Z" to HolidayCelebration.CHRISTMAS,
			"2027-01-01T22:00:00Z" to HolidayCelebration.NEW_YEAR
		)

		for ((instant, holiday) in moments) {
			val now = Instant.parse(instant).epochSecond
			val settings = AppSettings(useDeviceLocation = false, manualLatitude = 0.0, manualLongitude = 0.0)
			val fallback = WeatherSceneState(settings)
			val live = fallback.copy(
				snapshot = snapshotWith(weatherCode = 0, isDay = false).copy(
					observedAtEpochSeconds = now,
					sunriseEpochSeconds = now - 16 * 3600,
					sunsetEpochSeconds = now - 4 * 3600
				)
			)

			for (state in listOf(fallback, live)) {
				val params = provider.paramsFor(now, state)
				val disabled = provider.paramsFor(now, state.copy(settings = settings.copy(seasonalCelebrationsEnabled = false)))
				assertEquals(DayPhase.NIGHT, params.dayPhase)
				assertEquals(holiday, params.holidayCelebration)
				assertEquals(HolidayCelebration.NONE, disabled.holidayCelebration)
				assertEquals(backdropSignature(params), backdropSignature(disabled))
			}

			for (phase in DayPhase.entries) {
				val simulated = fallback.copy(
					settings = settings.copy(sceneSimulatorEnabled = true, sceneSimulatorActive = true, sceneSimulatorDayPhase = phase)
				)

				assertEquals(
					if (phase == DayPhase.NIGHT) {
						holiday
					} else {
						HolidayCelebration.NONE
					},
					provider.paramsFor(now, simulated, debugToolsAvailable = false).holidayCelebration
				)
			}
		}

		coVerify(exactly = 0) { weatherRepository.current(any(), any()) }
	}

	@Test
	fun `holiday opt out changes both consumers without rewriting a captured state`() {
		val now = Instant.parse("2026-10-31T22:00:00Z").epochSecond
		persistedSettings.value = AppSettings(sceneSimulatorEnabled = true, sceneSimulatorActive = true, sceneSimulatorDayPhase = DayPhase.NIGHT)
		val captured = provider.sceneState.value
		assertEquals(HolidayCelebration.HALLOWEEN, provider.paramsFor(now, captured).holidayCelebration)
		persistedSettings.value = persistedSettings.value.copy(seasonalCelebrationsEnabled = false)
		assertEquals(HolidayCelebration.NONE, provider.paramsFor(now).holidayCelebration)
		assertEquals(HolidayCelebration.HALLOWEEN, provider.paramsFor(now, captured).holidayCelebration)
		assertEquals(HolidayCelebration.NONE, provider.paramsFor(Instant.parse("2026-11-01T12:00:00Z").epochSecond, captured).holidayCelebration)
	}

	@Test
	fun `forced holiday uses artifact gate rather than public simulator availability`() {
		val outsideWindow = Instant.parse("2026-07-04T22:00:00Z").epochSecond
		for (holiday in HolidayCelebration.entries.filter { it != HolidayCelebration.NONE }) {
			for (phase in DayPhase.entries) {
				persistedSettings.value = AppSettings(
					sceneSimulatorEnabled = true,
					sceneSimulatorActive = true,
					sceneSimulatorDayPhase = phase,
					seasonalCelebrationsEnabled = false,
					forceHolidayCelebration = true,
					forcedHolidayCelebration = holiday
				)

				val expected = if (BuildConfig.DEBUG && phase == DayPhase.NIGHT) {
					holiday
				} else {
					HolidayCelebration.NONE
				}

				assertEquals(expected, provider.paramsFor(outsideWindow, debugToolsAvailable = false).holidayCelebration)
			}
		}

		persistedSettings.value = persistedSettings.value.copy(forceHolidayCelebration = false)
		assertEquals(HolidayCelebration.NONE, provider.paramsFor(outsideWindow, debugToolsAvailable = false).holidayCelebration)
	}

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

	@Test
	fun `bird changes reach live simulation and fallback without replacing captured scenes`() = runTest {
		persistedSettings.value = AppSettings(
			useDeviceLocation = true,
			birdFlockSize = BirdFlockSize.LARGE,
			birdAppearanceInterval = BirdAppearanceInterval.SECONDS_30
		)

		val fallback = provider.paramsFor(1_000_000L)
		assertEquals(BirdFlockSize.LARGE, fallback.birdFlockSize)
		assertEquals(BirdAppearanceInterval.SECONDS_30, fallback.birdAppearanceInterval)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 0))
		provider.refresh(1_000_000L)

		val captured = provider.sceneState.value
		val live = provider.paramsFor(1_000_030L, captured)
		assertEquals(BirdFlockSize.LARGE, live.birdFlockSize)
		assertEquals(BirdAppearanceInterval.SECONDS_30, live.birdAppearanceInterval)
		assertTrue(showsBirds(live))
		persistedSettings.value = persistedSettings.value.copy(birdFlockSize = BirdFlockSize.OFF)
		assertFalse(showsBirds(provider.paramsFor(1_000_030L)))
		assertTrue(showsBirds(provider.paramsFor(1_000_030L, captured)))
		assertEquals(backdropSignature(live), backdropSignature(provider.paramsFor(1_000_030L)))

		persistedSettings.value = persistedSettings.value.copy(
			sceneSimulatorActive = true,
			sceneSimulatorPresetIndex = 0,
			sceneSimulatorDayPhase = DayPhase.DAY,
			birdFlockSize = BirdFlockSize.MEDIUM,
			birdAppearanceInterval = BirdAppearanceInterval.MINUTE_1
		)

		val simulated = provider.paramsFor(1_000_030L)
		assertEquals(BirdFlockSize.MEDIUM, simulated.birdFlockSize)
		assertEquals(BirdAppearanceInterval.MINUTE_1, simulated.birdAppearanceInterval)
		assertTrue(showsBirds(simulated))
		coVerify(exactly = 1) { weatherRepository.current(any(), any()) }
	}

	@Test
	fun `tree shape changes preserve captured scenes and cached weather across live simulation and fallback`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, treesEnabled = true, treeStyle = TreeStyle.BROADLEAF)
		assertEquals(TreeStyle.BROADLEAF, provider.paramsFor(1_000_000L).treeStyle)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))
		provider.refresh(1_000_000L)

		val captured = provider.sceneState.value
		val broadleaf = provider.paramsFor(1_000_030L, captured)
		assertEquals(TreeStyle.BROADLEAF, broadleaf.treeStyle)
		persistedSettings.value = persistedSettings.value.copy(treeStyle = TreeStyle.EVERGREEN)
		val evergreen = provider.paramsFor(1_000_030L)
		assertEquals(broadleaf.copy(treeStyle = TreeStyle.EVERGREEN), evergreen)
		assertEquals(broadleaf, provider.paramsFor(1_000_030L, captured))
		assertEquals(backdropSignature(broadleaf), backdropSignature(evergreen))

		persistedSettings.value = persistedSettings.value.copy(
			sceneSimulatorActive = true,
			sceneSimulatorDayPhase = DayPhase.DAY,
			treeStyle = TreeStyle.BROADLEAF
		)

		val simulated = provider.paramsFor(1_000_030L)
		assertEquals(TreeStyle.BROADLEAF, simulated.treeStyle)
		assertTrue(simulated.treesEnabled)
		persistedSettings.value = persistedSettings.value.copy(treesEnabled = false)
		val disabled = provider.paramsFor(1_000_030L)
		assertEquals(simulated.copy(treesEnabled = false), disabled)
		coVerify(exactly = 1) { weatherRepository.current(any(), any()) }
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
		WeatherSceneProvider(context, locationRepository, weatherRepository, reverseGeocodingRepository, settingsRepository, photoBackgroundRepository, logger, applicationScope, weatherCacheFixture())
	}

	@After
	fun cancelSettingsCollection() {
		applicationScope.cancel()
	}

	@Test
	fun `changed device fix retains last weather through failure and replaces it on recovery`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(any(), any()) } returns Result.success(snapshotWith(weatherCode = 63))
		provider.refresh(1_000_000L, force = true)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.520001, 13.40)
		coEvery { weatherRepository.current(any(), any()) } coAnswers {
			assertEquals(PrecipitationKind.RAIN, provider.paramsFor(1_000_060L).precipitation?.kind)
			Result.failure(IllegalStateException("offline"))
		}

		provider.refresh(1_000_060L, force = true)
		assertEquals(PrecipitationKind.RAIN, provider.paramsFor(1_000_060L).precipitation?.kind)
		assertEquals(1_000_000L, provider.status.value.lastRefreshEpochSeconds)
		coEvery { weatherRepository.current(any(), any()) } returns Result.success(snapshotWith(weatherCode = 71))
		provider.refresh(1_000_120L, force = true)
		assertEquals(PrecipitationKind.SNOW, provider.paramsFor(1_000_120L).precipitation?.kind)
		assertEquals(1_000_120L, provider.status.value.lastRefreshEpochSeconds)
	}

	@Test
	fun `a failure for another city does not mark current weather stale after returning`() = runTest {
		assertReturningTargetIgnoresOtherFailure(AppSettings(useDeviceLocation = false, manualLatitude = 48.14, manualLongitude = 11.58))
	}

	@Test
	fun `a missing device fix does not mark current weather stale after returning`() = runTest {
		coEvery { locationRepository.currentLocation() } returns null
		assertReturningTargetIgnoresOtherFailure(AppSettings(useDeviceLocation = true))
	}

	private suspend fun assertReturningTargetIgnoresOtherFailure(failingSettings: AppSettings) {
		val berlin = AppSettings(useDeviceLocation = false, manualLatitude = 52.52, manualLongitude = 13.40, updateIntervalMinutes = 30)
		persistedSettings.value = berlin
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))
		coEvery { weatherRepository.current(48.14, 11.58) } returns Result.failure(IllegalStateException("offline"))
		provider.refresh(1_000_000L)

		persistedSettings.value = failingSettings.copy(updateIntervalMinutes = 30)
		provider.refresh(1_000_060L)
		assertEquals(1_000_000L, provider.paramsFor(1_000_060L).weatherNotice?.refreshedAtEpochSeconds)

		persistedSettings.value = berlin
		provider.refresh(1_000_120L)
		coVerify(exactly = 1) { weatherRepository.current(52.52, 13.40) }
		assertNull(provider.paramsFor(1_000_120L).weatherNotice)
		assertNull(provider.status.value.weatherNotice)
	}

	@Test
	fun `obsolete missing device fix cannot mark a newer manual success stale`() = runTest {
		assertObsoleteMissingFixPreservesSuccess(changeToManual = true)
	}

	@Test
	fun `older missing device fix cannot mark a newer same-target success stale`() = runTest {
		assertObsoleteMissingFixPreservesSuccess(changeToManual = false)
	}

	private suspend fun kotlinx.coroutines.test.TestScope.assertObsoleteMissingFixPreservesSuccess(changeToManual: Boolean) {
		val started = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		coEvery { locationRepository.currentLocation() } coAnswers {
			started.complete(Unit)
			release.await()
			null
		}
		val obsolete = async { provider.refresh(1_000_000L, force = true) }
		started.await()
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		if (changeToManual) {
			persistedSettings.value = AppSettings(useDeviceLocation = false, manualLatitude = 48.14, manualLongitude = 11.58)
		}

		coEvery { weatherRepository.current(any(), any()) } returns Result.success(snapshotWith(weatherCode = 63))
		try {
			provider.refresh(1_000_060L, force = true)
		} finally {
			release.complete(Unit)
		}

		obsolete.await()
		assertNull(provider.paramsFor(1_000_060L).weatherNotice)
		assertNull(provider.status.value.weatherNotice)
		assertEquals(1_000_060L, provider.status.value.lastRefreshEpochSeconds)
	}

	@Test
	fun `pending response retains its own place name after simulator location changes`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, showLocationLabel = true, sceneSimulatorEnabled = true)
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40) andThen GeoLocation(48.14, 11.58)
		coEvery { reverseGeocodingRepository.placeName(52.52, 13.40) } returns "Berlin"
		coEvery { reverseGeocodingRepository.placeName(48.14, 11.58) } returns "Munich"
		val started = CompletableDeferred<Unit>()
		val response = CompletableDeferred<Result<WeatherSnapshot>>()
		coEvery { weatherRepository.current(52.52, 13.40) } coAnswers {
			started.complete(Unit)
			response.await()
		}
		val refresh = async { provider.refresh(1_000_000L, force = true) }
		started.await()
		try {
			persistedSettings.value = persistedSettings.value.copy(sceneSimulatorActive = true)
			provider.refresh(1_000_060L, resolveLocationName = true)
		} finally {
			response.complete(Result.success(snapshotWith(weatherCode = 63)))
		}

		refresh.await()
		persistedSettings.value = persistedSettings.value.copy(sceneSimulatorActive = false)
		assertEquals("Berlin", provider.paramsFor(1_000_090L).weatherNotice?.locationLabel)
		assertEquals("Berlin", provider.paramsFor(1_000_090L).overlayLabels?.location)
	}

	@Test
	fun `scene simulator override replaces weather without fetching`() = runTest {
		val presetIndex = SCENE_PRESETS.indexOfFirst { it.name == "SNOW" }
		val preset = SCENE_PRESETS[presetIndex]
		persistedSettings.value = AppSettings(
			backdropScene = BackdropScene.BEACH,
			treesEnabled = true,
			labelVerticalPosition = 0.7f,
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
			moonSizeScale = 1.35f,
			rainbowSizeScale = 1.25f,
			sunColorPreset = SunColorPreset.GOLDEN,
			sunStyle = SunStyle.RADIANT,
			sunbeamMode = SunbeamMode.CLEAR_SKIES_TOO,
			lensFlareEnabled = false,
			sceneSimulatorEnabled = true,
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
		assertTrue(params.treesEnabled)
		assertEquals(0.7f, params.labelVerticalPosition, 0.0001f)
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
		assertEquals(1.35f, params.moonSizeScale, 0.0001f)
		assertEquals(SunColorPreset.GOLDEN, params.sunColorPreset)
		assertEquals(SunStyle.RADIANT, params.sunStyle)
		assertEquals(SunbeamMode.CLEAR_SKIES_TOO, params.sunbeamMode)
		assertFalse(params.lensFlareEnabled)
		assertEquals(1.25f, params.rainbowSizeScale, 0.0001f)
		assertEquals(moonPhaseFor(1_000_030L), params.moonPhase, 0.0001f)
		coVerify(exactly = 0) { locationRepository.currentLocation() }
		coVerify(exactly = 0) { weatherRepository.current(any(), any()) }
	}

	@Test
	fun `simulation resolves an enabled device location label without live weather`() = runTest {
		persistedSettings.value = AppSettings(
			useDeviceLocation = true,
			showWeatherLabel = true,
			showLocationLabel = true,
			sceneSimulatorEnabled = true,
			sceneSimulatorActive = true
		)

		coEvery { locationRepository.currentLocation() } returns GeoLocation(37.57, 126.98)
		coEvery { reverseGeocodingRepository.placeName(37.57, 126.98) } returns "Seoul"
		provider.refresh(1_000_000L)

		assertEquals(OverlayLabels(weather = "CLEAR", location = "Seoul"), provider.paramsFor(1_000_030L).overlayLabels)
	}

	@Test
	fun `simulation preserves enabled labels without waiting for live weather`() = runTest {
		persistedSettings.value = AppSettings(
			useDeviceLocation = false,
			manualLatitude = 41.9,
			manualLongitude = 12.5,
			manualLocationLabel = "Rome",
			showWeatherLabel = true,
			showLocationLabel = true,
			sceneSimulatorEnabled = true,
			sceneSimulatorActive = true,
			sceneSimulatorPresetIndex = 0,
			labelVerticalPosition = 0.65f
		)

		val captured = provider.sceneState.value
		val clear = provider.paramsFor(1_000_000L, captured)
		assertEquals(OverlayLabels(weather = "CLEAR", location = "Rome"), clear.overlayLabels)
		assertEquals(0.65f, clear.labelVerticalPosition, 0.0001f)

		persistedSettings.value = persistedSettings.value.copy(sceneSimulatorPresetIndex = SCENE_PRESETS.indexOfFirst { it.name == "RAIN" })
		assertEquals(OverlayLabels(weather = "RAIN", location = "Rome"), provider.paramsFor(1_000_030L).overlayLabels)
		assertEquals(OverlayLabels(weather = "CLEAR", location = "Rome"), provider.paramsFor(1_000_030L, captured).overlayLabels)

		persistedSettings.value = persistedSettings.value.copy(showWeatherLabel = false)
		assertEquals(OverlayLabels(weather = null, location = "Rome"), provider.paramsFor(1_000_030L).overlayLabels)

		persistedSettings.value = persistedSettings.value.copy(showLocationLabel = false)
		assertNull(provider.paramsFor(1_000_030L).overlayLabels)

		persistedSettings.value = persistedSettings.value.copy(showWeatherLabel = true)
		assertEquals(OverlayLabels(weather = "RAIN", location = null), provider.paramsFor(1_000_030L).overlayLabels)
	}

	@Test
	fun `scene simulator adopts the real calendar lunar phase at the evaluated moment`() = runTest {
		persistedSettings.value = AppSettings(
			useDeviceLocation = true,
			sceneSimulatorEnabled = true,
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
		val live = AppSettings(useDeviceLocation = true, sceneSimulatorEnabled = true)
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
		val settings = MutableStateFlow(AppSettings(useDeviceLocation = true, backdropScene = BackdropScene.BEACH, sceneSimulatorEnabled = true))
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
		val settings = MutableStateFlow(AppSettings(useDeviceLocation = true, sceneSimulatorEnabled = true))
		every { settingsRepository.settings } returns settings
		val location = CompletableDeferred<GeoLocation?>()
		coEvery { locationRepository.currentLocation() } coAnswers { location.await() }

		val refresh = async { provider.refresh(1_000_000L) }

		runCurrent()

		try {
			settings.value = settings.value.copy(sceneSimulatorActive = true, sceneSimulatorPresetIndex = SCENE_PRESETS.indexOfFirst { it.name == "SNOW" })
			runCurrent()
			assertEquals(PrecipitationKind.SNOW, provider.paramsFor(1_000_030L).precipitation?.kind)

			settings.value = settings.value.copy(sceneSimulatorActive = false, windIntensityScale = 1.5f, sunStyle = SunStyle.RADIANT, sunbeamMode = SunbeamMode.OFF, moonSizeScale = 1.75f, rainbowSizeScale = 0.75f)
			runCurrent()
			val fallback = provider.paramsFor(1_000_030L)
			assertNull(fallback.precipitation)
			assertEquals(1.5f, fallback.windScale, 0.0001f)
			assertEquals(SunStyle.RADIANT, fallback.sunStyle)
			assertEquals(SunbeamMode.OFF, fallback.sunbeamMode)
			assertEquals(1.75f, fallback.moonSizeScale, 0.0001f)
			assertEquals(0.75f, fallback.rainbowSizeScale, 0.0001f)
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
	fun `a failed provider switch backs off before retrying`() = runTest {
		val openMeteo = AppSettings(useDeviceLocation = true, weatherProvider = WeatherProviderType.OPEN_METEO)
		persistedSettings.value = openMeteo
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_000L)

		persistedSettings.value = openMeteo.copy(weatherProvider = WeatherProviderType.MET_NORWAY)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.failure(IllegalStateException("provider unavailable"))

		provider.refresh(1_000_060L)
		provider.refresh(1_000_090L)

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

		persistedSettings.value = device.copy(sunVisible = false, moonVisible = false, sunSizeScale = 1.8f, moonSizeScale = 1.45f, rainbowSizeScale = 1.3f, sunColorPreset = SunColorPreset.ORANGE, sunStyle = SunStyle.RADIANT, sunbeamMode = SunbeamMode.CLEAR_SKIES_TOO)
		provider.refresh(1_000_060L)

		val params = provider.paramsFor(1_000_090L)
		assertFalse(params.sunVisible)
		assertFalse(params.moonVisible)
		assertEquals(1.8f, params.sunSizeScale, 0.0001f)
		assertEquals(1.45f, params.moonSizeScale, 0.0001f)
		assertEquals(1.3f, params.rainbowSizeScale, 0.0001f)
		assertEquals(SunColorPreset.ORANGE, params.sunColorPreset)
		assertEquals(SunStyle.RADIANT, params.sunStyle)
		assertEquals(SunbeamMode.CLEAR_SKIES_TOO, params.sunbeamMode)
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
	fun `rainbow size setting reaches scene params even when refresh is throttled`() = runTest {
		val device = AppSettings(useDeviceLocation = true, rainbowSizeScale = 1f)
		persistedSettings.value = device
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 3))

		provider.refresh(1_000_000L)
		assertEquals(1f, provider.paramsFor(1_000_030L).rainbowSizeScale, 0.0001f)

		persistedSettings.value = device.copy(rainbowSizeScale = 1.35f)
		provider.refresh(1_000_060L)

		assertEquals(1.35f, provider.paramsFor(1_000_090L).rainbowSizeScale, 0.0001f)
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
		persistedSettings.value = AppSettings(useDeviceLocation = true, backdropScene = BackdropScene.PHOTO, treesEnabled = true)
		coEvery { locationRepository.currentLocation() } returns null
		photoState.value = photoState.value.copy(revision = 7)

		provider.refresh(1_000_000L)

		// No fix, so no snapshot; the clock-lit fallback still has to carry the revision or the very first photo import would never draw.
		assertEquals(7, provider.paramsFor(1_000_030L).photoRevision)
		assertTrue(provider.paramsFor(1_000_030L).treesEnabled)
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
	fun `position changes use the latest captured settings without refreshing weather`() = runTest {
		persistedSettings.value = AppSettings(
			useDeviceLocation = false,
			manualLatitude = 41.9,
			manualLongitude = 12.5,
			manualLocationLabel = "Rome",
			showLocationLabel = true,
			labelVerticalPosition = 0.2f
		)

		val fallbackState = provider.sceneState.value
		assertEquals("Rome", provider.paramsFor(1_000_000L, fallbackState).overlayLabels?.location)
		assertEquals(0.2f, provider.paramsFor(1_000_000L, fallbackState).labelVerticalPosition, 0.0001f)
		coEvery { weatherRepository.current(41.9, 12.5) } returns Result.success(snapshotWith(weatherCode = 63))
		provider.refresh(1_000_000L)
		val liveState = provider.sceneState.value
		persistedSettings.value = persistedSettings.value.copy(labelVerticalPosition = 0.8f)

		assertEquals(0.8f, provider.paramsFor(1_000_030L).labelVerticalPosition, 0.0001f)
		assertEquals(0.2f, provider.paramsFor(1_000_030L, liveState).labelVerticalPosition, 0.0001f)
		assertEquals(0.2f, provider.paramsFor(1_000_030L, fallbackState).labelVerticalPosition, 0.0001f)
		assertEquals("Rome", provider.paramsFor(1_000_030L).overlayLabels?.location)
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
	fun `clearing a manual city retains last success status without inventing a current fix`() = runTest {
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

		assertNull(provider.status.value.locationLabel)
		assertEquals(1_000_000L, provider.status.value.lastRefreshEpochSeconds)
		assertEquals("Seoul", provider.status.value.weatherNotice?.locationLabel)
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
		val device = AppSettings(useDeviceLocation = true, precipitationIntensityScale = 1.5f, windIntensityScale = 0.5f, cloudIntensityScale = 0.8f, cloudContrastScale = 1.3f, skyBrightnessScale = 0.75f, nightBrightnessScale = 0.65f, skySaturationScale = 1.25f, skyColorPreset = SkyColorPreset.WARM, treesEnabled = true)
		persistedSettings.value = device
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))

		provider.refresh(1_000_000L)

		// A wind of 5.0 km/h against the 40.0 km/h ceiling shapes to 0.2872; the 0.5x user preference rides on windScale.
		val initialParams = provider.paramsFor(1_000_030L)
		assertTrue(initialParams.treesEnabled)
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
		persistedSettings.value = device.copy(precipitationIntensityScale = 0.25f, windIntensityScale = 2f, cloudIntensityScale = 1.5f, cloudContrastScale = 0.7f, skyBrightnessScale = 1.3f, nightBrightnessScale = 0.2f, skySaturationScale = 0.6f, skyColorPreset = SkyColorPreset.CYBERPUNK, treesEnabled = false)
		provider.refresh(1_000_060L)

		// The new wind scale reaches scene params while windFactor stays an honest observation reading.
		val throttledParams = provider.paramsFor(1_000_090L)
		assertFalse(throttledParams.treesEnabled)
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
	fun `leaving simulation after a city change retains original weather until matching weather arrives`() = runTest {
		val berlin = AppSettings(useDeviceLocation = false, manualLatitude = 52.52, manualLongitude = 13.40, manualLocationLabel = "Berlin", showWeatherLabel = true, showLocationLabel = true, sceneSimulatorEnabled = true)
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
		assertEquals(PrecipitationKind.RAIN, fallback.precipitation?.kind)
		assertEquals("Berlin", fallback.overlayLabels?.location)
		assertEquals("Berlin", fallback.weatherNotice?.locationLabel)

		val response = CompletableDeferred<Result<WeatherSnapshot>>()
		coEvery { weatherRepository.current(48.14, 11.58) } coAnswers { response.await() }

		val refresh = async { provider.refresh(1_000_120L) }

		runCurrent()
		try {
			assertEquals(PrecipitationKind.RAIN, provider.paramsFor(1_000_120L).precipitation?.kind)
			assertEquals("Berlin", provider.paramsFor(1_000_120L).overlayLabels?.location)
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
		val berlin = AppSettings(useDeviceLocation = false, manualLatitude = 52.52, manualLongitude = 13.40, sceneSimulatorEnabled = true)
		persistedSettings.value = berlin
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))
		provider.refresh(1_000_000L)
		persistedSettings.value = berlin.copy(sceneSimulatorActive = true, weatherProvider = WeatherProviderType.MET_NORWAY)
		provider.refresh(1_000_060L)
		persistedSettings.value = persistedSettings.value.copy(sceneSimulatorActive = false)

		assertEquals(PrecipitationKind.RAIN, provider.paramsFor(1_000_090L).precipitation?.kind)
		assertEquals(1_000_000L, provider.paramsFor(1_000_090L).weatherNotice?.refreshedAtEpochSeconds)
		provider.refresh(1_000_090L)
		assertEquals(PrecipitationKind.RAIN, provider.paramsFor(1_000_120L).precipitation?.kind)
		assertEquals(WeatherProviderType.OPEN_METEO, provider.status.value.weatherSource?.provider)
	}

	@Test
	fun `changing only the fallback provider marks retained weather stale on simulator exit`() = runTest {
		val berlin = AppSettings(useDeviceLocation = false, manualLatitude = 52.52, manualLongitude = 13.40, sceneSimulatorEnabled = true)
		persistedSettings.value = berlin
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))
		provider.refresh(1_000_000L)
		persistedSettings.value = berlin.copy(sceneSimulatorActive = true, weatherFallbackProvider = WeatherProviderType.MET_NORWAY)
		provider.refresh(1_000_060L)
		persistedSettings.value = persistedSettings.value.copy(sceneSimulatorActive = false)

		assertEquals(PrecipitationKind.RAIN, provider.paramsFor(1_000_090L).precipitation?.kind)
		assertEquals(1_000_000L, provider.paramsFor(1_000_090L).weatherNotice?.refreshedAtEpochSeconds)
	}

	@Test
	fun `switching from a manual place to device location retains original weather and label`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = false, manualLatitude = 52.52, manualLongitude = 13.40, manualLocationLabel = "Berlin", showLocationLabel = true)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 63))
		coEvery { locationRepository.currentLocation() } returns null
		provider.refresh(1_000_000L)
		persistedSettings.value = persistedSettings.value.copy(sceneSimulatorActive = true, useDeviceLocation = true)
		provider.refresh(1_000_060L)
		persistedSettings.value = persistedSettings.value.copy(sceneSimulatorActive = false)

		val fallback = provider.paramsFor(1_000_090L)
		assertEquals(PrecipitationKind.RAIN, fallback.precipitation?.kind)
		assertEquals("Berlin", fallback.overlayLabels?.location)
		assertEquals("Berlin", fallback.weatherNotice?.locationLabel)
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
	fun `a known device location change during simulation retains weather without throttling its replacement`() = runTest {
		persistedSettings.value = AppSettings(useDeviceLocation = true, showWeatherLabel = true, showLocationLabel = true, sceneSimulatorEnabled = true)
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
		assertEquals(PrecipitationKind.RAIN, fallback.precipitation?.kind)
		assertEquals("Berlin", fallback.overlayLabels?.location)
		assertEquals("Berlin", fallback.weatherNotice?.locationLabel)
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

	@Test
	fun `readability overlay settings reach fallback scene params`() = runTest {
		persistedSettings.value = AppSettings(
			useDeviceLocation = false,
			wallpaperDimmingStrength = 0.35f,
			statusBarShadeStrength = 0.45f
		)

		val params = provider.paramsFor(1_000_000L)
		assertEquals(0.35f, params.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0.45f, params.statusBarShadeStrength, 0.0001f)
	}

	@Test
	fun `readability overlay settings reach live scene params`() = runTest {
		persistedSettings.value = AppSettings(
			useDeviceLocation = false,
			manualLatitude = 52.52,
			manualLongitude = 13.40,
			wallpaperDimmingStrength = 0.5f,
			statusBarShadeStrength = 0.25f
		)

		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 0))
		provider.refresh(1_000_000L)

		val params = provider.paramsFor(1_000_030L)
		assertEquals(0.5f, params.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0.25f, params.statusBarShadeStrength, 0.0001f)
	}

	@Test
	fun `readability overlay settings reach simulated scene params`() = runTest {
		persistedSettings.value = AppSettings(
			useDeviceLocation = false,
			sceneSimulatorEnabled = true,
			sceneSimulatorActive = true,
			wallpaperDimmingStrength = 0.6f,
			statusBarShadeStrength = 0.15f
		)

		val params = provider.paramsFor(1_000_000L)
		assertEquals(0.6f, params.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0.15f, params.statusBarShadeStrength, 0.0001f)
	}

	@Test
	fun `sun horizontal position reaches fallback scene params`() = runTest {
		persistedSettings.value = AppSettings(
			useDeviceLocation = false,
			sunHorizontalPosition = 0.25f
		)

		val params = provider.paramsFor(1_000_000L)

		assertEquals(0.25f, params.sunHorizontalPosition, 0.0001f)
	}

	@Test
	fun `sun horizontal position reaches live scene params even when refresh is throttled`() = runTest {
		val device = AppSettings(useDeviceLocation = true, sunHorizontalPosition = 0.72f)

		persistedSettings.value = device
		coEvery { locationRepository.currentLocation() } returns GeoLocation(52.52, 13.40)
		coEvery { weatherRepository.current(52.52, 13.40) } returns Result.success(snapshotWith(weatherCode = 0))

		provider.refresh(1_000_000L)
		assertEquals(0.72f, provider.paramsFor(1_000_030L).sunHorizontalPosition, 0.0001f)

		persistedSettings.value = device.copy(sunHorizontalPosition = 0.90f)
		provider.refresh(1_000_060L)

		assertEquals(0.90f, provider.paramsFor(1_000_090L).sunHorizontalPosition, 0.0001f)
	}

	@Test
	fun `sun horizontal position reaches simulated scene params`() = runTest {
		persistedSettings.value = AppSettings(
			useDeviceLocation = false,
			sceneSimulatorEnabled = true,
			sceneSimulatorActive = true,
			sunHorizontalPosition = 0.35f
		)

		val params = provider.paramsFor(1_000_000L)

		assertEquals(0.35f, params.sunHorizontalPosition, 0.0001f)
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
