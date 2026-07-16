package xyz.attacktive.weatherd.data.api

import retrofit2.http.GET
import retrofit2.http.Query
import xyz.attacktive.weatherd.data.api.dto.GeocodingResponseDto

/**
 * Open-Meteo's geocoding endpoint, which lives on a different host than the forecast API (`geocoding-api.open-meteo.com`), so it is provided with its own Retrofit instance.
 */
interface GeocodingApiService {
	@GET("v1/search")
	suspend fun search(
		@Query("name") name: String,
		@Query("count") count: Int = 10,
		@Query("language") language: String = "en",
		@Query("format") format: String = "json"
	): GeocodingResponseDto
}
