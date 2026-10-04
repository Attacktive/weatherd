package xyz.attacktive.weatherd.domain.model

data class SavedAppearancePreset(
	val id: String,
	val name: String,
	val snapshot: AppearancePresetSnapshot
)

data class AppearancePresetSnapshot(
	val backdropScene: BackdropScene,
	val showWeatherLabel: Boolean,
	val showLocationLabel: Boolean,
	val precipitationIntensityScale: Float,
	val windIntensityScale: Float,
	val cloudIntensityScale: Float,
	val cloudSizeScale: Float,
	val cloudCountScale: Float,
	val cloudContrastScale: Float,
	val skyBrightnessScale: Float,
	val nightBrightnessScale: Float,
	val skySaturationScale: Float,
	val skyColorPreset: SkyColorPreset,
	val sunVisible: Boolean,
	val moonVisible: Boolean,
	val sunSizeScale: Float,
	val sunColorPreset: SunColorPreset,
	val lensFlareEnabled: Boolean,
	val lensFlareMotionEnabled: Boolean
)

fun AppSettings.toAppearancePresetSnapshot() = AppearancePresetSnapshot(
	backdropScene = backdropScene,
	showWeatherLabel = showWeatherLabel,
	showLocationLabel = showLocationLabel,
	precipitationIntensityScale = precipitationIntensityScale,
	windIntensityScale = windIntensityScale,
	cloudIntensityScale = cloudIntensityScale,
	cloudSizeScale = cloudSizeScale,
	cloudCountScale = cloudCountScale,
	cloudContrastScale = cloudContrastScale,
	skyBrightnessScale = skyBrightnessScale,
	nightBrightnessScale = nightBrightnessScale,
	skySaturationScale = skySaturationScale,
	skyColorPreset = skyColorPreset,
	sunVisible = sunVisible,
	moonVisible = moonVisible,
	sunSizeScale = sunSizeScale,
	sunColorPreset = sunColorPreset,
	lensFlareEnabled = lensFlareEnabled,
	lensFlareMotionEnabled = lensFlareMotionEnabled
)

fun AppearancePresetSnapshot.appliedTo(settings: AppSettings) = settings.copy(
	backdropScene = backdropScene,
	showWeatherLabel = showWeatherLabel,
	showLocationLabel = showLocationLabel,
	precipitationIntensityScale = precipitationIntensityScale,
	windIntensityScale = windIntensityScale,
	cloudIntensityScale = cloudIntensityScale,
	cloudSizeScale = cloudSizeScale,
	cloudCountScale = cloudCountScale,
	cloudContrastScale = cloudContrastScale,
	skyBrightnessScale = skyBrightnessScale,
	nightBrightnessScale = nightBrightnessScale,
	skySaturationScale = skySaturationScale,
	skyColorPreset = skyColorPreset,
	sunVisible = sunVisible,
	moonVisible = moonVisible,
	sunSizeScale = sunSizeScale,
	sunColorPreset = sunColorPreset,
	lensFlareEnabled = lensFlareEnabled,
	lensFlareMotionEnabled = lensFlareMotionEnabled
)
