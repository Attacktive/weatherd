package xyz.attacktive.weatherd.data.provider

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.provider.WeatherProvider
import xyz.attacktive.weatherd.domain.repository.SettingsRepository

@Singleton
class ConfiguredWeatherProvider @Inject constructor(
	private val settingsRepository: SettingsRepository,
	private val openMeteoWeatherProvider: OpenMeteoWeatherProvider,
	private val metNoWeatherProvider: MetNoWeatherProvider
) : WeatherProvider {
	override suspend fun current(latitude: Double, longitude: Double): WeatherSnapshot {
		val provider = when (settingsRepository.settings.first().weatherProvider) {
			WeatherProviderType.OPEN_METEO -> openMeteoWeatherProvider
			WeatherProviderType.MET_NORWAY -> metNoWeatherProvider
		}

		return provider.current(latitude, longitude)
	}
}
