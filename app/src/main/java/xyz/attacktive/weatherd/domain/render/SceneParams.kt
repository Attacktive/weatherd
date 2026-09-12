package xyz.attacktive.weatherd.domain.render

import kotlin.math.pow
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
 * Everything the renderer needs, as orthogonal features rather than a scene taxonomy: lighting phase, continuous cloud cover, fog, what precipitates (if anything), lightning, wind, and the day-quantized synodic moon phase (0 = new, 0.5 = full — the default keeps previews and fallbacks on a full moon).
 * [celestialProgress] eases the sun/moon along its arc through the current phase; the midpoint default reproduces the old fixed heights.
 * [backdropScene] is the user's horizon scenery choice — a setting, not weather, so it defaults to the bare sky.
 * [photoRevision] counts changes to the user's stored photos, which are otherwise invisible to these params: picking a new photo for a bucket that already had one leaves every other field identical, and the wallpaper's backdrop cache would go on drawing the old one until the next phase flip.
 * It is an opaque number and never a file name or a bitmap — the params stay JVM-pure, and the renderer resolves the photo itself.
 * [overlayLabels] is the optional text overlay, already formatted for drawing; null keeps the wallpaper text-free.
 * [precipitationScale] is the user's preference rather than an observation, so it rides alongside [precipitation] instead of being folded into it: the renderer applies it past its own visibility floor, where it is the drop count the user actually sees.
 * [windScale] is the user's preference rather than an observation, so it rides alongside [windFactor] instead of being folded into it: the renderer applies it past its own floors, where it actually moves visible wind effects.
 * [cloudScale] is the user's preference rather than an observation, so it rides alongside [cloudiness] instead of being folded into it: the renderer applies it past its own floors, where it scales cloud opacity.
 */
data class SceneParams(
	val dayPhase: DayPhase,
	val cloudiness: Float,
	val fogDensity: Float,
	val precipitation: Precipitation?,
	val thunder: Boolean,
	val windFactor: Float,
	val precipitationScale: Float = 1f,
	val windScale: Float = 1f,
	val cloudScale: Float = 1f,
	val moonPhase: Float = 0.5f,
	val celestialProgress: Float = 0.5f,
	val backdropScene: BackdropScene = BackdropScene.NONE,
	val photoRevision: Int = 0,
	val overlayLabels: OverlayLabels? = null
)

/** The two overlay text lines — the current weather ("Rain · 10°") and the place name — each omissible on its own. */
data class OverlayLabels(val weather: String?, val location: String?)

/**
 * The same scene with the fields the backdrop cannot show flattened away, so two params that differ only in those compare equal.
 * The backdrop never draws the moon or the sun's arc, so their slow foreground-only ticks must not force a re-rasterize — and [celestialProgress] moves every few minutes through dawn and dusk, which would otherwise re-decode the user's photo for a change nothing in the backdrop reflects.
 * Which photo draws is a function of [SceneParams.dayPhase] and [SceneParams.backdropScene], so a phase flip or a switch away from [BackdropScene.PHOTO] re-rasterizes on its own; [SceneParams.photoRevision] covers the case those two miss, where the photo behind a fixed bucket is replaced or cleared.
 * Every other field is carried through untouched, so a field added later stays backdrop-relevant until someone lists it here.
 * Both the wallpaper's backdrop cache and the in-app preview's remembered backdrop key on this, which is what keeps them redrawing on exactly the same changes.
 */
fun backdropSignature(params: SceneParams) = params.copy(moonPhase = 0f, celestialProgress = 0f, overlayLabels = null)

/** Derives render parameters from a weather snapshot for the given moment. */
fun sceneParamsFor(
	snapshot: WeatherSnapshot,
	nowEpochSeconds: Long,
	backdropScene: BackdropScene = BackdropScene.NONE,
	photoRevision: Int = 0,
	overlayLabels: OverlayLabels? = null,
	precipitationScale: Float = 1f,
	windScale: Float = 1f,
	cloudScale: Float = 1f
): SceneParams {
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
			Precipitation(kind = it, severity = condition.severity, observed = shapedIntensity(precipitationIntensity(observation.precipitationMillimeters)))
		},
		thunder = condition.thunder,
		// Both scales are carried preferences that the renderer applies past its own floors, which is what makes the sliders span their advertised range.
		windFactor = shapedIntensity((observation.windSpeedKilometersPerHour / MAX_WIND_KILOMETERS_PER_HOUR).toFloat()),
		precipitationScale = precipitationScale,
		windScale = windScale,
		cloudScale = cloudScale,
		moonPhase = moonPhaseFor(nowEpochSeconds),
		celestialProgress = dayPhaseProgressFor(nowEpochSeconds, snapshot.sunriseEpochSeconds, snapshot.sunsetEpochSeconds, dayPhase),
		backdropScene = backdropScene,
		photoRevision = photoRevision,
		overlayLabels = overlayLabels
	)
}

/**
 * Lifts a normalized weather reading off the floor before the renderer sees it.
 * A plain `value / ceiling` leaves everyday weather — a 10 km/h breeze, 1 mm of rain — in the bottom quarter of a range built to reach a gale, so the scene reads the same on most days.
 * The gamma expands that crowded low end and barely touches the top, where a storm should still look like a storm.
 */
private fun shapedIntensity(normalized: Float) = normalized.coerceIn(0f, 1f)
	.pow(INTENSITY_GAMMA)
	.coerceIn(0f, 1f)

private const val INTENSITY_GAMMA = 0.6f
private const val MAX_WIND_KILOMETERS_PER_HOUR = 40.0
