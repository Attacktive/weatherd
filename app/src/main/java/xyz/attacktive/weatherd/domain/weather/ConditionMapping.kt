package xyz.attacktive.weatherd.domain.weather

import androidx.annotation.StringRes
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.WeatherCondition
import xyz.attacktive.weatherd.domain.model.WeatherLabel

/** Maps an Open-Meteo WMO code into provider-neutral scene features and an optional display label. */
fun conditionForWmoCode(weatherCode: Int): WeatherCondition = when (weatherCode) {
	0 -> WeatherCondition(label = WeatherLabel.CLEAR_SKY)
	1 -> WeatherCondition(label = WeatherLabel.MAINLY_CLEAR)
	2 -> WeatherCondition(label = WeatherLabel.PARTLY_CLOUDY)
	3 -> WeatherCondition(label = WeatherLabel.OVERCAST)
	45 -> WeatherCondition(label = WeatherLabel.FOG, fog = true)
	48 -> WeatherCondition(label = WeatherLabel.ICY_FOG, fog = true)
	51 -> WeatherCondition(WeatherLabel.LIGHT_DRIZZLE, PrecipitationKind.RAIN, SEVERITY_DRIZZLE)
	53 -> WeatherCondition(WeatherLabel.DRIZZLE, PrecipitationKind.RAIN, SEVERITY_DRIZZLE)
	55 -> WeatherCondition(WeatherLabel.DENSE_DRIZZLE, PrecipitationKind.RAIN, SEVERITY_DRIZZLE)
	56 -> WeatherCondition(WeatherLabel.LIGHT_FREEZING_DRIZZLE, PrecipitationKind.SLEET, SEVERITY_STEADY)
	57 -> WeatherCondition(WeatherLabel.FREEZING_DRIZZLE, PrecipitationKind.SLEET, SEVERITY_STEADY)
	61 -> WeatherCondition(WeatherLabel.LIGHT_RAIN, PrecipitationKind.RAIN, SEVERITY_STEADY)
	63 -> WeatherCondition(WeatherLabel.RAIN, PrecipitationKind.RAIN, SEVERITY_STEADY)
	65 -> WeatherCondition(WeatherLabel.HEAVY_RAIN, PrecipitationKind.RAIN, SEVERITY_HEAVY)
	66 -> WeatherCondition(WeatherLabel.LIGHT_FREEZING_RAIN, PrecipitationKind.SLEET, SEVERITY_STEADY)
	67 -> WeatherCondition(WeatherLabel.FREEZING_RAIN, PrecipitationKind.SLEET, SEVERITY_STEADY)
	71 -> WeatherCondition(WeatherLabel.LIGHT_SNOW, PrecipitationKind.SNOW, SEVERITY_STEADY)
	73 -> WeatherCondition(WeatherLabel.SNOW, PrecipitationKind.SNOW, SEVERITY_STEADY)
	75 -> WeatherCondition(WeatherLabel.HEAVY_SNOW, PrecipitationKind.SNOW, SEVERITY_HEAVY)
	77 -> WeatherCondition(WeatherLabel.SNOW_GRAINS, PrecipitationKind.SNOW, SEVERITY_STEADY)
	80 -> WeatherCondition(WeatherLabel.LIGHT_SHOWERS, PrecipitationKind.RAIN, SEVERITY_STEADY)
	81 -> WeatherCondition(WeatherLabel.SHOWERS, PrecipitationKind.RAIN, SEVERITY_STEADY)
	82 -> WeatherCondition(WeatherLabel.VIOLENT_SHOWERS, PrecipitationKind.RAIN, SEVERITY_HEAVY)
	85 -> WeatherCondition(WeatherLabel.SNOW_SHOWERS, PrecipitationKind.SNOW, SEVERITY_STEADY)
	86 -> WeatherCondition(WeatherLabel.HEAVY_SNOW_SHOWERS, PrecipitationKind.SNOW, SEVERITY_HEAVY)
	95 -> WeatherCondition(WeatherLabel.THUNDERSTORM, PrecipitationKind.RAIN, SEVERITY_STORM, thunder = true)
	96, 99 -> WeatherCondition(WeatherLabel.THUNDERSTORM_WITH_HAIL, PrecipitationKind.RAIN, SEVERITY_STORM, thunder = true)
	else -> WeatherCondition()
}

@StringRes
fun weatherLabelFor(label: WeatherLabel?): Int? = when (label) {
	WeatherLabel.CLEAR_SKY -> R.string.weather_clear_sky
	WeatherLabel.MAINLY_CLEAR -> R.string.weather_mainly_clear
	WeatherLabel.PARTLY_CLOUDY -> R.string.weather_partly_cloudy
	WeatherLabel.OVERCAST -> R.string.weather_overcast
	WeatherLabel.FOG -> R.string.weather_fog
	WeatherLabel.ICY_FOG -> R.string.weather_icy_fog
	WeatherLabel.LIGHT_DRIZZLE -> R.string.weather_light_drizzle
	WeatherLabel.DRIZZLE -> R.string.weather_drizzle
	WeatherLabel.DENSE_DRIZZLE -> R.string.weather_dense_drizzle
	WeatherLabel.LIGHT_FREEZING_DRIZZLE -> R.string.weather_light_freezing_drizzle
	WeatherLabel.FREEZING_DRIZZLE -> R.string.weather_freezing_drizzle
	WeatherLabel.LIGHT_RAIN -> R.string.weather_light_rain
	WeatherLabel.RAIN -> R.string.weather_rain
	WeatherLabel.HEAVY_RAIN -> R.string.weather_heavy_rain
	WeatherLabel.LIGHT_FREEZING_RAIN -> R.string.weather_light_freezing_rain
	WeatherLabel.FREEZING_RAIN -> R.string.weather_freezing_rain
	WeatherLabel.LIGHT_SNOW -> R.string.weather_light_snow
	WeatherLabel.SNOW -> R.string.weather_snow
	WeatherLabel.HEAVY_SNOW -> R.string.weather_heavy_snow
	WeatherLabel.SNOW_GRAINS -> R.string.weather_snow_grains
	WeatherLabel.LIGHT_SHOWERS -> R.string.weather_light_showers
	WeatherLabel.SHOWERS -> R.string.weather_showers
	WeatherLabel.VIOLENT_SHOWERS -> R.string.weather_violent_showers
	WeatherLabel.SNOW_SHOWERS -> R.string.weather_snow_showers
	WeatherLabel.HEAVY_SNOW_SHOWERS -> R.string.weather_heavy_snow_showers
	WeatherLabel.THUNDERSTORM -> R.string.weather_thunderstorm
	WeatherLabel.THUNDERSTORM_WITH_HAIL -> R.string.weather_thunderstorm_with_hail
	null -> null
}

/** Normalizes hourly precipitation (mm) to a 0..1 intensity used to modulate particle density. */
fun precipitationIntensity(precipitationMillimeters: Double) = (precipitationMillimeters / MAX_PRECIPITATION_MILLIMETERS).toFloat().coerceIn(0f, 1f)

const val SEVERITY_DRIZZLE = 0.35f
const val SEVERITY_STEADY = 0.65f
const val SEVERITY_STORM = 0.8f
const val SEVERITY_HEAVY = 1f

private const val MAX_PRECIPITATION_MILLIMETERS = 10.0
