package xyz.attacktive.weatherd.domain.weather

import androidx.annotation.StringRes
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.WeatherCondition
import xyz.attacktive.weatherd.domain.model.WeatherLabel

/** Maps an Open-Meteo WMO code into provider-neutral scene features and an optional display label. */
fun conditionForWmoCode(weatherCode: Int): WeatherCondition {
	val features = when (weatherCode) {
		45, 48 -> WeatherCondition(fog = true)
		51, 53, 55 -> WeatherCondition(precipitationKind = PrecipitationKind.RAIN, severity = SEVERITY_DRIZZLE)
		56, 57, 66, 67 -> WeatherCondition(precipitationKind = PrecipitationKind.SLEET, severity = SEVERITY_STEADY)
		61, 63, 80, 81 -> WeatherCondition(precipitationKind = PrecipitationKind.RAIN, severity = SEVERITY_STEADY)
		65, 82 -> WeatherCondition(precipitationKind = PrecipitationKind.RAIN, severity = SEVERITY_HEAVY)
		71, 73, 77, 85 -> WeatherCondition(precipitationKind = PrecipitationKind.SNOW, severity = SEVERITY_STEADY)
		75, 86 -> WeatherCondition(precipitationKind = PrecipitationKind.SNOW, severity = SEVERITY_HEAVY)
		95, 96, 99 -> WeatherCondition(precipitationKind = PrecipitationKind.RAIN, severity = SEVERITY_STORM, thunder = true)
		else -> WeatherCondition()
	}

	return features.copy(label = WMO_LABELS[weatherCode])
}

@StringRes
fun weatherLabelFor(label: WeatherLabel?): Int? = WEATHER_LABEL_RESOURCES[label]

private val WMO_LABELS = buildMap<Int, WeatherLabel> {
	put(0, WeatherLabel.CLEAR_SKY)
	put(1, WeatherLabel.MAINLY_CLEAR)
	put(2, WeatherLabel.PARTLY_CLOUDY)
	put(3, WeatherLabel.OVERCAST)
	put(45, WeatherLabel.FOG)
	put(48, WeatherLabel.ICY_FOG)
	put(51, WeatherLabel.LIGHT_DRIZZLE)
	put(53, WeatherLabel.DRIZZLE)
	put(55, WeatherLabel.DENSE_DRIZZLE)
	put(56, WeatherLabel.LIGHT_FREEZING_DRIZZLE)
	put(57, WeatherLabel.FREEZING_DRIZZLE)
	put(61, WeatherLabel.LIGHT_RAIN)
	put(63, WeatherLabel.RAIN)
	put(65, WeatherLabel.HEAVY_RAIN)
	put(66, WeatherLabel.LIGHT_FREEZING_RAIN)
	put(67, WeatherLabel.FREEZING_RAIN)
	put(71, WeatherLabel.LIGHT_SNOW)
	put(73, WeatherLabel.SNOW)
	put(75, WeatherLabel.HEAVY_SNOW)
	put(77, WeatherLabel.SNOW_GRAINS)
	put(80, WeatherLabel.LIGHT_SHOWERS)
	put(81, WeatherLabel.SHOWERS)
	put(82, WeatherLabel.VIOLENT_SHOWERS)
	put(85, WeatherLabel.SNOW_SHOWERS)
	put(86, WeatherLabel.HEAVY_SNOW_SHOWERS)
	put(95, WeatherLabel.THUNDERSTORM)
	put(96, WeatherLabel.THUNDERSTORM_WITH_HAIL)
	put(99, WeatherLabel.THUNDERSTORM_WITH_HAIL)
}

private val WEATHER_LABEL_RESOURCES = buildMap<WeatherLabel, Int> {
	put(WeatherLabel.CLEAR_SKY, R.string.weather_clear_sky)
	put(WeatherLabel.MAINLY_CLEAR, R.string.weather_mainly_clear)
	put(WeatherLabel.PARTLY_CLOUDY, R.string.weather_partly_cloudy)
	put(WeatherLabel.OVERCAST, R.string.weather_overcast)
	put(WeatherLabel.FOG, R.string.weather_fog)
	put(WeatherLabel.ICY_FOG, R.string.weather_icy_fog)
	put(WeatherLabel.LIGHT_DRIZZLE, R.string.weather_light_drizzle)
	put(WeatherLabel.DRIZZLE, R.string.weather_drizzle)
	put(WeatherLabel.DENSE_DRIZZLE, R.string.weather_dense_drizzle)
	put(WeatherLabel.LIGHT_FREEZING_DRIZZLE, R.string.weather_light_freezing_drizzle)
	put(WeatherLabel.FREEZING_DRIZZLE, R.string.weather_freezing_drizzle)
	put(WeatherLabel.LIGHT_RAIN, R.string.weather_light_rain)
	put(WeatherLabel.RAIN, R.string.weather_rain)
	put(WeatherLabel.HEAVY_RAIN, R.string.weather_heavy_rain)
	put(WeatherLabel.LIGHT_FREEZING_RAIN, R.string.weather_light_freezing_rain)
	put(WeatherLabel.FREEZING_RAIN, R.string.weather_freezing_rain)
	put(WeatherLabel.LIGHT_SNOW, R.string.weather_light_snow)
	put(WeatherLabel.SNOW, R.string.weather_snow)
	put(WeatherLabel.HEAVY_SNOW, R.string.weather_heavy_snow)
	put(WeatherLabel.SNOW_GRAINS, R.string.weather_snow_grains)
	put(WeatherLabel.LIGHT_SHOWERS, R.string.weather_light_showers)
	put(WeatherLabel.SHOWERS, R.string.weather_showers)
	put(WeatherLabel.VIOLENT_SHOWERS, R.string.weather_violent_showers)
	put(WeatherLabel.SNOW_SHOWERS, R.string.weather_snow_showers)
	put(WeatherLabel.HEAVY_SNOW_SHOWERS, R.string.weather_heavy_snow_showers)
	put(WeatherLabel.THUNDERSTORM, R.string.weather_thunderstorm)
	put(WeatherLabel.THUNDERSTORM_WITH_HAIL, R.string.weather_thunderstorm_with_hail)
}

/** Normalizes hourly precipitation (mm) to a 0..1 intensity used to modulate particle density. */
fun precipitationIntensity(precipitationMillimeters: Double) = (precipitationMillimeters / MAX_PRECIPITATION_MILLIMETERS).toFloat().coerceIn(0f, 1f)

const val SEVERITY_DRIZZLE = 0.35f
const val SEVERITY_STEADY = 0.65f
const val SEVERITY_STORM = 0.8f
const val SEVERITY_HEAVY = 1f

private const val MAX_PRECIPITATION_MILLIMETERS = 10.0
