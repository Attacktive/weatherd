package xyz.attacktive.weatherd.data.api.dto

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot

@Serializable
data class MetNoForecastResponseDto(val properties: MetNoPropertiesDto)

@Serializable
data class MetNoPropertiesDto(val timeseries: List<MetNoTimeSeriesDto>)

@Serializable
data class MetNoTimeSeriesDto(val time: String, val data: MetNoDataDto)

@Serializable
data class MetNoDataDto(
	val instant: MetNoInstantDto,
	@SerialName("next_1_hours") val nextOneHour: MetNoPeriodDto? = null
)

@Serializable
data class MetNoInstantDto(val details: MetNoInstantDetailsDto)

@Serializable
data class MetNoInstantDetailsDto(
	@SerialName("air_temperature") val airTemperature: Double,
	@SerialName("cloud_area_fraction") val cloudAreaFraction: Double,
	@SerialName("wind_speed") val windSpeed: Double
)

@Serializable
data class MetNoPeriodDto(val summary: MetNoSummaryDto, val details: MetNoPeriodDetailsDto)

@Serializable
data class MetNoSummaryDto(@SerialName("symbol_code") val symbolCode: String)

@Serializable
data class MetNoPeriodDetailsDto(@SerialName("precipitation_amount") val precipitationAmount: Double = 0.0)

fun MetNoForecastResponseDto.toSnapshot(): WeatherSnapshot {
	val current = properties.timeseries.firstOrNull()
		?: error("MET Norway response contains no timeseries")
	val symbolCode = current.data.nextOneHour?.summary?.symbolCode ?: "cloudy"
	val details = current.data.instant.details

	return WeatherSnapshot(
		observation = WeatherObservation(
			weatherCode = symbolCode.toWmoCode(),
			isDay = !symbolCode.endsWith("_night"),
			temperatureCelsius = details.airTemperature,
			precipitationMillimeters = current.data.nextOneHour?.details?.precipitationAmount ?: 0.0,
			windSpeedKilometersPerHour = details.windSpeed * METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR,
			cloudCoverPercent = details.cloudAreaFraction.toInt().coerceIn(0, 100)
		),
		observedAtEpochSeconds = Instant.parse(current.time).epochSecond,
		sunriseEpochSeconds = null,
		sunsetEpochSeconds = null
	)
}

private fun String.toWmoCode(): Int {
	val code = removeSuffix("_day").removeSuffix("_night").removeSuffix("_polartwilight")

	return when (code) {
		"clearsky" -> 0
		"fair" -> 1
		"partlycloudy" -> 2
		"cloudy" -> 3
		"fog" -> 45
		"lightrainshowers" -> 80
		"rainshowers" -> 81
		"heavyrainshowers" -> 82
		"lightsnowshowers", "snowshowers" -> 85
		"heavysnowshowers" -> 86
		"lightsleetshowers", "sleetshowers", "heavysleetshowers" -> 66
		"lightrain" -> 61
		"rain" -> 63
		"heavyrain" -> 65
		"lightsleet", "sleet", "heavysleet" -> 67
		"lightsnow" -> 71
		"snow" -> 73
		"heavysnow" -> 75
		"rainandthunder", "heavyrainandthunder",
		"rainshowersandthunder", "heavyrainshowersandthunder",
		"sleetandthunder", "snowandthunder",
		"sleetshowersandthunder", "snowshowersandthunder",
		"lightssleetshowersandthunder", "lightssnowshowersandthunder" -> 95
		else -> 3
	}
}

private const val METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR = 3.6
