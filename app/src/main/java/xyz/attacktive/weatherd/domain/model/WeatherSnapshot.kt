package xyz.attacktive.weatherd.domain.model

data class WeatherSource(
	val provider: WeatherProviderType,
	val model: String? = null,
	val fallbackReason: WeatherFallbackReason? = null
)

enum class WeatherFallbackReason {
	OUTSIDE_COVERAGE,
	PRIMARY_FAILED
}

/** A current-conditions observation plus its source and the sun times used to tint the scene for time of day. */
data class WeatherSnapshot(
	val observation: WeatherObservation,
	val observedAtEpochSeconds: Long,
	val sunriseEpochSeconds: Long?,
	val sunsetEpochSeconds: Long?,
	val source: WeatherSource = WeatherSource(WeatherProviderType.OPEN_METEO)
)
