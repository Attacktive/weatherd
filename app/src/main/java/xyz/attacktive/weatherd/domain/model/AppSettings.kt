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
	val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
	val frameRateCap: FrameRateCap = FrameRateCap.UNCAPPED,
	val precipitationIntensityScale: Float = 1f,
	val windIntensityScale: Float = 1f,
	val cloudIntensityScale: Float = 1f
)

/** Selectable weather-refresh intervals in minutes, offered in Settings. */
val UPDATE_INTERVAL_OPTIONS = listOf(15, 30, 60, 120, 180, 360)

/**
 * Bounds for the weather intensity multipliers offered in Settings.
 * The floor is deliberately above zero: a scale of 0 would silently erase rain the user can see out of the window, which reads as a broken wallpaper rather than a setting.
 */
val INTENSITY_SCALE_RANGE = 0.1f..2f
