package xyz.attacktive.weatherd.domain.render

import xyz.attacktive.weatherd.domain.model.CLOUD_CONTRAST_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.CLOUD_COUNT_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.CLOUD_SIZE_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.NIGHT_BRIGHTNESS_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.SKY_BRIGHTNESS_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SKY_SATURATION_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SUN_SIZE_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SunColorPreset
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_HEAVY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STEADY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

/** A named feature combination for the debug scene picker; combined with a [DayPhase] via [debugSceneParams]. */
data class ScenePreset(
	val name: String,
	val cloudiness: Float,
	val fogDensity: Float = 0f,
	val precipitation: Precipitation? = null,
	val thunder: Boolean = false,
	val windFactor: Float = 0.3f
)

/** Representative feature combinations covering every rendering branch, for previewing without a weather fetch. */
val SCENE_PRESETS = listOf(
	ScenePreset("CLEAR", cloudiness = 0.05f),
	ScenePreset("MOSTLY CLEAR", cloudiness = 0.2f),
	ScenePreset("PARTLY CLOUDY", cloudiness = 0.7f),
	ScenePreset("OVERCAST", cloudiness = 0.85f),
	ScenePreset("FOG", cloudiness = 0.85f, fogDensity = 1f),
	ScenePreset("DRIZZLE", cloudiness = 0.75f, precipitation = Precipitation(PrecipitationKind.RAIN, SEVERITY_DRIZZLE, observed = 0.35f), windFactor = 0.45f),
	ScenePreset("RAIN", cloudiness = 0.75f, precipitation = Precipitation(PrecipitationKind.RAIN, SEVERITY_STEADY, observed = 0.65f), windFactor = 0.45f),
	ScenePreset("HEAVY RAIN", cloudiness = 0.75f, precipitation = Precipitation(PrecipitationKind.RAIN, SEVERITY_HEAVY, observed = 0.9f), windFactor = 0.65f),
	ScenePreset("SLEET", cloudiness = 0.75f, precipitation = Precipitation(PrecipitationKind.SLEET, SEVERITY_STEADY, observed = 0.65f), windFactor = 0.45f),
	ScenePreset("SNOW", cloudiness = 0.75f, precipitation = Precipitation(PrecipitationKind.SNOW, SEVERITY_STEADY, observed = 0.65f), windFactor = 0.45f),
	ScenePreset("HEAVY SNOW", cloudiness = 0.75f, precipitation = Precipitation(PrecipitationKind.SNOW, SEVERITY_HEAVY, observed = 0.9f), windFactor = 0.45f),
	ScenePreset("THUNDERSTORM", cloudiness = 0.75f, precipitation = Precipitation(PrecipitationKind.RAIN, SEVERITY_STORM, observed = 0.9f), thunder = true, windFactor = 0.85f)
)

/**
 * Combines a preset with a day phase and user scales into render parameters.
 * Debug mode is a weather simulator, so it pins the weather while carrying the user's display preferences for the renderer to apply.
 */
fun debugSceneParams(
	preset: ScenePreset,
	dayPhase: DayPhase,
	precipitationScale: Float = 1f,
	windScale: Float = 1f,
	cloudScale: Float = 1f,
	cloudSizeScale: Float = 1f,
	cloudCountScale: Float = 1f,
	cloudContrastScale: Float = 1f,
	skyBrightnessScale: Float = 1f,
	nightBrightnessScale: Float = 1f,
	skySaturationScale: Float = 1f,
	skyColorPreset: SkyColorPreset = SkyColorPreset.NATURAL,
	sunVisible: Boolean = true,
	moonVisible: Boolean = true,
	sunSizeScale: Float = 1f,
	sunColorPreset: SunColorPreset = SunColorPreset.NATURAL,
	lensFlareEnabled: Boolean = true,
	celestialProgress: Float = 0.5f,
) = SceneParams(
	dayPhase = dayPhase,
	cloudiness = preset.cloudiness,
	fogDensity = preset.fogDensity,
	precipitation = preset.precipitation,
	thunder = preset.thunder,
	windFactor = preset.windFactor,
	precipitationScale = precipitationScale,
	windScale = windScale,
	cloudScale = cloudScale,
	cloudSizeScale = cloudSizeScale.coerceIn(CLOUD_SIZE_SCALE_RANGE.start, CLOUD_SIZE_SCALE_RANGE.endInclusive),
	cloudCountScale = cloudCountScale.coerceIn(CLOUD_COUNT_SCALE_RANGE.start, CLOUD_COUNT_SCALE_RANGE.endInclusive),
	cloudContrastScale = cloudContrastScale.coerceIn(CLOUD_CONTRAST_SCALE_RANGE.start, CLOUD_CONTRAST_SCALE_RANGE.endInclusive),
	skyBrightnessScale = skyBrightnessScale.coerceIn(SKY_BRIGHTNESS_SCALE_RANGE.start, SKY_BRIGHTNESS_SCALE_RANGE.endInclusive),
	nightBrightnessScale = nightBrightnessScale.coerceIn(NIGHT_BRIGHTNESS_SCALE_RANGE.start, NIGHT_BRIGHTNESS_SCALE_RANGE.endInclusive),
	skySaturationScale = skySaturationScale.coerceIn(SKY_SATURATION_SCALE_RANGE.start, SKY_SATURATION_SCALE_RANGE.endInclusive),
	skyColorPreset = skyColorPreset,
	sunVisible = sunVisible,
	moonVisible = moonVisible,
	sunSizeScale = sunSizeScale.coerceIn(SUN_SIZE_SCALE_RANGE.start, SUN_SIZE_SCALE_RANGE.endInclusive),
	sunColorPreset = sunColorPreset,
	lensFlareEnabled = lensFlareEnabled,
	celestialProgress = celestialProgress,
)
