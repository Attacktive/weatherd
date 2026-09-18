package xyz.attacktive.weatherd.domain.model

/** Current-conditions snapshot for one location, normalized so render behavior is independent of the upstream weather provider. */
data class WeatherObservation(
	val condition: WeatherCondition,
	val isDay: Boolean,
	val temperatureCelsius: Double,
	val precipitationMillimeters: Double,
	val windSpeedKilometersPerHour: Double,
	val cloudCoverPercent: Int
)
