package xyz.attacktive.weatherd.domain.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import xyz.attacktive.weatherd.domain.model.AnimationIdleTimeout
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.AppearancePresetSnapshot
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.BirdAppearanceInterval
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.CLOUD_COUNT_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.CLOUD_CONTRAST_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.CLOUD_SIZE_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.FrameRateCap
import xyz.attacktive.weatherd.domain.model.LABEL_VERTICAL_POSITION_RANGE
import xyz.attacktive.weatherd.domain.model.READABILITY_STRENGTH_RANGE
import xyz.attacktive.weatherd.domain.model.normalizeReadabilityStrength
import xyz.attacktive.weatherd.domain.model.NIGHT_BRIGHTNESS_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SKY_BRIGHTNESS_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SKY_SATURATION_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SUN_SIZE_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.normalizeMoonSizeScale
import xyz.attacktive.weatherd.domain.model.normalizeRainbowSizeScale
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SunColorPreset
import xyz.attacktive.weatherd.domain.model.SunStyle
import xyz.attacktive.weatherd.domain.model.SunbeamMode
import xyz.attacktive.weatherd.domain.model.TemperatureUnit
import xyz.attacktive.weatherd.domain.model.TreeStyle
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.defaultAppSettings
import xyz.attacktive.weatherd.domain.model.definition


sealed interface SettingsMutation {
	data class WeatherProvider(val value: WeatherProviderType): SettingsMutation
	data class WeatherFallbackProvider(val value: WeatherProviderType): SettingsMutation
	data class UpdateIntervalMinutes(val value: Int): SettingsMutation
	data class UseDeviceLocation(val enabled: Boolean): SettingsMutation
	data class ManualLocation(val latitude: Double, val longitude: Double, val label: String?): SettingsMutation
	data object ClearManualLocation: SettingsMutation
	data class Backdrop(val value: BackdropScene): SettingsMutation
	data class ShowWeatherLabel(val enabled: Boolean): SettingsMutation
	data class ShowLocationLabel(val enabled: Boolean): SettingsMutation
	data class TemperatureUnitValue(val value: TemperatureUnit): SettingsMutation
	data class FrameRate(val value: FrameRateCap): SettingsMutation
	data class AnimationIdleTimeoutValue(val value: AnimationIdleTimeout): SettingsMutation
	data class WallpaperScrolling(val enabled: Boolean): SettingsMutation
	data class PrecipitationIntensity(val value: Float): SettingsMutation
	data class GlassDropletsEnabled(val enabled: Boolean): SettingsMutation
	data class TreesEnabled(val enabled: Boolean): SettingsMutation
	data class TreeStyle(val value: xyz.attacktive.weatherd.domain.model.TreeStyle): SettingsMutation
	data class WindIntensity(val value: Float): SettingsMutation
	data class CloudIntensity(val value: Float): SettingsMutation
	data class CloudSize(val value: Float): SettingsMutation
	data class CloudCount(val value: Float): SettingsMutation
	data class CloudContrast(val value: Float): SettingsMutation
	data class SkyBrightness(val value: Float): SettingsMutation
	data class NightBrightness(val value: Float): SettingsMutation
	data class SkySaturation(val value: Float): SettingsMutation
	data class SkyColor(val value: SkyColorPreset): SettingsMutation
	data class SunVisible(val visible: Boolean): SettingsMutation
	data class MoonVisible(val visible: Boolean): SettingsMutation
	data class SunSize(val value: Float): SettingsMutation
	data class MoonSize(val value: Float): SettingsMutation
	data class SunColor(val value: SunColorPreset): SettingsMutation
	data class SunStyle(val value: xyz.attacktive.weatherd.domain.model.SunStyle): SettingsMutation
	data class SunbeamModeMutation(val value: xyz.attacktive.weatherd.domain.model.SunbeamMode): SettingsMutation
	data class LensFlareEnabled(val enabled: Boolean): SettingsMutation
	data class RainbowSize(val value: Float): SettingsMutation
	data class LensFlareMotionEnabled(val enabled: Boolean): SettingsMutation
	data class DistantAircraftEnabled(val enabled: Boolean): SettingsMutation
	data class BirdFlockSize(val value: xyz.attacktive.weatherd.domain.model.BirdFlockSize): SettingsMutation
	data class BirdAppearanceInterval(val value: xyz.attacktive.weatherd.domain.model.BirdAppearanceInterval): SettingsMutation
	data class SceneSimulatorEnabled(val enabled: Boolean): SettingsMutation
	data class SceneSimulatorActive(val active: Boolean): SettingsMutation
	data class SceneSimulatorPresetIndex(val index: Int): SettingsMutation
	data class SceneSimulatorDayPhase(val dayPhase: DayPhase): SettingsMutation
	data class SceneSimulatorCelestialProgress(val progress: Float): SettingsMutation
	data class LabelVerticalPosition(val value: Float): SettingsMutation
	data class WallpaperDimmingStrength(val value: Float): SettingsMutation
	data class StatusBarShadeStrength(val value: Float): SettingsMutation
}


internal fun settingsMutationsBetween(previous: AppSettings, updated: AppSettings): List<SettingsMutation> {
	val mutations = buildList {
		addIfChanged(previous.weatherProvider, updated.weatherProvider, SettingsMutation::WeatherProvider)
		addIfChanged(previous.weatherFallbackProvider, updated.weatherFallbackProvider, SettingsMutation::WeatherFallbackProvider)
		addIfChanged(previous.updateIntervalMinutes, updated.updateIntervalMinutes, SettingsMutation::UpdateIntervalMinutes)
		addManualLocationChange(previous, updated)
		addIfChanged(previous.useDeviceLocation, updated.useDeviceLocation, SettingsMutation::UseDeviceLocation)
		addIfChanged(previous.backdropScene, updated.backdropScene, SettingsMutation::Backdrop)
		addIfChanged(previous.showWeatherLabel, updated.showWeatherLabel, SettingsMutation::ShowWeatherLabel)
		addIfChanged(previous.showLocationLabel, updated.showLocationLabel, SettingsMutation::ShowLocationLabel)
		addIfChanged(previous.temperatureUnit, updated.temperatureUnit, SettingsMutation::TemperatureUnitValue)
		addIfChanged(previous.frameRateCap, updated.frameRateCap, SettingsMutation::FrameRate)
		addIfChanged(previous.animationIdleTimeout, updated.animationIdleTimeout, SettingsMutation::AnimationIdleTimeoutValue)
		addIfChanged(previous.wallpaperScrollingEnabled, updated.wallpaperScrollingEnabled, SettingsMutation::WallpaperScrolling)
		addIfChanged(previous.precipitationIntensityScale, updated.precipitationIntensityScale, SettingsMutation::PrecipitationIntensity)
		addIfChanged(previous.glassDropletsEnabled, updated.glassDropletsEnabled, SettingsMutation::GlassDropletsEnabled)
		addIfChanged(previous.treesEnabled, updated.treesEnabled, SettingsMutation::TreesEnabled)
		addIfChanged(previous.treeStyle, updated.treeStyle, SettingsMutation::TreeStyle)
		addIfChanged(previous.windIntensityScale, updated.windIntensityScale, SettingsMutation::WindIntensity)
		addIfChanged(previous.cloudIntensityScale, updated.cloudIntensityScale, SettingsMutation::CloudIntensity)
		addIfChanged(previous.cloudSizeScale, updated.cloudSizeScale, SettingsMutation::CloudSize)
		addIfChanged(previous.cloudCountScale, updated.cloudCountScale, SettingsMutation::CloudCount)
		addIfChanged(previous.cloudContrastScale, updated.cloudContrastScale, SettingsMutation::CloudContrast)
		addIfChanged(previous.skyBrightnessScale, updated.skyBrightnessScale, SettingsMutation::SkyBrightness)
		addIfChanged(previous.nightBrightnessScale, updated.nightBrightnessScale, SettingsMutation::NightBrightness)
		addIfChanged(previous.skySaturationScale, updated.skySaturationScale, SettingsMutation::SkySaturation)
		addIfChanged(previous.skyColorPreset, updated.skyColorPreset, SettingsMutation::SkyColor)
		addIfChanged(previous.sunVisible, updated.sunVisible, SettingsMutation::SunVisible)
		addIfChanged(previous.moonVisible, updated.moonVisible, SettingsMutation::MoonVisible)
		addIfChanged(previous.sunSizeScale, updated.sunSizeScale, SettingsMutation::SunSize)
		addIfChanged(previous.moonSizeScale, updated.moonSizeScale, SettingsMutation::MoonSize)
		addIfChanged(previous.sunColorPreset, updated.sunColorPreset, SettingsMutation::SunColor)
		addIfChanged(previous.sunStyle, updated.sunStyle, SettingsMutation::SunStyle)
		addIfChanged(previous.sunbeamMode, updated.sunbeamMode, SettingsMutation::SunbeamModeMutation)
		addIfChanged(previous.lensFlareEnabled, updated.lensFlareEnabled, SettingsMutation::LensFlareEnabled)
		addIfChanged(previous.rainbowSizeScale, updated.rainbowSizeScale, SettingsMutation::RainbowSize)
		addIfChanged(previous.lensFlareMotionEnabled, updated.lensFlareMotionEnabled, SettingsMutation::LensFlareMotionEnabled)
		addIfChanged(previous.distantAircraftEnabled, updated.distantAircraftEnabled, SettingsMutation::DistantAircraftEnabled)
		addIfChanged(previous.birdFlockSize, updated.birdFlockSize, SettingsMutation::BirdFlockSize)
		addIfChanged(previous.birdAppearanceInterval, updated.birdAppearanceInterval, SettingsMutation::BirdAppearanceInterval)
		addIfChanged(previous.sceneSimulatorEnabled, updated.sceneSimulatorEnabled, SettingsMutation::SceneSimulatorEnabled)
		addIfChanged(previous.sceneSimulatorActive, updated.sceneSimulatorActive, SettingsMutation::SceneSimulatorActive)
		addIfChanged(previous.sceneSimulatorPresetIndex, updated.sceneSimulatorPresetIndex, SettingsMutation::SceneSimulatorPresetIndex)
		addIfChanged(previous.sceneSimulatorDayPhase, updated.sceneSimulatorDayPhase, SettingsMutation::SceneSimulatorDayPhase)
		addIfChanged(previous.sceneSimulatorCelestialProgress, updated.sceneSimulatorCelestialProgress, SettingsMutation::SceneSimulatorCelestialProgress)
		addIfChanged(previous.labelVerticalPosition, updated.labelVerticalPosition, SettingsMutation::LabelVerticalPosition)
		addIfChanged(previous.wallpaperDimmingStrength, updated.wallpaperDimmingStrength, SettingsMutation::WallpaperDimmingStrength)
		addIfChanged(previous.statusBarShadeStrength, updated.statusBarShadeStrength, SettingsMutation::StatusBarShadeStrength)
	}

	val replayed = mutations.fold(previous) { settings, mutation ->
		mutation.appliedTo(settings)
	}
	check(replayed == updated) { "Settings mutation mapping is incomplete" }

	return mutations
}

private fun MutableList<SettingsMutation>.addManualLocationChange(previous: AppSettings, updated: AppSettings) {
	val changed = previous.manualLatitude != updated.manualLatitude || previous.manualLongitude != updated.manualLongitude || previous.manualLocationLabel != updated.manualLocationLabel
	if (!changed) {
		return
	}

	if (updated.manualLatitude == null && updated.manualLongitude == null && updated.manualLocationLabel == null) {
		add(SettingsMutation.ClearManualLocation)

		return
	}

	add(
		SettingsMutation.ManualLocation(
			latitude = requireNotNull(updated.manualLatitude),
			longitude = requireNotNull(updated.manualLongitude),
			label = updated.manualLocationLabel
		)
	)
}

private fun <T> MutableList<SettingsMutation>.addIfChanged(previous: T, updated: T, mutation: (T) -> SettingsMutation) {
	if (previous != updated) {
		add(mutation(updated))
	}
}

private fun SettingsMutation.appliedTo(settings: AppSettings) = when (this) {
	is SettingsMutation.WeatherProvider -> settings.copy(weatherProvider = value)
	is SettingsMutation.WeatherFallbackProvider -> settings.copy(weatherFallbackProvider = value)
	is SettingsMutation.UpdateIntervalMinutes -> settings.copy(updateIntervalMinutes = value)
	is SettingsMutation.UseDeviceLocation -> settings.copy(useDeviceLocation = enabled)
	is SettingsMutation.ManualLocation -> settings.copy(useDeviceLocation = false, manualLatitude = latitude, manualLongitude = longitude, manualLocationLabel = label)
	SettingsMutation.ClearManualLocation -> settings.copy(manualLatitude = null, manualLongitude = null, manualLocationLabel = null)
	is SettingsMutation.Backdrop -> settings.copy(backdropScene = value)
	is SettingsMutation.ShowWeatherLabel -> settings.copy(showWeatherLabel = enabled)
	is SettingsMutation.ShowLocationLabel -> settings.copy(showLocationLabel = enabled)
	is SettingsMutation.TemperatureUnitValue -> settings.copy(temperatureUnit = value)
	is SettingsMutation.FrameRate -> settings.copy(frameRateCap = value)
	is SettingsMutation.AnimationIdleTimeoutValue -> settings.copy(animationIdleTimeout = value)
	is SettingsMutation.WallpaperScrolling -> settings.copy(wallpaperScrollingEnabled = enabled)
	is SettingsMutation.PrecipitationIntensity -> settings.copy(precipitationIntensityScale = value)
	is SettingsMutation.GlassDropletsEnabled -> settings.copy(glassDropletsEnabled = enabled)
	is SettingsMutation.TreesEnabled -> settings.copy(treesEnabled = enabled)
	is SettingsMutation.TreeStyle -> settings.copy(treeStyle = value)
	is SettingsMutation.WindIntensity -> settings.copy(windIntensityScale = value)
	is SettingsMutation.CloudIntensity -> settings.copy(cloudIntensityScale = value)
	is SettingsMutation.CloudSize -> settings.copy(cloudSizeScale = value)
	is SettingsMutation.CloudCount -> settings.copy(cloudCountScale = value)
	is SettingsMutation.CloudContrast -> settings.copy(cloudContrastScale = value)
	is SettingsMutation.SkyBrightness -> settings.copy(skyBrightnessScale = value)
	is SettingsMutation.NightBrightness -> settings.copy(nightBrightnessScale = value)
	is SettingsMutation.SkySaturation -> settings.copy(skySaturationScale = value)
	is SettingsMutation.SkyColor -> settings.copy(skyColorPreset = value)
	is SettingsMutation.SunVisible -> settings.copy(sunVisible = visible)
	is SettingsMutation.MoonVisible -> settings.copy(moonVisible = visible)
	is SettingsMutation.SunSize -> settings.copy(sunSizeScale = value)
	is SettingsMutation.MoonSize -> settings.copy(moonSizeScale = value)
	is SettingsMutation.SunColor -> settings.copy(sunColorPreset = value)
	is SettingsMutation.SunStyle -> settings.copy(sunStyle = value)
	is SettingsMutation.SunbeamModeMutation -> settings.copy(sunbeamMode = value)
	is SettingsMutation.LensFlareEnabled -> settings.copy(lensFlareEnabled = enabled)
	is SettingsMutation.RainbowSize -> settings.copy(rainbowSizeScale = value)
	is SettingsMutation.LensFlareMotionEnabled -> settings.copy(lensFlareMotionEnabled = enabled)
	is SettingsMutation.DistantAircraftEnabled -> settings.copy(distantAircraftEnabled = enabled)
	is SettingsMutation.BirdFlockSize -> settings.copy(birdFlockSize = value)
	is SettingsMutation.BirdAppearanceInterval -> settings.copy(birdAppearanceInterval = value)
	is SettingsMutation.SceneSimulatorEnabled -> settings.copy(sceneSimulatorEnabled = enabled, sceneSimulatorActive = settings.sceneSimulatorActive && enabled)
	is SettingsMutation.SceneSimulatorActive -> settings.copy(sceneSimulatorActive = active)
	is SettingsMutation.SceneSimulatorPresetIndex -> settings.copy(sceneSimulatorPresetIndex = index.coerceAtLeast(0))
	is SettingsMutation.SceneSimulatorDayPhase -> settings.copy(sceneSimulatorDayPhase = dayPhase)
	is SettingsMutation.SceneSimulatorCelestialProgress -> settings.copy(sceneSimulatorCelestialProgress = progress.coerceIn(0f, 1f))
	is SettingsMutation.LabelVerticalPosition -> settings.copy(labelVerticalPosition = value.coerceIn(LABEL_VERTICAL_POSITION_RANGE.start, LABEL_VERTICAL_POSITION_RANGE.endInclusive))
	is SettingsMutation.WallpaperDimmingStrength -> settings.copy(wallpaperDimmingStrength = normalizeReadabilityStrength(value))
	is SettingsMutation.StatusBarShadeStrength -> settings.copy(statusBarShadeStrength = normalizeReadabilityStrength(value))
}

/** Persists [AppSettings] to a DataStore; defaults are resolved once when the repository is created and reused for absent keys. */
@Singleton
class SettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {
	val defaults = defaultAppSettings()

	private object Keys {
		val WEATHER_PROVIDER = stringPreferencesKey("weather_provider")
		val WEATHER_FALLBACK_PROVIDER = stringPreferencesKey("weather_fallback_provider")
		val UPDATE_INTERVAL_MINUTES = intPreferencesKey("update_interval_minutes")
		val USE_DEVICE_LOCATION = booleanPreferencesKey("use_device_location")
		val MANUAL_LATITUDE = doublePreferencesKey("manual_latitude")
		val MANUAL_LONGITUDE = doublePreferencesKey("manual_longitude")
		val MANUAL_LOCATION_LABEL = stringPreferencesKey("manual_location_label")
		val BACKDROP_SCENE = stringPreferencesKey("backdrop_scene")
		val SHOW_WEATHER_LABEL = booleanPreferencesKey("show_weather_label")
		val SHOW_LOCATION_LABEL = booleanPreferencesKey("show_location_label")
		val TEMPERATURE_UNIT = stringPreferencesKey("temperature_unit")
		val FRAME_RATE_CAP = stringPreferencesKey("frame_rate_cap")
		val ANIMATION_IDLE_TIMEOUT = stringPreferencesKey("animation_idle_timeout")
		val WALLPAPER_SCROLLING_ENABLED = booleanPreferencesKey("wallpaper_scrolling_enabled")
		val PRECIPITATION_INTENSITY_SCALE = floatPreferencesKey("precipitation_intensity_scale")
		val GLASS_DROPLETS_ENABLED = booleanPreferencesKey("glass_droplets_enabled")
		val TREES_ENABLED = booleanPreferencesKey("trees_enabled")
		val TREE_STYLE = stringPreferencesKey("tree_style")
		val WIND_INTENSITY_SCALE = floatPreferencesKey("wind_intensity_scale")
		val CLOUD_INTENSITY_SCALE = floatPreferencesKey("cloud_intensity_scale")
		val CLOUD_SIZE_SCALE = floatPreferencesKey("cloud_size_scale")
		val CLOUD_COUNT_SCALE = floatPreferencesKey("cloud_count_scale")
		val CLOUD_CONTRAST_SCALE = floatPreferencesKey("cloud_contrast_scale")
		val SKY_BRIGHTNESS_SCALE = floatPreferencesKey("sky_brightness_scale")
		val NIGHT_BRIGHTNESS_SCALE = floatPreferencesKey("night_brightness_scale")
		val SKY_SATURATION_SCALE = floatPreferencesKey("sky_saturation_scale")
		val SKY_COLOR_PRESET = stringPreferencesKey("sky_color_preset")
		val SUN_VISIBLE = booleanPreferencesKey("sun_visible")
		val MOON_VISIBLE = booleanPreferencesKey("moon_visible")
		val SUN_SIZE_SCALE = floatPreferencesKey("sun_size_scale")
		val MOON_SIZE_SCALE = floatPreferencesKey("moon_size_scale")
		val SUN_COLOR_PRESET = stringPreferencesKey("sun_color_preset")
		val SUN_STYLE = stringPreferencesKey("sun_style")
		val SUNBEAM_MODE = stringPreferencesKey("sunbeam_mode")
		val LENS_FLARE_ENABLED = booleanPreferencesKey("lens_flare_enabled")
		val RAINBOW_SIZE_SCALE = floatPreferencesKey("rainbow_size_scale")
		val LENS_FLARE_MOTION_ENABLED = booleanPreferencesKey("lens_flare_motion_enabled")
		val DISTANT_AIRCRAFT_ENABLED = booleanPreferencesKey("distant_aircraft_enabled")
		val BIRD_FLOCK_SIZE = stringPreferencesKey("bird_flock_size")
		val BIRD_APPEARANCE_INTERVAL = stringPreferencesKey("bird_appearance_interval")
		val SCENE_SIMULATOR_ENABLED = booleanPreferencesKey("scene_simulator_enabled")
		val SCENE_SIMULATOR_ACTIVE = booleanPreferencesKey("scene_simulator_active")
		val SCENE_SIMULATOR_PRESET_INDEX = intPreferencesKey("scene_simulator_preset_index")
		val SCENE_SIMULATOR_DAY_PHASE = stringPreferencesKey("scene_simulator_day_phase")
		val SCENE_SIMULATOR_CELESTIAL_PROGRESS = floatPreferencesKey("scene_simulator_celestial_progress")
		val LABEL_VERTICAL_POSITION = floatPreferencesKey("label_vertical_position")
		val WALLPAPER_DIMMING_STRENGTH = floatPreferencesKey("wallpaper_dimming_strength")
		val STATUS_BAR_SHADE_STRENGTH = floatPreferencesKey("status_bar_shade_strength")
	}

	val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
		AppSettings(
			weatherProvider = enumOrDefault(preferences[Keys.WEATHER_PROVIDER], WeatherProviderType.entries, defaults.weatherProvider),
			weatherFallbackProvider = globalWeatherProviderOrDefault(preferences[Keys.WEATHER_FALLBACK_PROVIDER], defaults.weatherFallbackProvider),
			updateIntervalMinutes = preferences[Keys.UPDATE_INTERVAL_MINUTES] ?: defaults.updateIntervalMinutes,
			useDeviceLocation = preferences[Keys.USE_DEVICE_LOCATION] ?: defaults.useDeviceLocation,
			manualLatitude = preferences[Keys.MANUAL_LATITUDE],
			manualLongitude = preferences[Keys.MANUAL_LONGITUDE],
			manualLocationLabel = preferences[Keys.MANUAL_LOCATION_LABEL],
			backdropScene = enumOrDefault(preferences[Keys.BACKDROP_SCENE], BackdropScene.entries, defaults.backdropScene),
			showWeatherLabel = preferences[Keys.SHOW_WEATHER_LABEL] ?: defaults.showWeatherLabel,
			showLocationLabel = preferences[Keys.SHOW_LOCATION_LABEL] ?: defaults.showLocationLabel,
			temperatureUnit = enumOrDefault(preferences[Keys.TEMPERATURE_UNIT], TemperatureUnit.entries, defaults.temperatureUnit),
			frameRateCap = enumOrDefault(preferences[Keys.FRAME_RATE_CAP], FrameRateCap.entries, defaults.frameRateCap),
			animationIdleTimeout = enumOrDefault(preferences[Keys.ANIMATION_IDLE_TIMEOUT], AnimationIdleTimeout.entries, defaults.animationIdleTimeout),
			wallpaperScrollingEnabled = preferences[Keys.WALLPAPER_SCROLLING_ENABLED] ?: defaults.wallpaperScrollingEnabled,
			precipitationIntensityScale = preferences[Keys.PRECIPITATION_INTENSITY_SCALE] ?: defaults.precipitationIntensityScale,
			glassDropletsEnabled = preferences[Keys.GLASS_DROPLETS_ENABLED] ?: defaults.glassDropletsEnabled,
			treesEnabled = preferences[Keys.TREES_ENABLED] ?: defaults.treesEnabled,
			treeStyle = enumOrDefault(preferences[Keys.TREE_STYLE], TreeStyle.entries, defaults.treeStyle),
			windIntensityScale = preferences[Keys.WIND_INTENSITY_SCALE] ?: defaults.windIntensityScale,
			cloudIntensityScale = preferences[Keys.CLOUD_INTENSITY_SCALE] ?: defaults.cloudIntensityScale,
			cloudSizeScale = (preferences[Keys.CLOUD_SIZE_SCALE] ?: defaults.cloudSizeScale).coerceIn(CLOUD_SIZE_SCALE_RANGE.start, CLOUD_SIZE_SCALE_RANGE.endInclusive),
			cloudCountScale = (preferences[Keys.CLOUD_COUNT_SCALE] ?: defaults.cloudCountScale).coerceIn(CLOUD_COUNT_SCALE_RANGE.start, CLOUD_COUNT_SCALE_RANGE.endInclusive),
			cloudContrastScale = (preferences[Keys.CLOUD_CONTRAST_SCALE] ?: defaults.cloudContrastScale).coerceIn(CLOUD_CONTRAST_SCALE_RANGE.start, CLOUD_CONTRAST_SCALE_RANGE.endInclusive),
			skyBrightnessScale = (preferences[Keys.SKY_BRIGHTNESS_SCALE] ?: defaults.skyBrightnessScale).coerceIn(SKY_BRIGHTNESS_SCALE_RANGE.start, SKY_BRIGHTNESS_SCALE_RANGE.endInclusive),
			nightBrightnessScale = (preferences[Keys.NIGHT_BRIGHTNESS_SCALE] ?: defaults.nightBrightnessScale).coerceIn(NIGHT_BRIGHTNESS_SCALE_RANGE.start, NIGHT_BRIGHTNESS_SCALE_RANGE.endInclusive),
			skySaturationScale = (preferences[Keys.SKY_SATURATION_SCALE] ?: defaults.skySaturationScale).coerceIn(SKY_SATURATION_SCALE_RANGE.start, SKY_SATURATION_SCALE_RANGE.endInclusive),
			skyColorPreset = enumOrDefault(preferences[Keys.SKY_COLOR_PRESET], SkyColorPreset.entries, defaults.skyColorPreset),
			sunVisible = preferences[Keys.SUN_VISIBLE] ?: defaults.sunVisible,
			moonVisible = preferences[Keys.MOON_VISIBLE] ?: defaults.moonVisible,
			sunSizeScale = (preferences[Keys.SUN_SIZE_SCALE] ?: defaults.sunSizeScale).coerceIn(SUN_SIZE_SCALE_RANGE.start, SUN_SIZE_SCALE_RANGE.endInclusive),
			moonSizeScale = normalizeMoonSizeScale(preferences[Keys.MOON_SIZE_SCALE] ?: defaults.moonSizeScale),
			sunColorPreset = enumOrDefault(preferences[Keys.SUN_COLOR_PRESET], SunColorPreset.entries, defaults.sunColorPreset),
			sunStyle = enumOrDefault(preferences[Keys.SUN_STYLE], SunStyle.entries, defaults.sunStyle),
			sunbeamMode = enumOrDefault(preferences[Keys.SUNBEAM_MODE], SunbeamMode.entries, defaults.sunbeamMode),
			lensFlareEnabled = preferences[Keys.LENS_FLARE_ENABLED] ?: defaults.lensFlareEnabled,
			rainbowSizeScale = normalizeRainbowSizeScale(preferences[Keys.RAINBOW_SIZE_SCALE] ?: defaults.rainbowSizeScale),
			lensFlareMotionEnabled = preferences[Keys.LENS_FLARE_MOTION_ENABLED] ?: defaults.lensFlareMotionEnabled,
			distantAircraftEnabled = preferences[Keys.DISTANT_AIRCRAFT_ENABLED] ?: defaults.distantAircraftEnabled,
			birdFlockSize = enumOrDefault(preferences[Keys.BIRD_FLOCK_SIZE], BirdFlockSize.entries, defaults.birdFlockSize),
			birdAppearanceInterval = enumOrDefault(preferences[Keys.BIRD_APPEARANCE_INTERVAL], BirdAppearanceInterval.entries, defaults.birdAppearanceInterval),
			sceneSimulatorEnabled = preferences[Keys.SCENE_SIMULATOR_ENABLED] ?: defaults.sceneSimulatorEnabled,
			sceneSimulatorActive = preferences[Keys.SCENE_SIMULATOR_ACTIVE] ?: defaults.sceneSimulatorActive,
			sceneSimulatorPresetIndex = (preferences[Keys.SCENE_SIMULATOR_PRESET_INDEX] ?: defaults.sceneSimulatorPresetIndex).coerceAtLeast(0),
			sceneSimulatorDayPhase = enumOrDefault(preferences[Keys.SCENE_SIMULATOR_DAY_PHASE], DayPhase.entries, defaults.sceneSimulatorDayPhase),
			sceneSimulatorCelestialProgress = (preferences[Keys.SCENE_SIMULATOR_CELESTIAL_PROGRESS] ?: defaults.sceneSimulatorCelestialProgress).coerceIn(0f, 1f),
			labelVerticalPosition = (preferences[Keys.LABEL_VERTICAL_POSITION] ?: defaults.labelVerticalPosition).coerceIn(LABEL_VERTICAL_POSITION_RANGE.start, LABEL_VERTICAL_POSITION_RANGE.endInclusive),
			wallpaperDimmingStrength = normalizeReadabilityStrength(preferences[Keys.WALLPAPER_DIMMING_STRENGTH] ?: defaults.wallpaperDimmingStrength),
			statusBarShadeStrength = normalizeReadabilityStrength(preferences[Keys.STATUS_BAR_SHADE_STRENGTH] ?: defaults.statusBarShadeStrength)
		)
	}


	suspend fun update(mutations: List<SettingsMutation>) {
		if (mutations.isEmpty()) {
			return
		}

		dataStore.edit { preferences ->
			mutations.forEach { mutation ->
				preferences.applyMutation(mutation)
			}
		}
	}

	suspend fun applyAppearancePreset(snapshot: AppearancePresetSnapshot) {
		dataStore.edit { preferences ->
			preferences.writeAppearancePreset(snapshot)
		}
	}

	internal suspend fun save(settings: AppSettings) {
		dataStore.edit { preferences ->
			preferences[Keys.WEATHER_PROVIDER] = settings.weatherProvider.name
			preferences[Keys.WEATHER_FALLBACK_PROVIDER] = settings.weatherFallbackProvider.takeIf { it.definition.isGlobal }?.name ?: defaults.weatherFallbackProvider.name
			preferences[Keys.UPDATE_INTERVAL_MINUTES] = settings.updateIntervalMinutes
			preferences[Keys.USE_DEVICE_LOCATION] = settings.useDeviceLocation
			preferences.putOrRemove(Keys.MANUAL_LATITUDE, settings.manualLatitude)
			preferences.putOrRemove(Keys.MANUAL_LONGITUDE, settings.manualLongitude)
			preferences.putOrRemove(Keys.MANUAL_LOCATION_LABEL, settings.manualLocationLabel)
			preferences[Keys.BACKDROP_SCENE] = settings.backdropScene.name
			preferences[Keys.SHOW_WEATHER_LABEL] = settings.showWeatherLabel
			preferences[Keys.SHOW_LOCATION_LABEL] = settings.showLocationLabel
			preferences[Keys.TEMPERATURE_UNIT] = settings.temperatureUnit.name
			preferences[Keys.FRAME_RATE_CAP] = settings.frameRateCap.name
			preferences[Keys.ANIMATION_IDLE_TIMEOUT] = settings.animationIdleTimeout.name
			preferences[Keys.WALLPAPER_SCROLLING_ENABLED] = settings.wallpaperScrollingEnabled
			preferences[Keys.PRECIPITATION_INTENSITY_SCALE] = settings.precipitationIntensityScale
			preferences[Keys.GLASS_DROPLETS_ENABLED] = settings.glassDropletsEnabled
			preferences[Keys.TREES_ENABLED] = settings.treesEnabled
			preferences[Keys.TREE_STYLE] = settings.treeStyle.name
			preferences[Keys.WIND_INTENSITY_SCALE] = settings.windIntensityScale
			preferences[Keys.CLOUD_INTENSITY_SCALE] = settings.cloudIntensityScale
			preferences[Keys.CLOUD_SIZE_SCALE] = settings.cloudSizeScale.coerceIn(CLOUD_SIZE_SCALE_RANGE.start, CLOUD_SIZE_SCALE_RANGE.endInclusive)
			preferences[Keys.CLOUD_COUNT_SCALE] = settings.cloudCountScale.coerceIn(CLOUD_COUNT_SCALE_RANGE.start, CLOUD_COUNT_SCALE_RANGE.endInclusive)
			preferences[Keys.CLOUD_CONTRAST_SCALE] = settings.cloudContrastScale.coerceIn(CLOUD_CONTRAST_SCALE_RANGE.start, CLOUD_CONTRAST_SCALE_RANGE.endInclusive)
			preferences[Keys.SKY_BRIGHTNESS_SCALE] = settings.skyBrightnessScale.coerceIn(SKY_BRIGHTNESS_SCALE_RANGE.start, SKY_BRIGHTNESS_SCALE_RANGE.endInclusive)
			preferences[Keys.NIGHT_BRIGHTNESS_SCALE] = settings.nightBrightnessScale.coerceIn(NIGHT_BRIGHTNESS_SCALE_RANGE.start, NIGHT_BRIGHTNESS_SCALE_RANGE.endInclusive)
			preferences[Keys.SKY_SATURATION_SCALE] = settings.skySaturationScale.coerceIn(SKY_SATURATION_SCALE_RANGE.start, SKY_SATURATION_SCALE_RANGE.endInclusive)
			preferences[Keys.SKY_COLOR_PRESET] = settings.skyColorPreset.name
			preferences[Keys.SUN_VISIBLE] = settings.sunVisible
			preferences[Keys.MOON_VISIBLE] = settings.moonVisible
			preferences[Keys.SUN_SIZE_SCALE] = settings.sunSizeScale.coerceIn(SUN_SIZE_SCALE_RANGE.start, SUN_SIZE_SCALE_RANGE.endInclusive)
			preferences[Keys.MOON_SIZE_SCALE] = normalizeMoonSizeScale(settings.moonSizeScale)
			preferences[Keys.SUN_COLOR_PRESET] = settings.sunColorPreset.name
			preferences[Keys.SUN_STYLE] = settings.sunStyle.name
			preferences[Keys.SUNBEAM_MODE] = settings.sunbeamMode.name
			preferences[Keys.LENS_FLARE_ENABLED] = settings.lensFlareEnabled
			preferences[Keys.RAINBOW_SIZE_SCALE] = normalizeRainbowSizeScale(settings.rainbowSizeScale)
			preferences[Keys.LENS_FLARE_MOTION_ENABLED] = settings.lensFlareMotionEnabled
			preferences[Keys.DISTANT_AIRCRAFT_ENABLED] = settings.distantAircraftEnabled
			preferences[Keys.BIRD_FLOCK_SIZE] = settings.birdFlockSize.name
			preferences[Keys.BIRD_APPEARANCE_INTERVAL] = settings.birdAppearanceInterval.name
			preferences[Keys.SCENE_SIMULATOR_ENABLED] = settings.sceneSimulatorEnabled
			preferences[Keys.SCENE_SIMULATOR_ACTIVE] = settings.sceneSimulatorActive
			preferences[Keys.SCENE_SIMULATOR_PRESET_INDEX] = settings.sceneSimulatorPresetIndex
			preferences[Keys.SCENE_SIMULATOR_DAY_PHASE] = settings.sceneSimulatorDayPhase.name
			preferences[Keys.SCENE_SIMULATOR_CELESTIAL_PROGRESS] = settings.sceneSimulatorCelestialProgress.coerceIn(0f, 1f)
			preferences[Keys.LABEL_VERTICAL_POSITION] = settings.labelVerticalPosition.coerceIn(LABEL_VERTICAL_POSITION_RANGE.start, LABEL_VERTICAL_POSITION_RANGE.endInclusive)
			preferences[Keys.WALLPAPER_DIMMING_STRENGTH] = normalizeReadabilityStrength(settings.wallpaperDimmingStrength)
			preferences[Keys.STATUS_BAR_SHADE_STRENGTH] = normalizeReadabilityStrength(settings.statusBarShadeStrength)
		}
	}

	private fun MutablePreferences.applyMutation(mutation: SettingsMutation) {
		when (mutation) {
			is SettingsMutation.WeatherProvider -> this[Keys.WEATHER_PROVIDER] = mutation.value.name
			is SettingsMutation.WeatherFallbackProvider -> this[Keys.WEATHER_FALLBACK_PROVIDER] = mutation.value.takeIf { it.definition.isGlobal }?.name ?: defaults.weatherFallbackProvider.name
			is SettingsMutation.UpdateIntervalMinutes -> this[Keys.UPDATE_INTERVAL_MINUTES] = mutation.value
			is SettingsMutation.UseDeviceLocation -> this[Keys.USE_DEVICE_LOCATION] = mutation.enabled
			is SettingsMutation.ManualLocation -> {
				this[Keys.USE_DEVICE_LOCATION] = false
				this[Keys.MANUAL_LATITUDE] = mutation.latitude
				this[Keys.MANUAL_LONGITUDE] = mutation.longitude
				putOrRemove(Keys.MANUAL_LOCATION_LABEL, mutation.label)
			}
			SettingsMutation.ClearManualLocation -> {
				remove(Keys.MANUAL_LATITUDE)
				remove(Keys.MANUAL_LONGITUDE)
				remove(Keys.MANUAL_LOCATION_LABEL)
			}
			is SettingsMutation.Backdrop -> this[Keys.BACKDROP_SCENE] = mutation.value.name
			is SettingsMutation.ShowWeatherLabel -> this[Keys.SHOW_WEATHER_LABEL] = mutation.enabled
			is SettingsMutation.ShowLocationLabel -> this[Keys.SHOW_LOCATION_LABEL] = mutation.enabled
			is SettingsMutation.TemperatureUnitValue -> this[Keys.TEMPERATURE_UNIT] = mutation.value.name
			is SettingsMutation.FrameRate -> this[Keys.FRAME_RATE_CAP] = mutation.value.name
			is SettingsMutation.AnimationIdleTimeoutValue -> this[Keys.ANIMATION_IDLE_TIMEOUT] = mutation.value.name
			is SettingsMutation.WallpaperScrolling -> this[Keys.WALLPAPER_SCROLLING_ENABLED] = mutation.enabled
			is SettingsMutation.PrecipitationIntensity -> this[Keys.PRECIPITATION_INTENSITY_SCALE] = mutation.value
			is SettingsMutation.GlassDropletsEnabled -> this[Keys.GLASS_DROPLETS_ENABLED] = mutation.enabled
			is SettingsMutation.TreesEnabled -> this[Keys.TREES_ENABLED] = mutation.enabled
			is SettingsMutation.TreeStyle -> this[Keys.TREE_STYLE] = mutation.value.name
			is SettingsMutation.WindIntensity -> this[Keys.WIND_INTENSITY_SCALE] = mutation.value
			is SettingsMutation.CloudIntensity -> this[Keys.CLOUD_INTENSITY_SCALE] = mutation.value
			is SettingsMutation.CloudSize -> this[Keys.CLOUD_SIZE_SCALE] = mutation.value.coerceIn(CLOUD_SIZE_SCALE_RANGE.start, CLOUD_SIZE_SCALE_RANGE.endInclusive)
			is SettingsMutation.CloudCount -> this[Keys.CLOUD_COUNT_SCALE] = mutation.value.coerceIn(CLOUD_COUNT_SCALE_RANGE.start, CLOUD_COUNT_SCALE_RANGE.endInclusive)
			is SettingsMutation.CloudContrast -> this[Keys.CLOUD_CONTRAST_SCALE] = mutation.value.coerceIn(CLOUD_CONTRAST_SCALE_RANGE.start, CLOUD_CONTRAST_SCALE_RANGE.endInclusive)
			is SettingsMutation.SkyBrightness -> this[Keys.SKY_BRIGHTNESS_SCALE] = mutation.value.coerceIn(SKY_BRIGHTNESS_SCALE_RANGE.start, SKY_BRIGHTNESS_SCALE_RANGE.endInclusive)
			is SettingsMutation.NightBrightness -> this[Keys.NIGHT_BRIGHTNESS_SCALE] = mutation.value.coerceIn(NIGHT_BRIGHTNESS_SCALE_RANGE.start, NIGHT_BRIGHTNESS_SCALE_RANGE.endInclusive)
			is SettingsMutation.SkySaturation -> this[Keys.SKY_SATURATION_SCALE] = mutation.value.coerceIn(SKY_SATURATION_SCALE_RANGE.start, SKY_SATURATION_SCALE_RANGE.endInclusive)
			is SettingsMutation.SkyColor -> this[Keys.SKY_COLOR_PRESET] = mutation.value.name
			is SettingsMutation.SunVisible -> this[Keys.SUN_VISIBLE] = mutation.visible
			is SettingsMutation.MoonVisible -> this[Keys.MOON_VISIBLE] = mutation.visible
			is SettingsMutation.SunSize -> this[Keys.SUN_SIZE_SCALE] = mutation.value.coerceIn(SUN_SIZE_SCALE_RANGE.start, SUN_SIZE_SCALE_RANGE.endInclusive)
			is SettingsMutation.MoonSize -> this[Keys.MOON_SIZE_SCALE] = normalizeMoonSizeScale(mutation.value)
			is SettingsMutation.SunColor -> this[Keys.SUN_COLOR_PRESET] = mutation.value.name
			is SettingsMutation.SunStyle -> this[Keys.SUN_STYLE] = mutation.value.name
			is SettingsMutation.SunbeamModeMutation -> this[Keys.SUNBEAM_MODE] = mutation.value.name
			is SettingsMutation.LensFlareEnabled -> this[Keys.LENS_FLARE_ENABLED] = mutation.enabled
			is SettingsMutation.RainbowSize -> this[Keys.RAINBOW_SIZE_SCALE] = normalizeRainbowSizeScale(mutation.value)
			is SettingsMutation.LensFlareMotionEnabled -> this[Keys.LENS_FLARE_MOTION_ENABLED] = mutation.enabled
			is SettingsMutation.DistantAircraftEnabled -> this[Keys.DISTANT_AIRCRAFT_ENABLED] = mutation.enabled
			is SettingsMutation.BirdFlockSize -> this[Keys.BIRD_FLOCK_SIZE] = mutation.value.name
			is SettingsMutation.BirdAppearanceInterval -> this[Keys.BIRD_APPEARANCE_INTERVAL] = mutation.value.name
			is SettingsMutation.SceneSimulatorEnabled -> {
				this[Keys.SCENE_SIMULATOR_ENABLED] = mutation.enabled
				if (!mutation.enabled) {
					this[Keys.SCENE_SIMULATOR_ACTIVE] = false
				}
			}
			is SettingsMutation.SceneSimulatorActive -> this[Keys.SCENE_SIMULATOR_ACTIVE] = mutation.active
			is SettingsMutation.SceneSimulatorPresetIndex -> this[Keys.SCENE_SIMULATOR_PRESET_INDEX] = mutation.index.coerceAtLeast(0)
			is SettingsMutation.SceneSimulatorDayPhase -> this[Keys.SCENE_SIMULATOR_DAY_PHASE] = mutation.dayPhase.name
			is SettingsMutation.SceneSimulatorCelestialProgress -> this[Keys.SCENE_SIMULATOR_CELESTIAL_PROGRESS] = mutation.progress.coerceIn(0f, 1f)
			is SettingsMutation.LabelVerticalPosition -> this[Keys.LABEL_VERTICAL_POSITION] = mutation.value.coerceIn(LABEL_VERTICAL_POSITION_RANGE.start, LABEL_VERTICAL_POSITION_RANGE.endInclusive)
			is SettingsMutation.WallpaperDimmingStrength -> this[Keys.WALLPAPER_DIMMING_STRENGTH] = normalizeReadabilityStrength(mutation.value)
			is SettingsMutation.StatusBarShadeStrength -> this[Keys.STATUS_BAR_SHADE_STRENGTH] = normalizeReadabilityStrength(mutation.value)
		}
	}

	private fun MutablePreferences.writeAppearancePreset(snapshot: AppearancePresetSnapshot) {
		this[Keys.BACKDROP_SCENE] = snapshot.backdropScene.name
		this[Keys.SHOW_WEATHER_LABEL] = snapshot.showWeatherLabel
		this[Keys.SHOW_LOCATION_LABEL] = snapshot.showLocationLabel
		this[Keys.PRECIPITATION_INTENSITY_SCALE] = snapshot.precipitationIntensityScale
		this[Keys.GLASS_DROPLETS_ENABLED] = snapshot.glassDropletsEnabled
		this[Keys.TREES_ENABLED] = snapshot.treesEnabled
		this[Keys.TREE_STYLE] = snapshot.treeStyle.name
		this[Keys.WIND_INTENSITY_SCALE] = snapshot.windIntensityScale
		this[Keys.CLOUD_INTENSITY_SCALE] = snapshot.cloudIntensityScale
		this[Keys.CLOUD_SIZE_SCALE] = snapshot.cloudSizeScale.coerceIn(CLOUD_SIZE_SCALE_RANGE.start, CLOUD_SIZE_SCALE_RANGE.endInclusive)
		this[Keys.CLOUD_COUNT_SCALE] = snapshot.cloudCountScale.coerceIn(CLOUD_COUNT_SCALE_RANGE.start, CLOUD_COUNT_SCALE_RANGE.endInclusive)
		this[Keys.CLOUD_CONTRAST_SCALE] = snapshot.cloudContrastScale.coerceIn(CLOUD_CONTRAST_SCALE_RANGE.start, CLOUD_CONTRAST_SCALE_RANGE.endInclusive)
		this[Keys.SKY_BRIGHTNESS_SCALE] = snapshot.skyBrightnessScale.coerceIn(SKY_BRIGHTNESS_SCALE_RANGE.start, SKY_BRIGHTNESS_SCALE_RANGE.endInclusive)
		this[Keys.NIGHT_BRIGHTNESS_SCALE] = snapshot.nightBrightnessScale.coerceIn(NIGHT_BRIGHTNESS_SCALE_RANGE.start, NIGHT_BRIGHTNESS_SCALE_RANGE.endInclusive)
		this[Keys.SKY_SATURATION_SCALE] = snapshot.skySaturationScale.coerceIn(SKY_SATURATION_SCALE_RANGE.start, SKY_SATURATION_SCALE_RANGE.endInclusive)
		this[Keys.SKY_COLOR_PRESET] = snapshot.skyColorPreset.name
		this[Keys.SUN_VISIBLE] = snapshot.sunVisible
		this[Keys.MOON_VISIBLE] = snapshot.moonVisible
		this[Keys.SUN_SIZE_SCALE] = snapshot.sunSizeScale.coerceIn(SUN_SIZE_SCALE_RANGE.start, SUN_SIZE_SCALE_RANGE.endInclusive)
		this[Keys.MOON_SIZE_SCALE] = normalizeMoonSizeScale(snapshot.moonSizeScale)
		this[Keys.SUN_COLOR_PRESET] = snapshot.sunColorPreset.name
		this[Keys.SUN_STYLE] = snapshot.sunStyle.name
		this[Keys.SUNBEAM_MODE] = snapshot.sunbeamMode.name
		this[Keys.LENS_FLARE_ENABLED] = snapshot.lensFlareEnabled
		this[Keys.RAINBOW_SIZE_SCALE] = normalizeRainbowSizeScale(snapshot.rainbowSizeScale)
		this[Keys.LENS_FLARE_MOTION_ENABLED] = snapshot.lensFlareMotionEnabled
		this[Keys.DISTANT_AIRCRAFT_ENABLED] = snapshot.distantAircraftEnabled
		this[Keys.BIRD_FLOCK_SIZE] = snapshot.birdFlockSize.name
		this[Keys.BIRD_APPEARANCE_INTERVAL] = snapshot.birdAppearanceInterval.name
		this[Keys.LABEL_VERTICAL_POSITION] = snapshot.labelVerticalPosition.coerceIn(LABEL_VERTICAL_POSITION_RANGE.start, LABEL_VERTICAL_POSITION_RANGE.endInclusive)
		this[Keys.WALLPAPER_DIMMING_STRENGTH] = normalizeReadabilityStrength(snapshot.wallpaperDimmingStrength)
		this[Keys.STATUS_BAR_SHADE_STRENGTH] = normalizeReadabilityStrength(snapshot.statusBarShadeStrength)
	}

}

private fun globalWeatherProviderOrDefault(name: String?, default: WeatherProviderType): WeatherProviderType {
	val provider = enumOrDefault(name, WeatherProviderType.entries, default)
	return if (provider.definition.isGlobal) provider else default
}

private fun <T : Enum<T>> enumOrDefault(name: String?, values: Iterable<T>, default: T) = values.firstOrNull { it.name == name } ?: default

/** Writes [value] under [key], or clears the key when [value] is null, so cleared settings revert to their default on read. */
private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
	if (value != null) {
		this[key] = value
	} else {
		remove(key)
	}
}
