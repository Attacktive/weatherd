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
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.CLOUD_COUNT_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.CLOUD_CONTRAST_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.CLOUD_SIZE_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.FrameRateCap
import xyz.attacktive.weatherd.domain.model.SKY_BRIGHTNESS_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SKY_SATURATION_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SUN_SIZE_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SunColorPreset
import xyz.attacktive.weatherd.domain.model.TemperatureUnit
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.defaultAppSettings

/** Persists [AppSettings] to a DataStore; absent keys fall back to [defaultAppSettings] on read. */
@Singleton
class SettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {
	private val defaults = defaultAppSettings()

	private object Keys {
		val WEATHER_PROVIDER = stringPreferencesKey("weather_provider")
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
		val WALLPAPER_SCROLLING_ENABLED = booleanPreferencesKey("wallpaper_scrolling_enabled")
		val PRECIPITATION_INTENSITY_SCALE = floatPreferencesKey("precipitation_intensity_scale")
		val WIND_INTENSITY_SCALE = floatPreferencesKey("wind_intensity_scale")
		val CLOUD_INTENSITY_SCALE = floatPreferencesKey("cloud_intensity_scale")
		val CLOUD_SIZE_SCALE = floatPreferencesKey("cloud_size_scale")
		val CLOUD_COUNT_SCALE = floatPreferencesKey("cloud_count_scale")
		val CLOUD_CONTRAST_SCALE = floatPreferencesKey("cloud_contrast_scale")
		val SKY_BRIGHTNESS_SCALE = floatPreferencesKey("sky_brightness_scale")
		val SKY_SATURATION_SCALE = floatPreferencesKey("sky_saturation_scale")
		val SKY_COLOR_PRESET = stringPreferencesKey("sky_color_preset")
		val SUN_VISIBLE = booleanPreferencesKey("sun_visible")
		val MOON_VISIBLE = booleanPreferencesKey("moon_visible")
		val SUN_SIZE_SCALE = floatPreferencesKey("sun_size_scale")
		val SUN_COLOR_PRESET = stringPreferencesKey("sun_color_preset")
		val LENS_FLARE_ENABLED = booleanPreferencesKey("lens_flare_enabled")
		val SCENE_SIMULATOR_ENABLED = booleanPreferencesKey("scene_simulator_enabled")
		val SCENE_SIMULATOR_ACTIVE = booleanPreferencesKey("scene_simulator_active")
		val SCENE_SIMULATOR_PRESET_INDEX = intPreferencesKey("scene_simulator_preset_index")
		val SCENE_SIMULATOR_DAY_PHASE = stringPreferencesKey("scene_simulator_day_phase")
		val SCENE_SIMULATOR_CELESTIAL_PROGRESS = floatPreferencesKey("scene_simulator_celestial_progress")
	}

	val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
		AppSettings(
			weatherProvider = enumOrDefault(preferences[Keys.WEATHER_PROVIDER], WeatherProviderType.entries, defaults.weatherProvider),
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
			wallpaperScrollingEnabled = preferences[Keys.WALLPAPER_SCROLLING_ENABLED] ?: defaults.wallpaperScrollingEnabled,
			precipitationIntensityScale = preferences[Keys.PRECIPITATION_INTENSITY_SCALE] ?: defaults.precipitationIntensityScale,
			windIntensityScale = preferences[Keys.WIND_INTENSITY_SCALE] ?: defaults.windIntensityScale,
			cloudIntensityScale = preferences[Keys.CLOUD_INTENSITY_SCALE] ?: defaults.cloudIntensityScale,
			cloudSizeScale = (preferences[Keys.CLOUD_SIZE_SCALE] ?: defaults.cloudSizeScale).coerceIn(CLOUD_SIZE_SCALE_RANGE.start, CLOUD_SIZE_SCALE_RANGE.endInclusive),
			cloudCountScale = (preferences[Keys.CLOUD_COUNT_SCALE] ?: defaults.cloudCountScale).coerceIn(CLOUD_COUNT_SCALE_RANGE.start, CLOUD_COUNT_SCALE_RANGE.endInclusive),
			cloudContrastScale = (preferences[Keys.CLOUD_CONTRAST_SCALE] ?: defaults.cloudContrastScale).coerceIn(CLOUD_CONTRAST_SCALE_RANGE.start, CLOUD_CONTRAST_SCALE_RANGE.endInclusive),
			skyBrightnessScale = (preferences[Keys.SKY_BRIGHTNESS_SCALE] ?: defaults.skyBrightnessScale).coerceIn(SKY_BRIGHTNESS_SCALE_RANGE.start, SKY_BRIGHTNESS_SCALE_RANGE.endInclusive),
			skySaturationScale = (preferences[Keys.SKY_SATURATION_SCALE] ?: defaults.skySaturationScale).coerceIn(SKY_SATURATION_SCALE_RANGE.start, SKY_SATURATION_SCALE_RANGE.endInclusive),
			skyColorPreset = enumOrDefault(preferences[Keys.SKY_COLOR_PRESET], SkyColorPreset.entries, defaults.skyColorPreset),
			sunVisible = preferences[Keys.SUN_VISIBLE] ?: defaults.sunVisible,
			moonVisible = preferences[Keys.MOON_VISIBLE] ?: defaults.moonVisible,
			sunSizeScale = (preferences[Keys.SUN_SIZE_SCALE] ?: defaults.sunSizeScale).coerceIn(SUN_SIZE_SCALE_RANGE.start, SUN_SIZE_SCALE_RANGE.endInclusive),
			sunColorPreset = enumOrDefault(preferences[Keys.SUN_COLOR_PRESET], SunColorPreset.entries, defaults.sunColorPreset),
			lensFlareEnabled = preferences[Keys.LENS_FLARE_ENABLED] ?: defaults.lensFlareEnabled,
			sceneSimulatorEnabled = preferences[Keys.SCENE_SIMULATOR_ENABLED] ?: defaults.sceneSimulatorEnabled,
			sceneSimulatorActive = preferences[Keys.SCENE_SIMULATOR_ACTIVE] ?: defaults.sceneSimulatorActive,
			sceneSimulatorPresetIndex = (preferences[Keys.SCENE_SIMULATOR_PRESET_INDEX] ?: defaults.sceneSimulatorPresetIndex).coerceAtLeast(0),
			sceneSimulatorDayPhase = enumOrDefault(preferences[Keys.SCENE_SIMULATOR_DAY_PHASE], DayPhase.entries, defaults.sceneSimulatorDayPhase),
			sceneSimulatorCelestialProgress = (preferences[Keys.SCENE_SIMULATOR_CELESTIAL_PROGRESS] ?: defaults.sceneSimulatorCelestialProgress).coerceIn(0f, 1f),
		)
	}

	suspend fun save(settings: AppSettings) {
		dataStore.edit { preferences ->
			preferences[Keys.WEATHER_PROVIDER] = settings.weatherProvider.name
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
			preferences[Keys.WALLPAPER_SCROLLING_ENABLED] = settings.wallpaperScrollingEnabled
			preferences[Keys.PRECIPITATION_INTENSITY_SCALE] = settings.precipitationIntensityScale
			preferences[Keys.WIND_INTENSITY_SCALE] = settings.windIntensityScale
			preferences[Keys.CLOUD_INTENSITY_SCALE] = settings.cloudIntensityScale
			preferences[Keys.CLOUD_SIZE_SCALE] = settings.cloudSizeScale.coerceIn(CLOUD_SIZE_SCALE_RANGE.start, CLOUD_SIZE_SCALE_RANGE.endInclusive)
			preferences[Keys.CLOUD_COUNT_SCALE] = settings.cloudCountScale.coerceIn(CLOUD_COUNT_SCALE_RANGE.start, CLOUD_COUNT_SCALE_RANGE.endInclusive)
			preferences[Keys.CLOUD_CONTRAST_SCALE] = settings.cloudContrastScale.coerceIn(CLOUD_CONTRAST_SCALE_RANGE.start, CLOUD_CONTRAST_SCALE_RANGE.endInclusive)
			preferences[Keys.SKY_BRIGHTNESS_SCALE] = settings.skyBrightnessScale.coerceIn(SKY_BRIGHTNESS_SCALE_RANGE.start, SKY_BRIGHTNESS_SCALE_RANGE.endInclusive)
			preferences[Keys.SKY_SATURATION_SCALE] = settings.skySaturationScale.coerceIn(SKY_SATURATION_SCALE_RANGE.start, SKY_SATURATION_SCALE_RANGE.endInclusive)
			preferences[Keys.SKY_COLOR_PRESET] = settings.skyColorPreset.name
			preferences[Keys.SUN_VISIBLE] = settings.sunVisible
			preferences[Keys.MOON_VISIBLE] = settings.moonVisible
			preferences[Keys.SUN_SIZE_SCALE] = settings.sunSizeScale.coerceIn(SUN_SIZE_SCALE_RANGE.start, SUN_SIZE_SCALE_RANGE.endInclusive)
			preferences[Keys.SUN_COLOR_PRESET] = settings.sunColorPreset.name
			preferences[Keys.LENS_FLARE_ENABLED] = settings.lensFlareEnabled
			preferences[Keys.SCENE_SIMULATOR_ENABLED] = settings.sceneSimulatorEnabled
			preferences[Keys.SCENE_SIMULATOR_ACTIVE] = settings.sceneSimulatorActive
			preferences[Keys.SCENE_SIMULATOR_PRESET_INDEX] = settings.sceneSimulatorPresetIndex
			preferences[Keys.SCENE_SIMULATOR_DAY_PHASE] = settings.sceneSimulatorDayPhase.name
			preferences[Keys.SCENE_SIMULATOR_CELESTIAL_PROGRESS] = settings.sceneSimulatorCelestialProgress.coerceIn(0f, 1f)
		}
	}
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
