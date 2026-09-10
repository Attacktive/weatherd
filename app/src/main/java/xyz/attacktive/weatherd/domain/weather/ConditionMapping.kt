package xyz.attacktive.weatherd.domain.weather

import androidx.annotation.StringRes
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

/**
 * The scene features carried by a WMO weather code: what falls and how hard, plus fog and lightning.
 * Cloud cover is deliberately absent — the API reports it separately and continuously.
 */
data class WeatherCondition(
	val precipitationKind: PrecipitationKind? = null,
	val severity: Float = 0f,
	val fog: Boolean = false,
	val thunder: Boolean = false
)

/**
 * Decomposes an Open-Meteo WMO weather code into scene features.
 * Codes 0-3 (and anything unknown) carry no features of their own; the separately-reported cloud cover shapes those skies.
 */
fun conditionFor(weatherCode: Int): WeatherCondition = when (weatherCode) {
	45, 48 -> WeatherCondition(fog = true)
	51, 53, 55 -> WeatherCondition(PrecipitationKind.RAIN, SEVERITY_DRIZZLE)
	56, 57, 66, 67 -> WeatherCondition(PrecipitationKind.SLEET, SEVERITY_STEADY)
	61, 63, 80, 81 -> WeatherCondition(PrecipitationKind.RAIN, SEVERITY_STEADY)
	65, 82 -> WeatherCondition(PrecipitationKind.RAIN, SEVERITY_HEAVY)
	71, 73, 77, 85 -> WeatherCondition(PrecipitationKind.SNOW, SEVERITY_STEADY)
	75, 86 -> WeatherCondition(PrecipitationKind.SNOW, SEVERITY_HEAVY)
	95, 96, 99 -> WeatherCondition(PrecipitationKind.RAIN, SEVERITY_STORM, thunder = true)
	else -> WeatherCondition()
}

/** The string-resource ID for the short human label a WMO weather code wears on the wallpaper; null for codes outside the table, so callers can omit the text. */
@StringRes
fun weatherLabelFor(weatherCode: Int): Int? = when (weatherCode) {
	0 -> R.string.weather_clear_sky
	1 -> R.string.weather_mainly_clear
	2 -> R.string.weather_partly_cloudy
	3 -> R.string.weather_overcast
	45 -> R.string.weather_fog
	48 -> R.string.weather_icy_fog
	51 -> R.string.weather_light_drizzle
	53 -> R.string.weather_drizzle
	55 -> R.string.weather_dense_drizzle
	56 -> R.string.weather_light_freezing_drizzle
	57 -> R.string.weather_freezing_drizzle
	61 -> R.string.weather_light_rain
	63 -> R.string.weather_rain
	65 -> R.string.weather_heavy_rain
	66 -> R.string.weather_light_freezing_rain
	67 -> R.string.weather_freezing_rain
	71 -> R.string.weather_light_snow
	73 -> R.string.weather_snow
	75 -> R.string.weather_heavy_snow
	77 -> R.string.weather_snow_grains
	80 -> R.string.weather_light_showers
	81 -> R.string.weather_showers
	82 -> R.string.weather_violent_showers
	85 -> R.string.weather_snow_showers
	86 -> R.string.weather_heavy_snow_showers
	95 -> R.string.weather_thunderstorm
	96, 99 -> R.string.weather_thunderstorm_with_hail
	else -> null
}

/** Normalizes hourly precipitation (mm) to a 0..1 intensity used to modulate particle density. */
fun precipitationIntensity(precipitationMillimeters: Double) = (precipitationMillimeters / MAX_PRECIPITATION_MILLIMETERS).toFloat().coerceIn(0f, 1f)

const val SEVERITY_DRIZZLE = 0.35f
const val SEVERITY_STEADY = 0.65f
const val SEVERITY_STORM = 0.8f
const val SEVERITY_HEAVY = 1f

private const val MAX_PRECIPITATION_MILLIMETERS = 10.0
