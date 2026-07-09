package xyz.attacktive.weatherd.domain.weather

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
 * Decomposes an Open-Meteo WMO weather code into scene features. Codes 0-3 (and anything unknown)
 * carry no features of their own; the separately-reported cloud cover shapes those skies.
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

/** Normalises hourly precipitation (mm) to a 0..1 intensity used to modulate particle density. */
fun precipitationIntensity(precipitationMillimeters: Double) = (precipitationMillimeters / MAX_PRECIPITATION_MILLIMETERS).toFloat().coerceIn(0f, 1f)

const val SEVERITY_DRIZZLE = 0.35f
const val SEVERITY_STEADY = 0.65f
const val SEVERITY_STORM = 0.8f
const val SEVERITY_HEAVY = 1f

private const val MAX_PRECIPITATION_MILLIMETERS = 10.0
