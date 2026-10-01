package xyz.attacktive.weatherd.data.provider

import javax.inject.Inject
import javax.inject.Singleton
import xyz.attacktive.weatherd.data.api.OpenMeteoApiService
import xyz.attacktive.weatherd.data.api.dto.toSnapshot
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.model.WeatherSource
import xyz.attacktive.weatherd.domain.model.definition
import xyz.attacktive.weatherd.domain.provider.WeatherProvider

@Singleton
class OpenMeteoWeatherProvider @Inject constructor(private val api: OpenMeteoApiService) : WeatherProvider {
	override suspend fun current(latitude: Double, longitude: Double) = current(latitude, longitude, WeatherProviderType.OPEN_METEO)

	suspend fun current(latitude: Double, longitude: Double, provider: WeatherProviderType): WeatherSnapshot {
		check(provider != WeatherProviderType.MET_NORWAY) { "MET Norway does not use Open-Meteo" }

		val model = provider.definition.model

		return api.forecast(latitude, longitude, models = model).toSnapshot(
			WeatherSource(
				provider = provider,
				model = model
			)
		)
	}
}
