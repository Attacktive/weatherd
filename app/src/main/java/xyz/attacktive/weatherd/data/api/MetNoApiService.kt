package xyz.attacktive.weatherd.data.api

import retrofit2.http.GET
import retrofit2.http.Query
import xyz.attacktive.weatherd.data.api.dto.MetNoForecastResponseDto

interface MetNoApiService {
	@GET("weatherapi/locationforecast/2.0/compact")
	suspend fun forecast(
		@Query("lat") latitude: Double,
		@Query("lon") longitude: Double
	): MetNoForecastResponseDto
}
