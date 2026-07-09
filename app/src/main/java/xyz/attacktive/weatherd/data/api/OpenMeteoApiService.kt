package xyz.attacktive.weatherd.data.api

import retrofit2.http.GET
import retrofit2.http.Query
import xyz.attacktive.weatherd.data.api.dto.ForecastResponseDto

interface OpenMeteoApiService {
	@GET("v1/forecast")
	suspend fun forecast(
		@Query("latitude") latitude: Double,
		@Query("longitude") longitude: Double,
		@Query("current") current: String = CURRENT_FIELDS,
		@Query("daily") daily: String = DAILY_FIELDS,
		@Query("timeformat") timeFormat: String = "unixtime",
		@Query("timezone") timezone: String = "auto",
		@Query("wind_speed_unit") windSpeedUnit: String = "kmh"
	): ForecastResponseDto

	companion object {
		const val CURRENT_FIELDS = "weather_code,is_day,temperature_2m,precipitation,wind_speed_10m,cloud_cover"
		const val DAILY_FIELDS = "sunrise,sunset"
	}
}
