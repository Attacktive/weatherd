package xyz.attacktive.weatherd.domain.render

import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.weather.conditionFor
import xyz.attacktive.weatherd.domain.weather.dayPhaseFor
import xyz.attacktive.weatherd.domain.weather.dayPhaseProgressFor
import xyz.attacktive.weatherd.domain.weather.moonPhaseFor
import xyz.attacktive.weatherd.domain.weather.precipitationIntensity

/**
 * Everything the renderer needs, as orthogonal features rather than a scene taxonomy: lighting phase,
 * continuous cloud cover, fog, what precipitates (if anything), lightning, wind, and the day-quantised
 * synodic moon phase (0 = new, 0.5 = full — the default keeps previews and fallbacks on a full moon).
 * [celestialProgress] eases the sun/moon along its arc through the current phase; the midpoint default
 * reproduces the old fixed heights.
 * [backdropScene] is the user's horizon scenery choice — a setting, not weather, so it defaults to the bare sky.
 * [overlayLabels] is the optional text overlay, already formatted for drawing; null keeps the wallpaper text-free.
 */
data class SceneParams(
	val dayPhase: DayPhase,
	val cloudiness: Float,
	val fogDensity: Float,
	val precipitation: Precipitation?,
	val thunder: Boolean,
	val windFactor: Float,
	val moonPhase: Float = 0.5f,
	val celestialProgress: Float = 0.5f,
	val backdropScene: BackdropScene = BackdropScene.NONE,
	val overlayLabels: OverlayLabels? = null
)

/** The two overlay text lines — the current weather ("Rain · 10°") and the place name — each omissible on its own. */
data class OverlayLabels(val weather: String?, val location: String?)

/** Derives render parameters from a weather snapshot for the given moment. */
fun sceneParamsFor(snapshot: WeatherSnapshot, nowEpochSeconds: Long, backdropScene: BackdropScene = BackdropScene.NONE, overlayLabels: OverlayLabels? = null): SceneParams {
	val observation = snapshot.observation
	val condition = conditionFor(observation.weatherCode)
	val dayPhase = dayPhaseFor(nowEpochSeconds, snapshot.sunriseEpochSeconds, snapshot.sunsetEpochSeconds, observation.isDay)

	return SceneParams(
		dayPhase = dayPhase,
		cloudiness = (observation.cloudCoverPercent / 100f).coerceIn(0f, 1f),
		fogDensity = if (condition.fog) {
			1f
		} else {
			0f
		},
		precipitation = condition.precipitationKind?.let {
			Precipitation(kind = it, severity = condition.severity, observed = precipitationIntensity(observation.precipitationMillimeters))
		},
		thunder = condition.thunder,
		windFactor = (observation.windSpeedKilometersPerHour / MAX_WIND_KILOMETERS_PER_HOUR).toFloat()
			.coerceIn(0f, 1f),
		moonPhase = moonPhaseFor(nowEpochSeconds),
		celestialProgress = dayPhaseProgressFor(nowEpochSeconds, snapshot.sunriseEpochSeconds, snapshot.sunsetEpochSeconds, dayPhase),
		backdropScene = backdropScene,
		overlayLabels = overlayLabels
	)
}

private const val MAX_WIND_KILOMETERS_PER_HOUR = 40.0
