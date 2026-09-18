package xyz.attacktive.weatherd.domain.weather

import androidx.annotation.StringRes
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.WeatherCondition
import xyz.attacktive.weatherd.domain.model.WeatherLabel

/** Maps an Open-Meteo WMO code into provider-neutral scene features and an optional display label. */
fun conditionForWmoCode(weatherCode: Int): WeatherCondition =
	(WMO_FEATURES[weatherCode] ?: WeatherCondition()).copy(label = WMO_LABELS[weatherCode])

@StringRes
fun weatherLabelFor(label: WeatherLabel?): Int? = WEATHER_LABEL_RESOURCES[label]

private val WMO_FEATURES = mapOf(
	45 to FOG,
	48 to FOG,
	51 to RAIN_DRIZZLE,
	53 to RAIN_DRIZZLE,
	55 to RAIN_DRIZZLE,
	56 to SLEET_STEADY,
	57 to SLEET_STEADY,
	61 to RAIN_STEADY,
	63 to RAIN_STEADY,
	65 to RAIN_HEAVY,
	66 to SLEET_STEADY,
	67 to SLEET_STEADY,
	71 to SNOW_STEADY,
	73 to SNOW_STEADY,
	75 to SNOW_HEAVY,
	77 to SNOW_STEADY,
	80 to RAIN_STEADY,
	81 to RAIN_STEADY,
	82 to RAIN_HEAVY,
	85 to SNOW_STEADY,
	86 to SNOW_HEAVY,
	95 to RAIN_STORM,
	96 to RAIN_STORM,
	99 to RAIN_STORM
)

private val WMO_LABELS = mapOf(
	0 to WeatherLabel.CLEAR_SKY,
	1 to WeatherLabel.MAINLY_CLEAR,
	2 to WeatherLabel.PARTLY_CLOUDY,
	3 to WeatherLabel.OVERCAST,
	45 to WeatherLabel.FOG,
	48 to WeatherLabel.ICY_FOG,
	51 to WeatherLabel.LIGHT_DRIZZLE,
	53 to WeatherLabel.DRIZZLE,
	55 to WeatherLabel.DENSE_DRIZZLE,
	56 to WeatherLabel.LIGHT_FREEZING_DRIZZLE,
	57 to WeatherLabel.FREEZING_DRIZZLE,
	61 to WeatherLabel.LIGHT_RAIN,
	63 to WeatherLabel.RAIN,
	65 to WeatherLabel.HEAVY_RAIN,
	66 to WeatherLabel.LIGHT_FREEZING_RAIN,
	67 to WeatherLabel.FREEZING_RAIN,
	71 to WeatherLabel.LIGHT_SNOW,
	73 to WeatherLabel.SNOW,
	75 to WeatherLabel.HEAVY_SNOW,
	77 to WeatherLabel.SNOW_GRAINS,
	80 to WeatherLabel.LIGHT_SHOWERS,
	81 to WeatherLabel.SHOWERS,
	82 to WeatherLabel.VIOLENT_SHOWERS,
	85 to WeatherLabel.SNOW_SHOWERS,
	86 to WeatherLabel.HEAVY_SNOW_SHOWERS,
	95 to WeatherLabel.THUNDERSTORM,
	96 to WeatherLabel.THUNDERSTORM_WITH_HAIL,
	99 to WeatherLabel.THUNDERSTORM_WITH_HAIL
)

private val WEATHER_LABEL_RESOURCES = mapOf(
	WeatherLabel.CLEAR_SKY to R.string.weather_clear_sky,
	WeatherLabel.MAINLY_CLEAR to R.string.weather_mainly_clear,
	WeatherLabel.PARTLY_CLOUDY to R.string.weather_partly_cloudy,
	WeatherLabel.OVERCAST to R.string.weather_overcast,
	WeatherLabel.FOG to R.string.weather_fog,
	WeatherLabel.ICY_FOG to R.string.weather_icy_fog,
	WeatherLabel.LIGHT_DRIZZLE to R.string.weather_light_drizzle,
	WeatherLabel.DRIZZLE to R.string.weather_drizzle,
	WeatherLabel.DENSE_DRIZZLE to R.string.weather_dense_drizzle,
	WeatherLabel.LIGHT_FREEZING_DRIZZLE to R.string.weather_light_freezing_drizzle,
	WeatherLabel.FREEZING_DRIZZLE to R.string.weather_freezing_drizzle,
	WeatherLabel.LIGHT_RAIN to R.string.weather_light_rain,
	WeatherLabel.RAIN to R.string.weather_rain,
	WeatherLabel.HEAVY_RAIN to R.string.weather_heavy_rain,
	WeatherLabel.LIGHT_FREEZING_RAIN to R.string.weather_light_freezing_rain,
	WeatherLabel.FREEZING_RAIN to R.string.weather_freezing_rain,
	WeatherLabel.LIGHT_SNOW to R.string.weather_light_snow,
	WeatherLabel.SNOW to R.string.weather_snow,
	WeatherLabel.HEAVY_SNOW to R.string.weather_heavy_snow,
	WeatherLabel.SNOW_GRAINS to R.string.weather_snow_grains,
	WeatherLabel.LIGHT_SHOWERS to R.string.weather_light_showers,
	WeatherLabel.SHOWERS to R.string.weather_showers,
	WeatherLabel.VIOLENT_SHOWERS to R.string.weather_violent_showers,
	WeatherLabel.SNOW_SHOWERS to R.string.weather_snow_showers,
	WeatherLabel.HEAVY_SNOW_SHOWERS to R.string.weather_heavy_snow_showers,
	WeatherLabel.THUNDERSTORM to R.string.weather_thunderstorm,
	WeatherLabel.THUNDERSTORM_WITH_HAIL to R.string.weather_thunderstorm_with_hail
)

private val FOG = WeatherCondition(fog = true)
private val RAIN_DRIZZLE = WeatherCondition(precipitationKind = PrecipitationKind.RAIN, severity = SEVERITY_DRIZZLE)
private val RAIN_STEADY = WeatherCondition(precipitationKind = PrecipitationKind.RAIN, severity = SEVERITY_STEADY)
private val RAIN_HEAVY = WeatherCondition(precipitationKind = PrecipitationKind.RAIN, severity = SEVERITY_HEAVY)
private val SLEET_STEADY = WeatherCondition(precipitationKind = PrecipitationKind.SLEET, severity = SEVERITY_STEADY)
private val SNOW_STEADY = WeatherCondition(precipitationKind = PrecipitationKind.SNOW, severity = SEVERITY_STEADY)
private val SNOW_HEAVY = WeatherCondition(precipitationKind = PrecipitationKind.SNOW, severity = SEVERITY_HEAVY)
private val RAIN_STORM = WeatherCondition(precipitationKind = PrecipitationKind.RAIN, severity = SEVERITY_STORM, thunder = true)

/** Normalizes hourly precipitation (mm) to a 0..1 intensity used to modulate particle density. */
fun precipitationIntensity(precipitationMillimeters: Double) = (precipitationMillimeters / MAX_PRECIPITATION_MILLIMETERS).toFloat().coerceIn(0f, 1f)

const val SEVERITY_DRIZZLE = 0.35f
const val SEVERITY_STEADY = 0.65f
const val SEVERITY_STORM = 0.8f
const val SEVERITY_HEAVY = 1f

private const val MAX_PRECIPITATION_MILLIMETERS = 10.0
