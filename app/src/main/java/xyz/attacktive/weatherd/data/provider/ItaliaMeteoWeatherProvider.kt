package xyz.attacktive.weatherd.data.provider

import javax.inject.Inject
import javax.inject.Singleton
import xyz.attacktive.weatherd.data.api.OpenMeteoApiService
import xyz.attacktive.weatherd.data.api.dto.toSnapshot
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.provider.WeatherProvider

@Singleton
class ItaliaMeteoWeatherProvider @Inject constructor(private val api: OpenMeteoApiService) : WeatherProvider {
	override suspend fun current(latitude: Double, longitude: Double): WeatherSnapshot {
		return api.forecast(latitude, longitude, models = ITALIA_METEO_ICON_2I_MODEL).toSnapshot()
	}
}

internal const val ITALIA_METEO_ICON_2I_MODEL = "italia_meteo_arpae_icon_2i"
