package xyz.attacktive.weatherd.domain.repository

import javax.inject.Inject
import javax.inject.Singleton
import xyz.attacktive.weatherd.data.api.GeocodingApiService
import xyz.attacktive.weatherd.data.api.dto.toGeoPlace
import xyz.attacktive.weatherd.domain.model.GeoPlace
import xyz.attacktive.weatherd.util.AppLogger

@Singleton
class GeocodingRepository @Inject constructor(private val geocodingApiService: GeocodingApiService, private val logger: AppLogger) {
	/** Resolves a free-text city query to candidate places (best matches first); an empty list when nothing matches. */
	suspend fun search(query: String): Result<List<GeoPlace>> = runCatching { geocodingApiService.search(query).results.map { it.toGeoPlace() } }
		.onFailure { logger.error(TAG, "geocoding for \"$query\" failed", it) }

	companion object {
		private const val TAG = "GeocodingRepository"
	}
}
