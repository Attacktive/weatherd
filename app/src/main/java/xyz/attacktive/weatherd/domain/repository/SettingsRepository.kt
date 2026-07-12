package xyz.attacktive.weatherd.domain.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene

/** Persists [AppSettings] to a DataStore; absent keys fall back to the [AppSettings] defaults on read. */
@Singleton
class SettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {
	private object Keys {
		val UPDATE_INTERVAL_MINUTES = intPreferencesKey("update_interval_minutes")
		val USE_DEVICE_LOCATION = booleanPreferencesKey("use_device_location")
		val MANUAL_LATITUDE = doublePreferencesKey("manual_latitude")
		val MANUAL_LONGITUDE = doublePreferencesKey("manual_longitude")
		val MANUAL_LOCATION_LABEL = stringPreferencesKey("manual_location_label")
		val BACKDROP_SCENE = stringPreferencesKey("backdrop_scene")
	}

	val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
		AppSettings(
			updateIntervalMinutes = preferences[Keys.UPDATE_INTERVAL_MINUTES] ?: DEFAULTS.updateIntervalMinutes,
			useDeviceLocation = preferences[Keys.USE_DEVICE_LOCATION] ?: DEFAULTS.useDeviceLocation,
			manualLatitude = preferences[Keys.MANUAL_LATITUDE],
			manualLongitude = preferences[Keys.MANUAL_LONGITUDE],
			manualLocationLabel = preferences[Keys.MANUAL_LOCATION_LABEL],
			backdropScene = BackdropScene.fromName(preferences[Keys.BACKDROP_SCENE])
		)
	}

	suspend fun save(settings: AppSettings) {
		dataStore.edit { preferences ->
			preferences[Keys.UPDATE_INTERVAL_MINUTES] = settings.updateIntervalMinutes
			preferences[Keys.USE_DEVICE_LOCATION] = settings.useDeviceLocation
			preferences.putOrRemove(Keys.MANUAL_LATITUDE, settings.manualLatitude)
			preferences.putOrRemove(Keys.MANUAL_LONGITUDE, settings.manualLongitude)
			preferences.putOrRemove(Keys.MANUAL_LOCATION_LABEL, settings.manualLocationLabel)
			preferences[Keys.BACKDROP_SCENE] = settings.backdropScene.name
		}
	}

	companion object {
		private val DEFAULTS = AppSettings()
	}
}

/** Writes [value] under [key], or clears the key when [value] is null, so cleared settings revert to their default on read. */
private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
	if (value != null) {
		this[key] = value
	} else {
		remove(key)
	}
}
