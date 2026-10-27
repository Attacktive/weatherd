package xyz.attacktive.weatherd.domain.repository

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.render.WeatherNotice
import xyz.attacktive.weatherd.domain.render.WeatherSceneTarget
import xyz.attacktive.weatherd.util.AppLogger

@Serializable
data class CachedWeather(
	val snapshot: WeatherSnapshot,
	val target: WeatherSceneTarget,
	val refreshedAtEpochSeconds: Long,
	val locationLabel: String?,
	val version: Int = 1
) {
	@Transient
	val notice = WeatherNotice(refreshedAtEpochSeconds, locationLabel)
}

/** One last-success record, separate from preferences and never populated by simulation or failed requests. */
@Singleton
class WeatherCacheRepository @Inject constructor(
	private val dataStore: DataStore<Preferences>,
	private val json: Json,
	private val logger: AppLogger
) {
	suspend fun load() = try {
		dataStore.data.first()[KEY]?.let { encoded ->
			json.decodeFromString<CachedWeather>(encoded).takeIf {
				it.version == 1 && it.refreshedAtEpochSeconds > 0 &&
					it.target.latitude?.let { latitude -> latitude.isFinite() && latitude in -90.0..90.0 } == true &&
					it.target.longitude?.let { longitude -> longitude.isFinite() && longitude in -180.0..180.0 } == true
			}
		}
	} catch (exception: IOException) {
		logger.error(TAG, "Unable to read last successful weather", exception)
		null
	} catch (exception: SerializationException) {
		logger.error(TAG, "Ignoring unreadable weather cache", exception)
		null
	} catch (exception: IllegalArgumentException) {
		logger.error(TAG, "Ignoring invalid weather cache", exception)
		null
	}

	suspend fun save(weather: CachedWeather) {
		try {
			val encoded = json.encodeToString(weather)
			dataStore.edit { it[KEY] = encoded }
		} catch (exception: IOException) {
			logger.error(TAG, "Unable to persist last successful weather", exception)
		} catch (exception: SerializationException) {
			logger.error(TAG, "Unable to encode last successful weather", exception)
		} catch (exception: IllegalArgumentException) {
			logger.error(TAG, "Unable to encode invalid weather values", exception)
		}
	}

	companion object {
		private const val TAG = "WeatherCacheRepository"
		private val KEY = stringPreferencesKey("last_successful_weather")
	}
}
