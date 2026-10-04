package xyz.attacktive.weatherd.data.api.dto

import android.annotation.SuppressLint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.attacktive.weatherd.domain.model.CloudCover
import xyz.attacktive.weatherd.domain.model.CloudLayers
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.model.WeatherSource
import xyz.attacktive.weatherd.domain.weather.conditionForWmoCode

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
	@SerialName("cloud_cover") val cloudCover: Int,
	@SerialName("cloud_cover_low") val cloudCoverLow: Int? = null,
	@SerialName("cloud_cover_mid") val cloudCoverMid: Int? = null,
	@SerialName("cloud_cover_high") val cloudCoverHigh: Int? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class DailyDto(val sunrise: List<Long> = emptyList(), val sunset: List<Long> = emptyList())

private fun CurrentWeatherDto.cloudLayers(): CloudLayers? {
	val low = cloudCoverLow ?: return null
	val mid = cloudCoverMid ?: return null
	val high = cloudCoverHigh ?: return null

	return CloudLayers(
		lowPercent = low.coerceIn(0, 100),
		midPercent = mid.coerceIn(0, 100),
		highPercent = high.coerceIn(0, 100)
	)
}

/** Distils the Open-Meteo response into the domain snapshot; sun times take the first (today's) entry. */
fun ForecastResponseDto.toSnapshot(source: WeatherSource = WeatherSource(WeatherProviderType.OPEN_METEO)): WeatherSnapshot {
	val observation = WeatherObservation(
		condition = conditionForWmoCode(current.weatherCode),
		isDay = current.isDay == 1,
		temperatureCelsius = current.temperature,
		precipitationMillimeters = current.precipitation,
		windSpeedKilometersPerHour = current.windSpeed,
		cloudCover = CloudCover(
			totalPercent = current.cloudCover.coerceIn(0, 100),
			layers = current.cloudLayers()
		)
	)

	return WeatherSnapshot(
		observation = observation,
		observedAtEpochSeconds = current.time,
		sunriseEpochSeconds = daily?.sunrise?.firstOrNull(),
		sunsetEpochSeconds = daily?.sunset?.firstOrNull(),
		source = source
	)
}
