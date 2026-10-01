package xyz.attacktive.weatherd.domain.model

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal data class WeatherProviderDefinition(
	val model: String?,
	val isGlobal: Boolean,
	private val coverage: WeatherCoverage
) {
	fun supports(latitude: Double, longitude: Double) = coverage.contains(latitude, longitude)
}

internal val WeatherProviderType.definition: WeatherProviderDefinition
	get() = WEATHER_PROVIDER_DEFINITIONS.getValue(this)

internal val globalWeatherProviders: List<WeatherProviderType>
	get() = WeatherProviderType.entries.filter { it.definition.isGlobal }

private val WEATHER_PROVIDER_DEFINITIONS = mapOf(
	WeatherProviderType.OPEN_METEO to WeatherProviderDefinition(model = null, isGlobal = true, coverage = GlobalCoverage),
	WeatherProviderType.MET_NORWAY to WeatherProviderDefinition(model = null, isGlobal = true, coverage = GlobalCoverage),
	WeatherProviderType.DWD_ICON_GLOBAL to WeatherProviderDefinition(model = "dwd_icon_global", isGlobal = true, coverage = GlobalCoverage),
	WeatherProviderType.DWD_ICON_EU to WeatherProviderDefinition(
		model = "dwd_icon_eu",
		isGlobal = false,
		coverage = RectangularCoverage(latitudeMin = 29.5, latitudeMax = 70.5, longitudeMin = -23.5, longitudeMax = 62.5)
	),
	WeatherProviderType.DWD_ICON_D2 to WeatherProviderDefinition(
		model = "dwd_icon_d2",
		isGlobal = false,
		coverage = RectangularCoverage(latitudeMin = 43.18, latitudeMax = 58.08, longitudeMin = -3.94, longitudeMax = 20.34)
	),
	WeatherProviderType.ITALIA_METEO to WeatherProviderDefinition(
		model = "italia_meteo_arpae_icon_2i",
		isGlobal = false,
		coverage = RectangularCoverage(latitudeMin = 33.7, latitudeMax = 48.9, longitudeMin = 3.0, longitudeMax = 22.0)
	),
	WeatherProviderType.METEOSWISS_ICON_CH1 to WeatherProviderDefinition(model = "meteoswiss_icon_ch1", isGlobal = false, coverage = MeteoSwissCoverage),
	WeatherProviderType.METEOSWISS_ICON_CH2 to WeatherProviderDefinition(model = "meteoswiss_icon_ch2", isGlobal = false, coverage = MeteoSwissCoverage)
)

internal fun interface WeatherCoverage {
	fun contains(latitude: Double, longitude: Double): Boolean
}

private object GlobalCoverage: WeatherCoverage {
	override fun contains(latitude: Double, longitude: Double) = latitude in -90.0..90.0 && longitude in -180.0..180.0
}

private data class RectangularCoverage(
	val latitudeMin: Double,
	val latitudeMax: Double,
	val longitudeMin: Double,
	val longitudeMax: Double
): WeatherCoverage {
	override fun contains(latitude: Double, longitude: Double) = latitude in latitudeMin..latitudeMax && longitude in longitudeMin..longitudeMax
}

/**
 * MeteoSwiss ICON uses a rotated latitude/longitude grid.
 * These projected bounds mirror the trimmed CH1/CH2 grids Open-Meteo currently publishes, so Weatherd does not probe a regional model outside its usable domain.
 */
private object MeteoSwissCoverage: WeatherCoverage {
	override fun contains(latitude: Double, longitude: Double): Boolean {
		val longitudeRadians = Math.toRadians(longitude)
		val latitudeRadians = Math.toRadians(latitude)
		val theta = Math.toRadians(133.0)
		val phi = Math.toRadians(190.0)
		val cartesianX = cos(longitudeRadians) * cos(latitudeRadians)
		val cartesianY = sin(longitudeRadians) * cos(latitudeRadians)
		val cartesianZ = sin(latitudeRadians)
		val rotatedX = cos(theta) * cos(phi) * cartesianX + cos(theta) * sin(phi) * cartesianY + sin(theta) * cartesianZ
		val rotatedY = -sin(phi) * cartesianX + cos(phi) * cartesianY
		val rotatedZ = -sin(theta) * cos(phi) * cartesianX - sin(theta) * sin(phi) * cartesianY + cos(theta) * cartesianZ
		val projectedLongitude = -Math.toDegrees(atan2(rotatedY, rotatedX))
		val projectedLatitude = -Math.toDegrees(asin(rotatedZ))

		return projectedLongitude in -6.46..4.42 && projectedLatitude in -4.06..2.98
	}
}
