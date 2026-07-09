package xyz.attacktive.weatherd.domain.render

import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
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
	ScenePreset("PARTLY CLOUDY", cloudiness = 0.4f),
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

fun debugSceneParams(preset: ScenePreset, dayPhase: DayPhase) = SceneParams(
	dayPhase = dayPhase,
	cloudiness = preset.cloudiness,
	fogDensity = preset.fogDensity,
	precipitation = preset.precipitation,
	thunder = preset.thunder,
	windFactor = preset.windFactor
)
