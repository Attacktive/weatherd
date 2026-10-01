package xyz.attacktive.weatherd.data.provider

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import xyz.attacktive.weatherd.domain.model.WeatherFallbackReason
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.model.definition
import xyz.attacktive.weatherd.domain.provider.WeatherProvider
import xyz.attacktive.weatherd.domain.repository.SettingsRepository

@Singleton
class ConfiguredWeatherProvider @Inject constructor(
	private val settingsRepository: SettingsRepository,
	private val openMeteoWeatherProvider: OpenMeteoWeatherProvider,
	private val metNoWeatherProvider: MetNoWeatherProvider
) : WeatherProvider {
	override suspend fun current(latitude: Double, longitude: Double): WeatherSnapshot {
		val settings = settingsRepository.settings.first()
		val primary = settings.weatherProvider
		val configuredFallback = settings.weatherFallbackProvider
		val fallback = if (configuredFallback.definition.isGlobal) {
			configuredFallback
		} else {
			WeatherProviderType.OPEN_METEO
		}

		if (!primary.definition.supports(latitude, longitude)) {
			return fetch(fallback, latitude, longitude).withFallbackReason(WeatherFallbackReason.OUTSIDE_COVERAGE)
		}

		return try {
			fetch(primary, latitude, longitude)
		} catch (cancellationException: CancellationException) {
			throw cancellationException
		} catch (primaryFailure: Exception) {
			if (primary == fallback) {
				throw primaryFailure
			}

			try {
				fetch(fallback, latitude, longitude).withFallbackReason(WeatherFallbackReason.PRIMARY_FAILED)
			} catch (cancellationException: CancellationException) {
				throw cancellationException
			} catch (fallbackFailure: Exception) {
				fallbackFailure.addSuppressed(primaryFailure)
				throw fallbackFailure
			}
		}
	}

	private suspend fun fetch(provider: WeatherProviderType, latitude: Double, longitude: Double) = when (provider) {
		WeatherProviderType.MET_NORWAY -> metNoWeatherProvider.current(latitude, longitude)
		else -> openMeteoWeatherProvider.current(latitude, longitude, provider)
	}

	private fun WeatherSnapshot.withFallbackReason(reason: WeatherFallbackReason) = copy(source = source.copy(fallbackReason = reason))
}
