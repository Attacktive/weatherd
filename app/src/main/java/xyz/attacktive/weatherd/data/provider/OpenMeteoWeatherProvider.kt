package xyz.attacktive.weatherd.data.provider

import javax.inject.Inject
import javax.inject.Singleton
import xyz.attacktive.weatherd.data.api.OpenMeteoApiService
import xyz.attacktive.weatherd.data.api.dto.toSnapshot
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.provider.WeatherProvider

@Singleton
class OpenMeteoWeatherProvider @Inject constructor(private val api: OpenMeteoApiService) : WeatherProvider {
	override suspend fun current(latitude: Double, longitude: Double): WeatherSnapshot =
		api.forecast(latitude, longitude).toSnapshot()
}
