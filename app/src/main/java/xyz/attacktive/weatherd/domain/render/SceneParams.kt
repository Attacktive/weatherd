package xyz.attacktive.weatherd.domain.render

import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.weather.conditionFor
import xyz.attacktive.weatherd.domain.weather.dayPhaseFor
import xyz.attacktive.weatherd.domain.weather.precipitationIntensity

/**
 * Everything the renderer needs, as orthogonal features rather than a scene taxonomy: lighting phase,
 * continuous cloud cover, fog, what precipitates (if anything), lightning, and wind.
 */
data class SceneParams(
	val dayPhase: DayPhase,
	val cloudiness: Float,
	val fogDensity: Float,
	val precipitation: Precipitation?,
	val thunder: Boolean,
	val windFactor: Float
)

/** Derives render parameters from a weather snapshot for the given moment. */
fun sceneParamsFor(snapshot: WeatherSnapshot, nowEpochSeconds: Long): SceneParams {
	val observation = snapshot.observation
	val condition = conditionFor(observation.weatherCode)

	return SceneParams(
		dayPhase = dayPhaseFor(nowEpochSeconds, snapshot.sunriseEpochSeconds, snapshot.sunsetEpochSeconds, observation.isDay),
		cloudiness = (observation.cloudCoverPercent / 100f).coerceIn(0f, 1f),
		fogDensity = if (condition.fog) 1f else 0f,
		precipitation = condition.precipitationKind?.let {
			Precipitation(kind = it, severity = condition.severity, observed = precipitationIntensity(observation.precipitationMillimeters))
		},
		thunder = condition.thunder,
		windFactor = (observation.windSpeedKilometersPerHour / MAX_WIND_KILOMETERS_PER_HOUR).toFloat().coerceIn(0f, 1f)
	)
}

private const val MAX_WIND_KILOMETERS_PER_HOUR = 40.0
