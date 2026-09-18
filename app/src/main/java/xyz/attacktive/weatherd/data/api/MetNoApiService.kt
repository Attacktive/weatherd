package xyz.attacktive.weatherd.data.api

import retrofit2.http.GET
import retrofit2.http.Query
import xyz.attacktive.weatherd.data.api.dto.MetNoForecastResponseDto
import xyz.attacktive.weatherd.data.api.dto.MetNoSunriseResponseDto

interface MetNoApiService {
	@GET("weatherapi/locationforecast/2.0/compact")
	suspend fun forecast(
		@Query("lat") latitude: String,
		@Query("lon") longitude: String
	): MetNoForecastResponseDto

	@GET("weatherapi/sunrise/3.0/sun")
	suspend fun sunrise(
		@Query("lat") latitude: String,
		@Query("lon") longitude: String,
		@Query("date") date: String
	): MetNoSunriseResponseDto
}
