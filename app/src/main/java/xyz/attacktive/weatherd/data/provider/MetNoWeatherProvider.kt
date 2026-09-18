package xyz.attacktive.weatherd.data.provider

import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton
import xyz.attacktive.weatherd.data.api.MetNoApiService
import xyz.attacktive.weatherd.data.api.dto.toSnapshot
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.provider.WeatherProvider

@Singleton
class MetNoWeatherProvider @Inject constructor(private val api: MetNoApiService) : WeatherProvider {
	override suspend fun current(latitude: Double, longitude: Double): WeatherSnapshot =
		api.forecast(latitude.roundForMetNo(), longitude.roundForMetNo()).toSnapshot()
}

private fun Double.roundForMetNo(): Double = BigDecimal.valueOf(this)
	.setScale(4, RoundingMode.HALF_UP)
	.toDouble()
