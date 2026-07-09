package xyz.attacktive.weatherd.ui.settings

import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.GeoPlace
import xyz.attacktive.weatherd.domain.repository.GeocodingRepository
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
	private val settingsRepository: SettingsRepository,
	private val geocodingRepository: GeocodingRepository
): ViewModel() {
	val settings = settingsRepository.settings
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

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
				.onFailure { _citySearch.value = CitySearchState.Error(it.message ?: "Search failed") }
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
}
