package xyz.attacktive.weatherd.ui.home

import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.render.SceneParams
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository

@HiltViewModel
class HomeViewModel @Inject constructor(
	private val sceneProvider: WeatherSceneProvider,
	private val photoBackgroundRepository: PhotoBackgroundRepository,
	private val settingsRepository: SettingsRepository
): ViewModel() {
	private val simulatorSettingsMutex = Mutex()

	/** The user's redraw cap, so the preview animates at the same rate the wallpaper will. */
	val frameRateCap = settingsRepository.settings
		.map { it.frameRateCap }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().frameRateCap)

	/** The user's precipitation intensity scale, so the debug cycler simulates the chosen particle density. */
	val precipitationIntensityScale = settingsRepository.settings
		.map { it.precipitationIntensityScale }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().precipitationIntensityScale)

	/** The user's wind intensity scale, so the debug cycler simulates the chosen wind strength. */
	val windIntensityScale = settingsRepository.settings
		.map { it.windIntensityScale }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().windIntensityScale)

	/** The user's cloud intensity scale, so the debug cycler simulates the chosen cloud opacity. */
	val cloudIntensityScale = settingsRepository.settings
		.map { it.cloudIntensityScale }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().cloudIntensityScale)

	/** Whether camera-style lens flare is enabled, so debug mode matches the live wallpaper. */
	val lensFlareEnabled = settingsRepository.settings
		.map { it.lensFlareEnabled }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().lensFlareEnabled)

	/** Whether release builds should expose the scene simulator controls on the home preview. */
	val sceneSimulatorEnabled = settingsRepository.settings
		.map { it.sceneSimulatorEnabled }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().sceneSimulatorEnabled)

	/** Whether the simulator currently replaces live weather for both the preview and the wallpaper. */
	val sceneSimulatorActive = settingsRepository.settings
		.map { it.sceneSimulatorActive }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().sceneSimulatorActive)

	val sceneSimulatorPresetIndex = settingsRepository.settings
		.map { it.sceneSimulatorPresetIndex }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().sceneSimulatorPresetIndex)

	val sceneSimulatorDayPhase = settingsRepository.settings
		.map { it.sceneSimulatorDayPhase }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().sceneSimulatorDayPhase)

	val sceneSimulatorCelestialProgress = settingsRepository.settings
		.map { it.sceneSimulatorCelestialProgress }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().sceneSimulatorCelestialProgress)

	fun setSceneSimulatorActive(active: Boolean) {
		updateSceneSimulator { it.copy(sceneSimulatorActive = active) }
	}

	fun setSceneSimulatorPresetIndex(index: Int) {
		updateSceneSimulator { it.copy(sceneSimulatorPresetIndex = index.coerceAtLeast(0)) }
	}

	fun setSceneSimulatorDayPhase(dayPhase: DayPhase) {
		updateSceneSimulator { it.copy(sceneSimulatorDayPhase = dayPhase) }
	}

	fun setSceneSimulatorCelestialProgress(progress: Float) {
		updateSceneSimulator { it.copy(sceneSimulatorCelestialProgress = progress.coerceIn(0f, 1f)) }
	}

	private fun updateSceneSimulator(transform: (AppSettings) -> AppSettings) {
		viewModelScope.launch {
			simulatorSettingsMutex.withLock {
				val current = settingsRepository.settings.first()
				settingsRepository.save(transform(current))
				sceneProvider.refresh(nowEpochSeconds())
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
	fun currentParams(): SceneParams = sceneProvider.paramsFor(nowEpochSeconds())

	/**
	 * The stored photo to preview as the sky for [scene] during [dayPhase], or null when [scene] draws no photo, no filled bucket covers that phase, or the stored file no longer decodes.
	 * Null is the ordinary case and not a failure: the caller then lets the renderer paint its procedural sky.
	 * Resolving and loading live in [PhotoBackgroundRepository.loadFor] rather than here, so the preview and the wallpaper read the fallback rule off the same line of code; this only hands the preview a way to reach it.
	 * Synchronous on purpose, and the caller owns the bitmap: it borrows it for one `renderBackdrop` call on the thread it rasterizes on, which is what `SceneRenderer.backgroundPhoto`'s unsynchronized shape requires, and must recycle it afterward.
	 */
	fun loadPhotoBackground(scene: BackdropScene, dayPhase: DayPhase) = photoBackgroundRepository.loadFor(scene, dayPhase)

	private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L
}
