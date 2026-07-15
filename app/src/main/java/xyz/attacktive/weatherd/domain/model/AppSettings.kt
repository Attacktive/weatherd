package xyz.attacktive.weatherd.domain.model

data class AppSettings(
	val updateIntervalMinutes: Int = 30,
	val useDeviceLocation: Boolean = true,
	val manualLatitude: Double? = null,
	val manualLongitude: Double? = null,
	val manualLocationLabel: String? = null,
	val backdropScene: BackdropScene = BackdropScene.NONE,
	val showWeatherLabel: Boolean = false,
	val showLocationLabel: Boolean = false,
	val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS
)

/** Selectable weather-refresh intervals in minutes, offered in Settings. */
val UPDATE_INTERVAL_OPTIONS = listOf(15, 30, 60, 120, 180, 360)
