package xyz.attacktive.weatherd.ui.settings

import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.di.ApplicationScope
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.SavedAppearancePreset
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot
import xyz.attacktive.weatherd.domain.model.GeoPlace
import xyz.attacktive.weatherd.domain.model.PhotoBucket
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider
import xyz.attacktive.weatherd.domain.repository.AppearancePresetRepository
import xyz.attacktive.weatherd.domain.repository.AppearancePresetStorageState
import xyz.attacktive.weatherd.domain.repository.GeocodingRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.SettingsMutation
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.repository.settingsMutationsBetween

/** UI state for the city-name search shown under the manual-location option. */
sealed interface CitySearchState {
	data object Idle: CitySearchState
	data object Loading: CitySearchState
	data object Empty: CitySearchState
	data class Results(val places: List<GeoPlace>): CitySearchState
	data class Error(val message: String): CitySearchState
}

sealed interface AppearancePresetMutationState {
	data object Idle: AppearancePresetMutationState
	data object InProgress: AppearancePresetMutationState
	data object Succeeded: AppearancePresetMutationState
	data object Failed: AppearancePresetMutationState
}

private sealed interface SearchTrigger {
	val query: String

	data class Debounced(override val query: String): SearchTrigger
	data class Immediate(override val query: String): SearchTrigger
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
	application: Application,
	private val settingsRepository: SettingsRepository,
	private val appearancePresetRepository: AppearancePresetRepository,
	private val geocodingRepository: GeocodingRepository,
	private val photoBackgroundRepository: PhotoBackgroundRepository,
	private val sceneProvider: WeatherSceneProvider,
	@ApplicationScope private val applicationScope: CoroutineScope
): AndroidViewModel(application) {
	val defaults = settingsRepository.defaults
	val settings = settingsRepository.settings
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), defaults)
	val weatherStatus = sceneProvider.status
	val appearancePresets = appearancePresetRepository.state
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppearancePresetStorageState.Ready(emptyList()))

	private val _appearancePresetMutationState = MutableStateFlow<AppearancePresetMutationState>(AppearancePresetMutationState.Idle)
	val appearancePresetMutationState = _appearancePresetMutationState.asStateFlow()

	private val weatherRefreshMutex = Mutex()
	private val _weatherRefreshInProgress = MutableStateFlow(false)
	val weatherRefreshInProgress = _weatherRefreshInProgress.asStateFlow()

	/**
	 * The usable photo buckets, derived from the repository's coherent photo state.
	 * The repository is the only truth about which files exist, which is what keeps a failed import from leaving a row claiming a photo it never wrote.
	 */
	val photoBuckets = photoBackgroundRepository.state
		.map { it.available }
		.distinctUntilChanged()
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), photoBackgroundRepository.state.value.available)

	/**
	 * Downsampled thumbnails for the user's photos, keyed by bucket.
	 * Decoded off the main thread and republished whenever a photo is added, replaced, or cleared.
	 */
	val photoThumbnails = photoBackgroundRepository.state
		.map { it.revision }
		.distinctUntilChanged()
		.mapLatest {
			PhotoBucket.entries.mapNotNull { bucket ->
				val thumbnail = photoBackgroundRepository.loadThumbnail(bucket)
				if (thumbnail != null) {
					bucket to thumbnail
				} else {
					null
				}
			}
			.toMap()
		}
		.flowOn(Dispatchers.IO)
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

	private val _photoImportFailed = MutableStateFlow(false)
	val photoImportFailed = _photoImportFailed.asStateFlow()

	private val typingQueries = MutableSharedFlow<String>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
	private val immediateQueries = MutableSharedFlow<String>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

	private val _citySearch = MutableStateFlow<CitySearchState>(CitySearchState.Idle)
	val citySearch = _citySearch.asStateFlow()

	init {
		val debouncedTyping = typingQueries
			.map { it.trim() }
			.distinctUntilChanged()
			.map { SearchTrigger.Debounced(it) }

		val immediateSearch = immediateQueries
			.map { SearchTrigger.Immediate(it.trim()) }

		viewModelScope.launch { refreshWeatherStatus(force = false) }

		viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
			merge(debouncedTyping, immediateSearch)
				.collectLatest { trigger ->
					val trimmed = trigger.query
					if (trimmed.length < 2) {
						_citySearch.value = CitySearchState.Idle
						return@collectLatest
					}

					if (trigger is SearchTrigger.Debounced) {
						delay(CITY_SEARCH_DEBOUNCE_MILLIS)
					}

					_citySearch.value = CitySearchState.Loading

					try {
						geocodingRepository.search(trimmed)
							.onSuccess { places ->
								_citySearch.value = if (places.isEmpty()) {
									CitySearchState.Empty
								} else {
									CitySearchState.Results(places)
								}
							}
							.onFailure {
								_citySearch.value = CitySearchState.Error(it.message ?: getApplication<Application>().getString(R.string.city_search_failed))
							}
					} catch (cancellationException: CancellationException) {
						throw cancellationException
					}
				}
		}
	}

	fun applySettingsChange(previous: AppSettings, updated: AppSettings) {
		val mutations = settingsMutationsBetween(previous, updated)
		if (mutations.isEmpty()) {
			return
		}

		val weatherProviderChanged = mutations.any { mutation ->
			mutation is SettingsMutation.WeatherProvider || mutation is SettingsMutation.WeatherFallbackProvider
		}

		viewModelScope.launch {
			settingsRepository.update(mutations)

			if (weatherProviderChanged) {
				refreshWeatherStatus(force = true)
			}
		}
	}

	fun setUseDeviceLocation(enabled: Boolean) {
		viewModelScope.launch {
			settingsRepository.update(listOf(SettingsMutation.UseDeviceLocation(enabled)))
			refreshWeatherStatus(force = true)
		}
	}


	fun createAppearancePreset(name: String) {
		mutateAppearancePreset {
			val snapshot = settingsRepository.settings.first().toAppearancePresetSnapshot()

			appearancePresetRepository.create(name, snapshot)
		}
	}

	fun replaceAppearancePreset(id: String, name: String) {
		mutateAppearancePreset {
			val snapshot = settingsRepository.settings.first().toAppearancePresetSnapshot()

			appearancePresetRepository.replace(id, name, snapshot)
		}
	}

	fun renameAppearancePreset(id: String, name: String) {
		mutateAppearancePreset {
			appearancePresetRepository.rename(id, name)
		}
	}

	fun deleteAppearancePreset(id: String) {
		mutateAppearancePreset {
			appearancePresetRepository.delete(id)
		}
	}

	fun clearAppearancePresetMutationState() {
		_appearancePresetMutationState.value = AppearancePresetMutationState.Idle
	}

	fun applyAppearancePreset(preset: SavedAppearancePreset) {
		val current = appearancePresets.value
		if (current !is AppearancePresetStorageState.Ready || current.presets.none { it.id == preset.id }) {
			return
		}

		viewModelScope.launch {
			settingsRepository.applyAppearancePreset(preset.snapshot)
		}
	}

	fun refreshWeather() {
		viewModelScope.launch { refreshWeatherStatus(force = true) }
	}

	fun onCityQueryChange(query: String) {
		typingQueries.tryEmit(query)
	}

	fun searchCityImmediately(query: String) {
		immediateQueries.tryEmit(query)
	}

	fun searchCity(query: String) {
		searchCityImmediately(query)
	}

	fun clearCityQuery() {
		onCityQueryChange("")
	}

	/** Persists the chosen place as the manual location; the live wallpaper picks it up on its next refresh. */
	fun selectPlace(place: GeoPlace) {
		viewModelScope.launch {
			settingsRepository.update(
				listOf(
					SettingsMutation.ManualLocation(
						latitude = place.latitude,
						longitude = place.longitude,
						label = place.label
					)
				)
			)
			refreshWeatherStatus(force = true)

			_citySearch.value = CitySearchState.Idle
		}
	}

	fun clearManualLocation() {
		viewModelScope.launch {
			settingsRepository.update(listOf(SettingsMutation.ClearManualLocation))
			refreshWeatherStatus(force = true)

			_citySearch.value = CitySearchState.Idle
		}
	}

	/**
	 * Copies the photo the user just picked into [bucket], reporting through [photoImportFailed] whether it landed.
	 * The copy runs on [applicationScope] rather than on [viewModelScope] because it is a write the user has already asked for: a full-resolution decode takes seconds, and pressing Back in the middle of one used to cancel it silently, leaving the row still saying no photo was selected.
	 * Only the flag it reports through belongs to the screen, and writing to it after the screen is gone is a write nobody is collecting rather than a leak: the view model outlives its own scope until the copy returns, holding nothing but the application and singletons.
	 * A failed import writes nothing, which is why [photoBuckets] stays the only thing the rows read: the row cannot end up advertising a photo that was never stored.
	 */
	fun importPhoto(bucket: PhotoBucket, source: Uri) {
		_photoImportFailed.value = false

		applicationScope.launch {
			_photoImportFailed.value = photoBackgroundRepository.import(bucket, source).isFailure
		}
	}

	/** Lowers [photoImportFailed], so that a failure the user has already been shown does not come back with the section the next time it is opened. */
	fun dismissImportFailure() {
		_photoImportFailed.value = false
	}

	/**
	 * Drops the photo stored for [bucket], after which that phase falls back to its parent bucket or to the painted sky.
	 * Application-scoped for the same reason the import is: a clear queued behind an import waits on the repository's mutex, and a clear dropped on the way out of the screen would leave a photo the user told us to remove.
	 */
	fun clearPhoto(bucket: PhotoBucket) {
		applicationScope.launch {
			photoBackgroundRepository.clear(bucket)
		}
	}

	private fun mutateAppearancePreset(mutation: suspend () -> Result<Unit>) {
		_appearancePresetMutationState.value = AppearancePresetMutationState.InProgress

		viewModelScope.launch {
			val succeeded = try {
				mutation().isSuccess
			} catch (cancellationException: CancellationException) {
				throw cancellationException
			} catch (_: Exception) {
				false
			}

			_appearancePresetMutationState.value = if (succeeded) {
				AppearancePresetMutationState.Succeeded
			} else {
				AppearancePresetMutationState.Failed
			}
		}
	}

	private suspend fun refreshWeatherStatus(force: Boolean) {
		weatherRefreshMutex.withLock {
			_weatherRefreshInProgress.value = true

			try {
				sceneProvider.refresh(nowEpochSeconds(), force = force, resolveLocationName = true)
			} finally {
				_weatherRefreshInProgress.value = false
			}
		}
	}

	private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L

	companion object {
		const val CITY_SEARCH_DEBOUNCE_MILLIS = 400L
	}
}
