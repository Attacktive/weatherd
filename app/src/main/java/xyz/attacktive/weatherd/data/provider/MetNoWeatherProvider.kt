package xyz.attacktive.weatherd.data.provider

import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import xyz.attacktive.weatherd.data.api.MetNoApiService
import xyz.attacktive.weatherd.data.api.dto.sunriseDate
import xyz.attacktive.weatherd.data.api.dto.toSnapshot
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.provider.WeatherProvider

@Singleton
class MetNoWeatherProvider @Inject constructor(private val api: MetNoApiService) : WeatherProvider {
	override suspend fun current(latitude: Double, longitude: Double): WeatherSnapshot {
		val latitudeQuery = latitude.toMetNoCoordinate()
		val longitudeQuery = longitude.toMetNoCoordinate()
		val forecast = api.forecast(latitudeQuery, longitudeQuery)
		val sun = api.sunrise(latitudeQuery, longitudeQuery, forecast.sunriseDate(longitude))

		return forecast.toSnapshot(sun)
	}
}

internal fun Double.toMetNoCoordinate(): String = BigDecimal.valueOf(this).setScale(MET_NO_COORDINATE_DECIMALS, RoundingMode.DOWN).toPlainString()

private const val MET_NO_COORDINATE_DECIMALS = 4
