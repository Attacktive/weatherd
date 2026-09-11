package xyz.attacktive.weatherd.ui.settings

import javax.inject.Inject
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
	private val photoBackgroundRepository: PhotoBackgroundRepository
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
	 * Copies the photo the user just picked into [bucket], and raises [photoImportFailed] when it does not land.
	 * The import owns its own thread hop, so this only has to keep it off the composition; the pick itself is a revocable grant on someone else's file and can fail for reasons no amount of retrying here would fix.
	 * A failed import writes nothing, which is why [photoBuckets] stays the only thing the rows read: the row cannot end up advertising a photo that was never stored.
	 */
	fun importPhoto(bucket: PhotoBucket, source: Uri) {
		viewModelScope.launch {
			_photoImportFailed.value = false

			photoBackgroundRepository.import(bucket, source)
				.onFailure { _photoImportFailed.value = true }
		}
	}

	/** Drops the photo stored for [bucket], after which that phase falls back to its parent bucket or to the painted sky. */
	fun clearPhoto(bucket: PhotoBucket) {
		viewModelScope.launch {
			photoBackgroundRepository.clear(bucket)
		}
	}
}
