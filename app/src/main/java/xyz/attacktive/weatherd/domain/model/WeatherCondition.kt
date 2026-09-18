package xyz.attacktive.weatherd.domain.model

data class WeatherCondition(
	val label: WeatherLabel? = null,
	val precipitationKind: PrecipitationKind? = null,
	val severity: Float = 0f,
	val fog: Boolean = false,
	val thunder: Boolean = false
)

enum class WeatherLabel {
	CLEAR_SKY,
	MAINLY_CLEAR,
	PARTLY_CLOUDY,
	OVERCAST,
	FOG,
	ICY_FOG,
	LIGHT_DRIZZLE,
	DRIZZLE,
	DENSE_DRIZZLE,
	LIGHT_FREEZING_DRIZZLE,
	FREEZING_DRIZZLE,
	LIGHT_RAIN,
	RAIN,
	HEAVY_RAIN,
	LIGHT_FREEZING_RAIN,
	FREEZING_RAIN,
	LIGHT_SNOW,
	SNOW,
	HEAVY_SNOW,
	SNOW_GRAINS,
	LIGHT_SHOWERS,
	SHOWERS,
	VIOLENT_SHOWERS,
	SNOW_SHOWERS,
	HEAVY_SNOW_SHOWERS,
	THUNDERSTORM,
	THUNDERSTORM_WITH_HAIL
}
