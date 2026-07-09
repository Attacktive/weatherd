package xyz.attacktive.weatherd.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import android.annotation.SuppressLint
import xyz.attacktive.weatherd.domain.model.GeoPlace

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class GeocodingResponseDto(val results: List<GeocodingResultDto> = emptyList())

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class GeocodingResultDto(
	val name: String,
	val latitude: Double,
	val longitude: Double,
	val country: String? = null,
	val admin1: String? = null,
	@SerialName("country_code") val countryCode: String? = null
)

/** Maps a geocoding hit to the domain place; `admin1` is the region/state (e.g. "Illinois"). */
fun GeocodingResultDto.toGeoPlace() = GeoPlace(name = name, latitude = latitude, longitude = longitude, region = admin1, country = country)
