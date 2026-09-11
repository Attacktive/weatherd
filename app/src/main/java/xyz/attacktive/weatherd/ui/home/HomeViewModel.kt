package xyz.attacktive.weatherd.ui.home

import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.photoBucketFor
import xyz.attacktive.weatherd.domain.render.SceneParams
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository

@HiltViewModel
class HomeViewModel @Inject constructor(
	private val sceneProvider: WeatherSceneProvider,
	private val photoBackgroundRepository: PhotoBackgroundRepository,
	settingsRepository: SettingsRepository
): ViewModel() {
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

	/** Kicks a weather fetch (rate-limited by the provider) so the preview tracks the latest conditions and any settings change. */
	fun refresh() {
		viewModelScope.launch {
			sceneProvider.refresh(nowEpochSeconds())
		}
	}

	/** The scene to preview right now — real weather once it has loaded, a clock-lit clear sky until then. */
	fun currentParams(): SceneParams = sceneProvider.paramsFor(nowEpochSeconds())

	/**
	 * The stored photo to preview as the sky during [dayPhase], or null when no filled bucket covers that phase or the stored file no longer decodes.
	 * Null is the ordinary case and not a failure: the caller then lets the renderer paint its procedural sky.
	 * Decoded synchronously so the caller can borrow it for one `renderBackdrop` call on the thread it rasterizes on, which is what `SceneRenderer.backgroundPhoto`'s unsynchronized shape requires; the caller owns the bitmap and must recycle it.
	 */
	fun loadPhotoBackground(dayPhase: DayPhase): Bitmap? {
		val bucket = photoBucketFor(dayPhase, photoBackgroundRepository.availableNow()) ?: return null

		return photoBackgroundRepository.load(bucket)
	}

	private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L
}
