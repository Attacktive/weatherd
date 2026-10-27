package xyz.attacktive.weatherd.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class WeatherSource(
	val provider: WeatherProviderType,
	val model: String? = null,
	val fallbackReason: WeatherFallbackReason? = null
)

enum class WeatherFallbackReason {
	OUTSIDE_COVERAGE,
	PRIMARY_FAILED
}

/** Whether a precipitation total accumulates before or after the forecast-valid timestamp. */
enum class PrecipitationWindowDirection {
	PRECEDING,
	FOLLOWING
}

/** The source-specific accumulation interval, not an instantaneous precipitation rate. */
@Serializable
data class PrecipitationWindow(val durationSeconds: Int, val direction: PrecipitationWindowDirection)

/** A current-conditions observation plus its source and the sun times used to tint the scene for time of day. */
@Serializable
data class WeatherSnapshot(
	val observation: WeatherObservation,
	val observedAtEpochSeconds: Long,
	val sunriseEpochSeconds: Long?,
	val sunsetEpochSeconds: Long?,
	val source: WeatherSource = WeatherSource(WeatherProviderType.OPEN_METEO),
	val locationLongitude: Double? = null,
	val isDayAuthoritativeWithoutCompleteSolarTimes: Boolean = false,
	val precipitationWindow: PrecipitationWindow? = null
)
