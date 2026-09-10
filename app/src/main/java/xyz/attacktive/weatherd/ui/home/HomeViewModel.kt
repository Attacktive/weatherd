package xyz.attacktive.weatherd.ui.home

import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.render.SceneParams
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider
import xyz.attacktive.weatherd.domain.repository.SettingsRepository

@HiltViewModel
class HomeViewModel @Inject constructor(
	private val sceneProvider: WeatherSceneProvider,
	settingsRepository: SettingsRepository
): ViewModel() {
	/** The user's redraw cap, so the preview animates at the same rate the wallpaper will. */
	val frameRateCap = settingsRepository.settings
		.map { it.frameRateCap }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings().frameRateCap)

	/** Kicks a weather fetch (rate-limited by the provider) so the preview tracks the latest conditions and any settings change. */
	fun refresh() {
		viewModelScope.launch {
			sceneProvider.refresh(nowEpochSeconds())
		}
	}

	/** The scene to preview right now — real weather once it has loaded, a clock-lit clear sky until then. */
	fun currentParams(): SceneParams = sceneProvider.paramsFor(nowEpochSeconds())

	private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L
}
