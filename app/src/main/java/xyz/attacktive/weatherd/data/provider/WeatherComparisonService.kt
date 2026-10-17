package xyz.attacktive.weatherd.data.provider

import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.domain.model.WeatherLabel
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.model.PrecipitationWindow
import xyz.attacktive.weatherd.domain.model.PrecipitationWindowDirection
import xyz.attacktive.weatherd.domain.model.definition
import xyz.attacktive.weatherd.domain.repository.LocationRepository
import xyz.attacktive.weatherd.di.ComparisonMetNo

/** One explicitly requested comparison. No location, forecast, or diagnostic report is persisted. */
class WeatherComparisonService @Inject constructor(
	private val locationRepository: LocationRepository,
	private val openMeteoWeatherProvider: OpenMeteoWeatherProvider,
	@ComparisonMetNo private val metNoWeatherProvider: MetNoWeatherProvider
) {
	suspend fun capture(settings: AppSettings): WeatherComparisonCapture? {
		val location = locationFor(settings) ?: return null
		val samples = coroutineScope {
			COMPARISON_PROVIDERS.map { provider ->
				async { sample(provider, location) }
			}.awaitAll()
		}

		return WeatherComparisonCapture(System.currentTimeMillis() / 1000L, samples)
	}

	private suspend fun locationFor(settings: AppSettings): GeoLocation? {
		if (settings.useDeviceLocation) {
			return locationRepository.currentLocation()
		}

		val latitude = settings.manualLatitude ?: return null
		val longitude = settings.manualLongitude ?: return null
		if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
			return null
		}

		return GeoLocation(latitude, longitude)
	}

	private suspend fun sample(provider: WeatherProviderType, location: GeoLocation): WeatherComparisonSample {
		if (!provider.definition.supports(location.latitude, location.longitude)) {
			return WeatherComparisonSample(provider, WeatherSampleStatus.OUTSIDE_COVERAGE)
		}

		return try {
			val snapshot = withTimeoutOrNull(PROVIDER_TIMEOUT_MILLIS) {
				if (provider == WeatherProviderType.MET_NORWAY) {
					metNoWeatherProvider.current(location.latitude, location.longitude)
				} else {
					openMeteoWeatherProvider.current(location.latitude, location.longitude, provider)
				}
			}

			if (snapshot == null) {
				WeatherComparisonSample(provider, WeatherSampleStatus.TIMED_OUT)
			} else {
				WeatherComparisonSample.fromSnapshot(provider, snapshot)
			}
		} catch (cancellationException: CancellationException) {
			throw cancellationException
		} catch (_: Exception) {
			WeatherComparisonSample(provider, WeatherSampleStatus.REQUEST_FAILED)
		}
	}

	private companion object {
		const val PROVIDER_TIMEOUT_MILLIS = 25_000L
		val COMPARISON_PROVIDERS = listOf(
			WeatherProviderType.OPEN_METEO,
			WeatherProviderType.ITALIA_METEO,
			WeatherProviderType.DWD_ICON_EU,
			WeatherProviderType.MET_NORWAY
		)
	}
}

enum class ObservedSky {
	CLEAR,
	PARTLY_CLOUDY,
	OVERCAST,
	RAIN,
	SNOW,
	UNSURE
}

enum class WeatherSampleStatus {
	SUCCESS,
	OUTSIDE_COVERAGE,
	TIMED_OUT,
	REQUEST_FAILED
}

/** Only provider-derived weather fields, never the requested coordinates or place name. */
data class WeatherComparisonSample(
	val provider: WeatherProviderType,
	val status: WeatherSampleStatus,
	val validAtEpochSeconds: Long? = null,
	val condition: WeatherLabel? = null,
	val cloudCoverPercent: Int? = null,
	val lowCloudPercent: Int? = null,
	val midCloudPercent: Int? = null,
	val highCloudPercent: Int? = null,
	val temperatureCelsius: Double? = null,
	val precipitationMillimeters: Double? = null,
	val precipitationWindow: PrecipitationWindow? = null
) {
	companion object {
		fun fromSnapshot(provider: WeatherProviderType, snapshot: WeatherSnapshot): WeatherComparisonSample {
			val observation = snapshot.observation
			val layers = observation.cloudCover.layers

			return WeatherComparisonSample(
				provider = provider,
				status = WeatherSampleStatus.SUCCESS,
				validAtEpochSeconds = snapshot.observedAtEpochSeconds,
				condition = observation.condition.label,
				cloudCoverPercent = observation.cloudCover.totalPercent,
				lowCloudPercent = layers?.lowPercent,
				midCloudPercent = layers?.midPercent,
				highCloudPercent = layers?.highPercent,
				temperatureCelsius = observation.temperatureCelsius,
				precipitationMillimeters = observation.precipitationMillimeters,
				precipitationWindow = snapshot.precipitationWindow
			)
		}
	}
}

/** Shareable by explicit tester action only. Report contains no coordinates or location labels. */
data class WeatherComparisonCapture(
	val capturedAtEpochSeconds: Long,
	val samples: List<WeatherComparisonSample>
) {
	fun toReport(observedSky: ObservedSky, appVersion: String) = buildString {
		appendLine("Weatherd $appVersion - experimental provider comparison")
		appendLine("Captured UTC: ${Instant.ofEpochSecond(capturedAtEpochSeconds)}")
		appendLine("Tester observed: $observedSky")
		appendLine("Location: omitted for privacy")
		appendLine("Weather data: Open-Meteo and MET Norway (CC BY 4.0), normalized by Weatherd")
		appendLine("Times below are provider forecast-valid times, not ground observations.")
		appendLine("Precipitation totals use different accumulation windows; compare periods before comparing millimeters.")
		appendLine()

		for (sample in samples) {
			appendLine("${sample.provider}: ${sample.status}")
			if (sample.status == WeatherSampleStatus.SUCCESS) {
				appendLine("  Forecast valid UTC: ${sample.validAtEpochSeconds?.let(Instant::ofEpochSecond) ?: "unknown"}")
				appendLine("  Condition: ${sample.condition ?: "unknown"}")
				appendLine("  Cloud cover total/low/mid/high (%): ${sample.cloudCoverPercent ?: "unknown"} / ${sample.lowCloudPercent ?: "unknown"} / ${sample.midCloudPercent ?: "unknown"} / ${sample.highCloudPercent ?: "unknown"}")
				appendLine("  Temperature (C): ${sample.temperatureCelsius ?: "unknown"}")
				appendLine("  Precipitation (mm): ${sample.precipitationMillimeters ?: "unknown"}")
				appendLine("  Precipitation window: ${sample.precipitationWindow.descriptionAt(sample.validAtEpochSeconds)}")
			}
		}
	}
}

/** The time bounds are anchored to the source's forecast-valid timestamp, not the comparison capture time. */
private fun PrecipitationWindow?.descriptionAt(validAtEpochSeconds: Long?): String {
	if (this == null || validAtEpochSeconds == null) {
		return "unknown (provider interval unavailable)"
	}

	val start = if (direction == PrecipitationWindowDirection.PRECEDING) validAtEpochSeconds - durationSeconds else validAtEpochSeconds
	val end = if (direction == PrecipitationWindowDirection.FOLLOWING) validAtEpochSeconds + durationSeconds else validAtEpochSeconds
	val duration = if (durationSeconds % 60 == 0) {
		"${durationSeconds / 60} min"
	} else {
		"$durationSeconds sec"
	}

	val directionName = when (direction) {
		PrecipitationWindowDirection.PRECEDING -> "preceding"
		PrecipitationWindowDirection.FOLLOWING -> "following"
	}

	return "$directionName $duration (UTC ${Instant.ofEpochSecond(start)} to ${Instant.ofEpochSecond(end)})"
}
