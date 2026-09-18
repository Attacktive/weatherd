package xyz.attacktive.weatherd.domain.repository

import javax.inject.Inject
import javax.inject.Singleton
import xyz.attacktive.weatherd.di.DefaultWeatherProvider
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.provider.WeatherProvider
import xyz.attacktive.weatherd.util.AppLogger

@Singleton
class WeatherRepository @Inject constructor(
	@DefaultWeatherProvider private val weatherProvider: WeatherProvider,
	private val logger: AppLogger
) {
	suspend fun current(latitude: Double, longitude: Double): Result<WeatherSnapshot> = runCatching {
		weatherProvider.current(latitude, longitude)
	}.onFailure { logger.error(TAG, "forecast for $latitude,$longitude failed", it) }

	companion object {
		private const val TAG = "WeatherRepository"
	}
}
