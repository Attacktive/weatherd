package xyz.attacktive.weatherd.ui.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider
import xyz.attacktive.weatherd.domain.render.WeatherSceneState
import xyz.attacktive.weatherd.domain.repository.LocationRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundState
import xyz.attacktive.weatherd.domain.repository.ReverseGeocodingRepository
import xyz.attacktive.weatherd.domain.repository.SettingsMutation
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.repository.WeatherRepository
import xyz.attacktive.weatherd.platform.LensFlareMotionSensor
import xyz.attacktive.weatherd.util.AppLogger

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Test
	fun `queued button presses apply every toggle and step before scene publication`() = runTest {
		Dispatchers.setMain(StandardTestDispatcher(testScheduler))
		val repository = SettingsRepository(
			PreferenceDataStoreFactory.create(scope = backgroundScope) {
				temporaryFolder.newFile("simulator.preferences_pb")
			}
		)

		val provider = mockk<WeatherSceneProvider> {
			every { sceneState } returns MutableStateFlow(WeatherSceneState(AppSettings()))
			coEvery { refresh(any(), any(), any()) } returns Unit
		}

		val viewModel = HomeViewModel(provider, mockk(), repository, mockk())
		try {
			viewModel.toggleSceneSimulator()
			viewModel.toggleSceneSimulator()
			viewModel.changeSceneSimulatorPreset(1)
			viewModel.changeSceneSimulatorPreset(1)
			viewModel.changeSceneSimulatorDayPhase(1)
			viewModel.changeSceneSimulatorDayPhase(1)
			viewModel.viewModelScope.coroutineContext.job.children.toList().joinAll()

			val settings = repository.settings.first()
			assertFalse(settings.sceneSimulatorActive)
			assertEquals(2, settings.sceneSimulatorPresetIndex)
			assertEquals(DayPhase.NIGHT, settings.sceneSimulatorDayPhase)
		} finally {
			viewModel.viewModelScope.cancel()
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `simulator can be reenabled and changed before its exit refresh finishes`() = runTest {
		Dispatchers.setMain(StandardTestDispatcher(testScheduler))
		val settings = MutableStateFlow(AppSettings(sceneSimulatorActive = true, sceneSimulatorDayPhase = DayPhase.NIGHT))
		val repository = mockk<SettingsRepository> {
			every { defaults } returns AppSettings()
			every { this@mockk.settings } returns settings
		}

		coEvery { repository.update(any()) } coAnswers {
			firstArg<List<SettingsMutation>>().forEach { mutation ->
				settings.value = when (mutation) {
					is SettingsMutation.SceneSimulatorActive -> settings.value.copy(sceneSimulatorActive = mutation.active)
					is SettingsMutation.SceneSimulatorDayPhase -> settings.value.copy(sceneSimulatorDayPhase = mutation.dayPhase)
					else -> error("Unexpected mutation: $mutation")
				}
			}
		}

		val response = CompletableDeferred<Result<WeatherSnapshot>>()
		val locations = mockk<LocationRepository> {
			coEvery { currentLocation() } returns GeoLocation(52.52, 13.40)
		}

		val weather = mockk<WeatherRepository> {
			coEvery { current(any(), any()) } coAnswers { response.await() }
		}

		val photos = mockk<PhotoBackgroundRepository> {
			every { state } returns MutableStateFlow(PhotoBackgroundState(emptySet()))
		}

		val provider = WeatherSceneProvider(
			context = mockk<Context>(relaxed = true),
			locationRepository = locations,
			weatherRepository = weather,
			reverseGeocodingRepository = mockk<ReverseGeocodingRepository>(),
			settingsRepository = repository,
			photoBackgroundRepository = photos,
			logger = mockk<AppLogger>(relaxed = true),
			applicationScope = backgroundScope
		)

		val viewModel = HomeViewModel(provider, photos, repository, mockk<LensFlareMotionSensor>())

		try {
			runCurrent()
			viewModel.setSceneSimulatorActive(false)
			runCurrent()
			viewModel.setSceneSimulatorActive(true)
			viewModel.changeSceneSimulatorDayPhase(-1)
			runCurrent()

			assertEquals(DayPhase.DUSK, provider.paramsFor(1_000_000L).dayPhase)
			assertFalse(response.isCompleted)

			response.complete(Result.failure(IllegalStateException("Offline")))
			runCurrent()
			assertEquals(DayPhase.DUSK, provider.paramsFor(1_000_000L).dayPhase)
		} finally {
			viewModel.viewModelScope.cancel()
			Dispatchers.resetMain()
		}
	}
}
