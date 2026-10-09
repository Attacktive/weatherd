package xyz.attacktive.weatherd.domain.render

import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.domain.model.WeatherProviderType

/** The requested location source and provider configuration, distinct from the provider that ultimately supplies fallback weather. */
data class WeatherSceneTarget(
	val useDeviceLocation: Boolean,
	val latitude: Double?,
	val longitude: Double?,
	val provider: WeatherProviderType,
	val fallbackProvider: WeatherProviderType
) {
	fun matchesLocation(settings: AppSettings, deviceLocation: GeoLocation? = null): Boolean {
		if (useDeviceLocation != selectsDeviceLocation(settings)) {
			return false
		}

		if (useDeviceLocation) {
			val location = deviceLocation ?: return false
			return latitude != null && longitude != null && latitude == location.latitude && longitude == location.longitude
		}

		return latitude == settings.manualLatitude && longitude == settings.manualLongitude
	}

	fun matchesWeather(settings: AppSettings, deviceLocation: GeoLocation? = null) = provider == settings.weatherProvider && fallbackProvider == settings.weatherFallbackProvider && matchesLocation(settings, deviceLocation)

	companion object {
		internal fun selectsDeviceLocation(settings: AppSettings) = settings.useDeviceLocation || settings.manualLatitude == null || settings.manualLongitude == null

		fun from(settings: AppSettings, deviceLocation: GeoLocation? = null): WeatherSceneTarget {
			val usesDevice = selectsDeviceLocation(settings)
			val latitude = if (usesDevice) {
				deviceLocation?.latitude
			} else {
				settings.manualLatitude
			}

			val longitude = if (usesDevice) {
				deviceLocation?.longitude
			} else {
				settings.manualLongitude
			}

			return WeatherSceneTarget(
				useDeviceLocation = usesDevice,
				latitude = latitude,
				longitude = longitude,
				provider = settings.weatherProvider,
				fallbackProvider = settings.weatherFallbackProvider
			)
		}
	}
}
