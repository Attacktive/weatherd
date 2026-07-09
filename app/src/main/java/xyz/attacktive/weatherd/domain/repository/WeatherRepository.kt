package xyz.attacktive.weatherd.domain.repository

import javax.inject.Inject
import javax.inject.Singleton
import xyz.attacktive.weatherd.data.api.OpenMeteoApiService
import xyz.attacktive.weatherd.data.api.dto.toSnapshot
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.util.AppLogger

@Singleton
class WeatherRepository @Inject constructor(private val openMeteoApiService: OpenMeteoApiService, private val logger: AppLogger) {
	suspend fun current(latitude: Double, longitude: Double): Result<WeatherSnapshot> = runCatching { openMeteoApiService.forecast(latitude, longitude).toSnapshot() }
		.onFailure { logger.error(TAG, "forecast for $latitude,$longitude failed", it) }

	companion object {
		private const val TAG = "WeatherRepository"
	}
}
