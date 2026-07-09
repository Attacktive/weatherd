package xyz.attacktive.weatherd.ui.home

import javax.inject.Inject
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.attacktive.weatherd.domain.render.SceneParams
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider

@HiltViewModel
class HomeViewModel @Inject constructor(private val sceneProvider: WeatherSceneProvider): ViewModel() {
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
