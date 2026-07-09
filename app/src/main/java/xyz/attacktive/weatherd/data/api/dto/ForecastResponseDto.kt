package xyz.attacktive.weatherd.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import android.annotation.SuppressLint
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ForecastResponseDto(val latitude: Double, val longitude: Double, val current: CurrentWeatherDto, val daily: DailyDto? = null)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class CurrentWeatherDto(
	val time: Long,
	@SerialName("weather_code") val weatherCode: Int,
	@SerialName("is_day") val isDay: Int,
	@SerialName("temperature_2m") val temperature: Double,
	val precipitation: Double,
	@SerialName("wind_speed_10m") val windSpeed: Double,
	@SerialName("cloud_cover") val cloudCover: Int
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class DailyDto(val sunrise: List<Long> = emptyList(), val sunset: List<Long> = emptyList())

/** Distils the Open-Meteo response into the domain snapshot; sun times take the first (today's) entry. */
fun ForecastResponseDto.toSnapshot(): WeatherSnapshot {
	val observation = WeatherObservation(
		weatherCode = current.weatherCode,
		isDay = current.isDay == 1,
		temperatureCelsius = current.temperature,
		precipitationMillimeters = current.precipitation,
		windSpeedKilometersPerHour = current.windSpeed,
		cloudCoverPercent = current.cloudCover
	)

	return WeatherSnapshot(
		observation = observation,
		observedAtEpochSeconds = current.time,
		sunriseEpochSeconds = daily?.sunrise?.firstOrNull(),
		sunsetEpochSeconds = daily?.sunset?.firstOrNull()
	)
}
