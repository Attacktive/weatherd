package xyz.attacktive.weatherd.ui.settings

import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.di.ApplicationScope
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.GeoPlace
import xyz.attacktive.weatherd.domain.model.PhotoBucket
import xyz.attacktive.weatherd.domain.repository.GeocodingRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository

/** UI state for the city-name search shown under the manual-location option. */
sealed interface CitySearchState {
	data object Idle: CitySearchState
	data object Loading: CitySearchState
	data object Empty: CitySearchState
	data class Results(val places: List<GeoPlace>): CitySearchState
	data class Error(val message: String): CitySearchState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
	application: Application,
	private val settingsRepository: SettingsRepository,
	private val geocodingRepository: GeocodingRepository,
	private val photoBackgroundRepository: PhotoBackgroundRepository,
	@ApplicationScope private val applicationScope: CoroutineScope
): AndroidViewModel(application) {
	val settings = settingsRepository.settings
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

	/**
	 * The buckets that currently hold one of the user's photos, already a hot [kotlinx.coroutines.flow.StateFlow] on the repository and so exposed as it stands.
	 * The repository is the only truth about which files exist, which is what keeps a failed import from leaving a row claiming a photo it never wrote.
	 */
	val photoBuckets = photoBackgroundRepository.available

	private val _photoImportFailed = MutableStateFlow(false)
	val photoImportFailed = _photoImportFailed.asStateFlow()

	private val _citySearch = MutableStateFlow<CitySearchState>(CitySearchState.Idle)
	val citySearch = _citySearch.asStateFlow()

	fun save(settings: AppSettings) {
		viewModelScope.launch {
			settingsRepository.save(settings)
		}
	}

	fun searchCity(query: String) {
		val trimmed = query.trim()
		if (trimmed.isEmpty()) {
			return
		}

		viewModelScope.launch {
			_citySearch.value = CitySearchState.Loading

			geocodingRepository.search(trimmed)
				.onSuccess { places ->
					_citySearch.value = if (places.isEmpty()) {
						CitySearchState.Empty
					} else {
						CitySearchState.Results(places)
					}
				}
				.onFailure { _citySearch.value = CitySearchState.Error(it.message ?: getApplication<Application>().getString(R.string.city_search_failed)) }
		}
	}

	/** Persists the chosen place as the manual location; the live wallpaper picks it up on its next refresh. */
	fun selectPlace(place: GeoPlace) {
		viewModelScope.launch {
			val current = settingsRepository.settings.first()
			settingsRepository.save(
				current.copy(
					useDeviceLocation = false,
					manualLatitude = place.latitude,
					manualLongitude = place.longitude,
					manualLocationLabel = place.label
				)
			)

			_citySearch.value = CitySearchState.Idle
		}
	}

	fun clearManualLocation() {
		viewModelScope.launch {
			val current = settingsRepository.settings.first()
			settingsRepository.save(current.copy(manualLatitude = null, manualLongitude = null, manualLocationLabel = null))

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
}
