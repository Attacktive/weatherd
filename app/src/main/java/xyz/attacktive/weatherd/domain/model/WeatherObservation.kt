package xyz.attacktive.weatherd.domain.model

/** Total cloud cover plus the optional vertical breakdown supplied by the weather provider. */
data class CloudCover(
	val totalPercent: Int,
	val layers: CloudLayers? = null
)

/** Cloud cover split by altitude; null at the [CloudCover] level means the provider supplied only total cover. */
data class CloudLayers(
	val lowPercent: Int,
	val midPercent: Int,
	val highPercent: Int
)

/** Current-conditions snapshot for one location, normalized so render behavior is independent of the upstream weather provider. */
data class WeatherObservation(
	val condition: WeatherCondition,
	val isDay: Boolean,
	val temperatureCelsius: Double,
	val precipitationMillimeters: Double,
	val windSpeedKilometersPerHour: Double,
	val cloudCover: CloudCover,
	val relativeHumidityPercent: Double? = null
) {
	val cloudCoverPercent: Int
		get() = cloudCover.totalPercent
}
