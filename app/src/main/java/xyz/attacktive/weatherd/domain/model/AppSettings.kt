package xyz.attacktive.weatherd.domain.model

import java.util.Locale

data class AppSettings(
	val weatherProvider: WeatherProviderType = WeatherProviderType.OPEN_METEO,
	val weatherFallbackProvider: WeatherProviderType = WeatherProviderType.OPEN_METEO,
	val updateIntervalMinutes: Int = 30,
	val hideStaleWeatherWarning: Boolean = false,
	val useDeviceLocation: Boolean = true,
	val manualLatitude: Double? = null,
	val manualLongitude: Double? = null,
	val manualLocationLabel: String? = null,
	val backdropScene: BackdropScene = BackdropScene.NONE,
	val showWeatherLabel: Boolean = false,
	val showLocationLabel: Boolean = false,
	val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
	val frameRateCap: FrameRateCap = FrameRateCap.UNCAPPED,
	val animationIdleTimeout: AnimationIdleTimeout = AnimationIdleTimeout.NEVER,
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
	val moonSizeScale: Float = 1f,
	val sunColorPreset: SunColorPreset = SunColorPreset.NATURAL,
	val sunStyle: SunStyle = SunStyle.CLASSIC,
	val sunbeamMode: SunbeamMode = SunbeamMode.THROUGH_CLOUDS,
	val lensFlareEnabled: Boolean = true,
	val rainbowSizeScale: Float = 1f,
	val lensFlareMotionEnabled: Boolean = false,
	val distantAircraftEnabled: Boolean = true,
	val birdFlockSize: BirdFlockSize = BirdFlockSize.SMALL,
	val birdAppearanceInterval: BirdAppearanceInterval = BirdAppearanceInterval.NATURAL,
	val sceneSimulatorEnabled: Boolean = false,
	val sceneSimulatorActive: Boolean = false,
	val sceneSimulatorPresetIndex: Int = 0,
	val sceneSimulatorDayPhase: DayPhase = DayPhase.DAY,
	val sceneSimulatorCelestialProgress: Float = 0.5f,
	val glassDropletsEnabled: Boolean = false,
	val treesEnabled: Boolean = false,
	val treeStyle: TreeStyle = TreeStyle.EVERGREEN,
	val labelVerticalPosition: Float = DEFAULT_LABEL_VERTICAL_POSITION,
	val wallpaperDimmingStrength: Float = 0f,
	val statusBarShadeStrength: Float = 0f
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

/** Reset coordinate anchored to the historical fixed weather/location baselines. */
const val DEFAULT_LABEL_VERTICAL_POSITION = 0.09f

/** Bounds for the visible overlay block's full travel, with the reset coordinate preserving historical placement. */
val LABEL_VERTICAL_POSITION_RANGE = 0f..1f

/** Bounds for whole-scene wallpaper dimming and top status-bar shading. */
val READABILITY_STRENGTH_RANGE = 0f..0.6f

/** Normalizes a readability strength into [READABILITY_STRENGTH_RANGE], safely resolving non-finite values to off. */
fun normalizeReadabilityStrength(value: Float) = if (value.isFinite()) {
	value.coerceIn(READABILITY_STRENGTH_RANGE.start, READABILITY_STRENGTH_RANGE.endInclusive)
} else {
	0f
}

/** Bounds for Moon size multiplier offered in Settings. */
val MOON_SIZE_SCALE_RANGE = 0.5f..2f

/** Normalizes a Moon size scale into [MOON_SIZE_SCALE_RANGE], safely resolving non-finite values to 1f. */
fun normalizeMoonSizeScale(value: Float) = if (value.isFinite()) {
	value.coerceIn(MOON_SIZE_SCALE_RANGE.start, MOON_SIZE_SCALE_RANGE.endInclusive)
} else {
	1f
}

/** Bounds for the prismatic solar arc; other lens reflections keep their own geometry. */
val RAINBOW_SIZE_SCALE_RANGE = 0.6f..1.4f

/** Preserves the established arc size when a persisted multiplier is non-finite. */
fun normalizeRainbowSizeScale(value: Float) = if (value.isFinite()) {
	value.coerceIn(RAINBOW_SIZE_SCALE_RANGE.start, RAINBOW_SIZE_SCALE_RANGE.endInclusive)
} else {
	1f
}
