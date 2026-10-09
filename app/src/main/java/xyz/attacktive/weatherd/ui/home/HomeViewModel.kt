package xyz.attacktive.weatherd.ui.home

import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.render.SCENE_PRESETS
import xyz.attacktive.weatherd.domain.render.SceneParams
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider
import xyz.attacktive.weatherd.domain.render.WeatherSceneState
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.SettingsMutation
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.platform.LensFlareMotionSensor

@HiltViewModel
class HomeViewModel @Inject constructor(
	private val sceneProvider: WeatherSceneProvider,
	private val photoBackgroundRepository: PhotoBackgroundRepository,
	private val settingsRepository: SettingsRepository,
	val lensFlareMotionSensor: LensFlareMotionSensor
): ViewModel() {
	private val simulatorSettingsMutex = Mutex()

	/** One immutable selection and weather snapshot shared with the live wallpaper. */
	val sceneState = sceneProvider.sceneState

	fun setSceneSimulatorActive(active: Boolean) {
		updateSceneSimulator { SettingsMutation.SceneSimulatorActive(active) }
	}

	fun toggleSceneSimulator() {
		updateSceneSimulator { SettingsMutation.SceneSimulatorActive(!it.sceneSimulatorActive) }
	}

	fun changeSceneSimulatorPreset(step: Int) {
		updateSceneSimulator {
			val current = it.sceneSimulatorPresetIndex.coerceIn(0, SCENE_PRESETS.lastIndex)
			SettingsMutation.SceneSimulatorPresetIndex(Math.floorMod(current + step, SCENE_PRESETS.size))
		}
	}

	fun changeSceneSimulatorDayPhase(step: Int) {
		updateSceneSimulator {
			SettingsMutation.SceneSimulatorDayPhase(DayPhase.entries[Math.floorMod(it.sceneSimulatorDayPhase.ordinal + step, DayPhase.entries.size)])
		}
	}

	fun setSceneSimulatorCelestialProgress(progress: Float) {
		updateSceneSimulator { SettingsMutation.SceneSimulatorCelestialProgress(progress) }
	}

	private fun updateSceneSimulator(mutationFor: (AppSettings) -> SettingsMutation) {
		viewModelScope.launch {
			val mutation = simulatorSettingsMutex.withLock {
				val mutation = mutationFor(settingsRepository.settings.first())
				settingsRepository.update(listOf(mutation))
				mutation
			}

			if (mutation is SettingsMutation.SceneSimulatorActive && !mutation.active) {
				refresh()
			}
		}
	}

	/** Kicks a weather fetch (rate-limited by the provider) so the preview tracks the latest conditions and any settings change. */
	fun refresh() {
		viewModelScope.launch {
			sceneProvider.refresh(nowEpochSeconds())
		}
	}

	/** The scene to preview right now — real weather once it has loaded, a clock-lit clear sky until then. */
	fun currentParams(state: WeatherSceneState = sceneState.value): SceneParams = sceneProvider.paramsFor(nowEpochSeconds(), state)

	/**
	 * The stored photo to preview as the sky for [scene] during [dayPhase], or null when [scene] draws no photo, no filled bucket covers that phase, or the stored file no longer decodes.
	 * Null is the ordinary case and not a failure: the caller then lets the renderer paint its procedural sky.
	 * Resolving and loading live in [PhotoBackgroundRepository.loadFor] rather than here, so the preview and the wallpaper read the fallback rule off the same line of code; this only hands the preview a way to reach it.
	 * Synchronous on purpose, and the caller owns the bitmap: it borrows it for one `renderBackdrop` call on the thread it rasterizes on, which is what `SceneRenderer.backgroundPhoto`'s unsynchronized shape requires, and must recycle it afterward.
	 */
	fun loadPhotoBackground(scene: BackdropScene, dayPhase: DayPhase) = photoBackgroundRepository.loadFor(scene, dayPhase)

	/** Whether the preview will actually resolve [scene] to a stored photo for [dayPhase], without allocating that bitmap. */
	fun hasPhotoBackground(scene: BackdropScene, dayPhase: DayPhase) = photoBackgroundRepository.hasFor(scene, dayPhase)

	private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L
}
