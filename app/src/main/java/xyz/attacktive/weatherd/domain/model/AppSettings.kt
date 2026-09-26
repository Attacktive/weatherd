package xyz.attacktive.weatherd.domain.model

import java.util.Locale

data class AppSettings(
	val weatherProvider: WeatherProviderType = WeatherProviderType.OPEN_METEO,
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
	val wallpaperScrollingEnabled: Boolean = false,
	val precipitationIntensityScale: Float = 1f,
	val windIntensityScale: Float = 1f,
	val cloudIntensityScale: Float = 1f,
	val cloudSizeScale: Float = 1f,
	val cloudCountScale: Float = 1f,
	val cloudContrastScale: Float = 1f,
	val skyBrightnessScale: Float = 1f,
	val nightBrightnessScale: Float = 1f,
	val skySaturationScale: Float = 1f,
	val skyColorPreset: SkyColorPreset = SkyColorPreset.NATURAL,
	val sunVisible: Boolean = true,
	val moonVisible: Boolean = true,
	val sunSizeScale: Float = 1f,
	val sunColorPreset: SunColorPreset = SunColorPreset.NATURAL,
	val lensFlareEnabled: Boolean = true,
	val sceneSimulatorEnabled: Boolean = false,
	val sceneSimulatorActive: Boolean = false,
	val sceneSimulatorPresetIndex: Int = 0,
	val sceneSimulatorDayPhase: DayPhase = DayPhase.DAY,
	val sceneSimulatorCelestialProgress: Float = 0.5f,
)

/** Fresh-install settings, with region-sensitive choices resolved before persistence supplies any overrides. */
fun defaultAppSettings(locale: Locale = Locale.getDefault(Locale.Category.FORMAT)) = AppSettings(
	temperatureUnit = TemperatureUnit.defaultForLocale(locale)
)

/** Selectable weather-refresh intervals in minutes, offered in Settings. */
val UPDATE_INTERVAL_OPTIONS = listOf(15, 30, 60, 120, 180, 360)

/**
 * Bounds for the weather intensity multipliers offered in Settings.
 * The floor is deliberately above zero: a scale of 0 would silently erase rain the user can see out of the window, which reads as a broken wallpaper rather than a setting.
 */
val INTENSITY_SCALE_RANGE = 0.1f..2f

/** Bounds for fair-weather cloud body size and rendered cloud coverage. */
val CLOUD_SIZE_SCALE_RANGE = 0.5f..2f
val CLOUD_COUNT_SCALE_RANGE = 0.5f..2f
