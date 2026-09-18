package xyz.attacktive.weatherd.data.api.dto

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToLong
import android.annotation.SuppressLint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.WeatherCondition
import xyz.attacktive.weatherd.domain.model.WeatherLabel
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_HEAVY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STEADY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MetNoForecastResponseDto(val properties: MetNoPropertiesDto = MetNoPropertiesDto())

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MetNoPropertiesDto(val timeseries: List<MetNoTimeSeriesDto> = emptyList())

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MetNoTimeSeriesDto(val time: String? = null, val data: MetNoDataDto? = null)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MetNoDataDto(
	val instant: MetNoInstantDto? = null,
	@SerialName("next_1_hours") val nextOneHour: MetNoPeriodDto? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MetNoInstantDto(val details: MetNoInstantDetailsDto? = null)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MetNoInstantDetailsDto(
	@SerialName("air_temperature") val airTemperature: Double? = null,
	@SerialName("cloud_area_fraction") val cloudAreaFraction: Double? = null,
	@SerialName("wind_speed") val windSpeed: Double? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MetNoPeriodDto(val summary: MetNoSummaryDto? = null, val details: MetNoPeriodDetailsDto? = null)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MetNoSummaryDto(@SerialName("symbol_code") val symbolCode: String? = null)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MetNoPeriodDetailsDto(@SerialName("precipitation_amount") val precipitationAmount: Double? = null)

fun MetNoForecastResponseDto.toSnapshot(sun: MetNoSunriseResponseDto): WeatherSnapshot {
	val current = properties.timeseries.firstOrNull() ?: error("MET Norway response contains no timeseries")
	val time = current.time ?: error("MET Norway current timeseries has no time")
	val data = current.data ?: error("MET Norway current timeseries has no data")
	val details = data.instant?.details ?: error("MET Norway current timeseries has no instant details")
	val nextOneHour = data.nextOneHour ?: error("MET Norway current timeseries has no next-hour forecast")
	val symbolCode = nextOneHour.summary?.symbolCode ?: error("MET Norway current timeseries has no next-hour symbol")
	val periodDetails = nextOneHour.details ?: error("MET Norway current timeseries has no next-hour details")
	val precipitation = periodDetails.precipitationAmount ?: 0.0
	val airTemperature = details.airTemperature ?: error("MET Norway current timeseries has no air temperature")
	val cloudAreaFraction = details.cloudAreaFraction ?: error("MET Norway current timeseries has no cloud cover")
	val windSpeed = details.windSpeed ?: error("MET Norway current timeseries has no wind speed")
	val observedAtEpochSeconds = Instant.parse(time).epochSecond

	return WeatherSnapshot(
		observation = WeatherObservation(
			condition = symbolCode.toMetNoCondition(),
			isDay = sun.isDayAt(observedAtEpochSeconds),
			temperatureCelsius = airTemperature,
			precipitationMillimeters = precipitation,
			windSpeedKilometersPerHour = windSpeed * METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR,
			cloudCoverPercent = cloudAreaFraction.toInt().coerceIn(0, 100)
		),
		observedAtEpochSeconds = observedAtEpochSeconds,
		sunriseEpochSeconds = sun.sunriseEpochSeconds(),
		sunsetEpochSeconds = sun.sunsetEpochSeconds()
	)
}

internal fun MetNoForecastResponseDto.sunriseDate(longitude: Double): String {
	val time = properties.timeseries.firstOrNull()?.time ?: error("MET Norway response contains no current time")
	val solarOffsetSeconds = (longitude / FULL_CIRCLE_DEGREES * SECONDS_PER_DAY).roundToLong()

	return Instant.parse(time).plusSeconds(solarOffsetSeconds).atZone(ZoneOffset.UTC).toLocalDate().toString()
}

internal fun String.toMetNoCondition(): WeatherCondition {
	val code = removeSuffix("_day").removeSuffix("_night").removeSuffix("_polartwilight")

	return MET_NO_CONDITIONS[code] ?: error("Unsupported MET Norway symbol code: $this")
}

internal val supportedMetNoSymbolCodes: Set<String>
	get() = MET_NO_CONDITIONS.keys

private val MET_NO_CONDITIONS = mapOf(
	"clearsky" to WeatherCondition(label = WeatherLabel.CLEAR_SKY),
	"fair" to WeatherCondition(label = WeatherLabel.MAINLY_CLEAR),
	"partlycloudy" to WeatherCondition(label = WeatherLabel.PARTLY_CLOUDY),
	"cloudy" to WeatherCondition(label = WeatherLabel.OVERCAST),
	"fog" to WeatherCondition(label = WeatherLabel.FOG, fog = true),
	"lightrainshowers" to precipitation(WeatherLabel.LIGHT_SHOWERS, PrecipitationKind.RAIN, SEVERITY_STEADY),
	"rainshowers" to precipitation(WeatherLabel.SHOWERS, PrecipitationKind.RAIN, SEVERITY_STEADY),
	"heavyrainshowers" to precipitation(WeatherLabel.VIOLENT_SHOWERS, PrecipitationKind.RAIN, SEVERITY_HEAVY),
	"lightsleetshowers" to precipitation(null, PrecipitationKind.SLEET, SEVERITY_DRIZZLE),
	"sleetshowers" to precipitation(null, PrecipitationKind.SLEET, SEVERITY_STEADY),
	"heavysleetshowers" to precipitation(null, PrecipitationKind.SLEET, SEVERITY_HEAVY),
	"lightsnowshowers" to precipitation(WeatherLabel.SNOW_SHOWERS, PrecipitationKind.SNOW, SEVERITY_STEADY),
	"snowshowers" to precipitation(WeatherLabel.SNOW_SHOWERS, PrecipitationKind.SNOW, SEVERITY_STEADY),
	"heavysnowshowers" to precipitation(WeatherLabel.HEAVY_SNOW_SHOWERS, PrecipitationKind.SNOW, SEVERITY_HEAVY),
	"lightrain" to precipitation(WeatherLabel.LIGHT_RAIN, PrecipitationKind.RAIN, SEVERITY_STEADY),
	"rain" to precipitation(WeatherLabel.RAIN, PrecipitationKind.RAIN, SEVERITY_STEADY),
	"heavyrain" to precipitation(WeatherLabel.HEAVY_RAIN, PrecipitationKind.RAIN, SEVERITY_HEAVY),
	"lightsleet" to precipitation(null, PrecipitationKind.SLEET, SEVERITY_DRIZZLE),
	"sleet" to precipitation(null, PrecipitationKind.SLEET, SEVERITY_STEADY),
	"heavysleet" to precipitation(null, PrecipitationKind.SLEET, SEVERITY_HEAVY),
	"lightsnow" to precipitation(WeatherLabel.LIGHT_SNOW, PrecipitationKind.SNOW, SEVERITY_STEADY),
	"snow" to precipitation(WeatherLabel.SNOW, PrecipitationKind.SNOW, SEVERITY_STEADY),
	"heavysnow" to precipitation(WeatherLabel.HEAVY_SNOW, PrecipitationKind.SNOW, SEVERITY_HEAVY),
	"lightrainshowersandthunder" to thunder(PrecipitationKind.RAIN, SEVERITY_STORM),
	"rainshowersandthunder" to thunder(PrecipitationKind.RAIN, SEVERITY_STORM),
	"heavyrainshowersandthunder" to thunder(PrecipitationKind.RAIN, SEVERITY_HEAVY),
	"lightssleetshowersandthunder" to thunder(PrecipitationKind.SLEET, SEVERITY_STORM),
	"sleetshowersandthunder" to thunder(PrecipitationKind.SLEET, SEVERITY_STORM),
	"heavysleetshowersandthunder" to thunder(PrecipitationKind.SLEET, SEVERITY_HEAVY),
	"lightssnowshowersandthunder" to thunder(PrecipitationKind.SNOW, SEVERITY_STORM),
	"snowshowersandthunder" to thunder(PrecipitationKind.SNOW, SEVERITY_STORM),
	"heavysnowshowersandthunder" to thunder(PrecipitationKind.SNOW, SEVERITY_HEAVY),
	"lightrainandthunder" to thunder(PrecipitationKind.RAIN, SEVERITY_STORM),
	"rainandthunder" to thunder(PrecipitationKind.RAIN, SEVERITY_STORM),
	"heavyrainandthunder" to thunder(PrecipitationKind.RAIN, SEVERITY_HEAVY),
	"lightsleetandthunder" to thunder(PrecipitationKind.SLEET, SEVERITY_STORM),
	"sleetandthunder" to thunder(PrecipitationKind.SLEET, SEVERITY_STORM),
	"heavysleetandthunder" to thunder(PrecipitationKind.SLEET, SEVERITY_HEAVY),
	"lightsnowandthunder" to thunder(PrecipitationKind.SNOW, SEVERITY_STORM),
	"snowandthunder" to thunder(PrecipitationKind.SNOW, SEVERITY_STORM),
	"heavysnowandthunder" to thunder(PrecipitationKind.SNOW, SEVERITY_HEAVY)
)

private fun precipitation(label: WeatherLabel?, kind: PrecipitationKind, severity: Float) = WeatherCondition(
	label = label,
	precipitationKind = kind,
	severity = severity
)

private fun thunder(kind: PrecipitationKind, severity: Float) = WeatherCondition(
	label = WeatherLabel.THUNDERSTORM,
	precipitationKind = kind,
	severity = severity,
	thunder = true
)

private const val METERS_PER_SECOND_TO_KILOMETERS_PER_HOUR = 3.6
private const val FULL_CIRCLE_DEGREES = 360.0
private const val SECONDS_PER_DAY = 86_400.0
