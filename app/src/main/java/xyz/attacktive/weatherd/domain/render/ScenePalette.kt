package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.roundToInt
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STEADY

/** A vertical sky gradient as two ARGB colors. */
data class SkyGradient(val topColor: Int, val bottomColor: Int)

/**
 * The sky gradient for a scene: a clear base for the phase, blended toward gray by how overcast the features make it, then darkened for storms.
 * Pure ARGB maths so it unit-tests without Android.
 */
fun skyGradientFor(params: SceneParams): SkyGradient {
	val base = basePhaseGradient(params.dayPhase)
	val gray = if (params.precipitation?.kind == PrecipitationKind.SNOW) {
		snowGray(params.dayPhase)
	} else {
		phaseGray(params.dayPhase)
	}

	val overcast = overcastAmount(params)
	val darken = darkenAmount(params)

	val top = darkenColor(lerpColor(base.topColor, gray, overcast), darken)
	val bottom = darkenColor(lerpColor(base.bottomColor, gray, overcast), darken)

	return SkyGradient(top, bottom)
}

/**
 * The sky-derived tone a scenery plane takes with no intrinsic color — the pre-paint silhouette look.
 * The single source for these tones: the renderer uses it for detail tinting and [sceneryLayerColor] as the atmospheric target.
 */
fun sceneryPlaneTone(plane: SceneryPlane, skyBottom: Int): Int {
	// The near plane keeps 20% of the sky tone's brightness; the far plane sits 55% of the way from sky to near.
	val near = darkenColor(skyBottom, 0.8f)

	return when (plane) {
		SceneryPlane.NEAR -> near
		SceneryPlane.FAR -> lerpColor(skyBottom, near, 0.55f)
	}
}

/**
 * The color a painted layer draws with: its intrinsic daylight color pulled toward the sky-derived plane tone by how much atmosphere sits in front of it.
 * Clear day keeps colors honest, overcast/fog/precipitation grays them, dawn/dusk backlights them, and night collapses to the silhouette look.
 */
fun sceneryLayerColor(material: SceneryMaterial, plane: SceneryPlane, params: SceneParams, skyBottom: Int): Int {
	val target = sceneryPlaneTone(plane, skyBottom)
	if (material == SceneryMaterial.SILHOUETTE) {
		return target
	}

	return lerpColor(intrinsicColor(material), target, atmosphereAmount(params, plane))
}

/**
 * Computes one ridge face's target lighting color from its unit-space normal and the scene's unit-space key light.
 * The renderer blends adjacent face targets at their shared boundaries, so this color supplies directional variation without exposing polygon seams.
 * A straight-up normal is neutral; faces turning toward the light warm and brighten while faces turning away cool and darken.
 * Atmospheric perspective scales the contrast down with distance and weather, and night removes it entirely so the existing silhouette treatment stays authoritative.
 */
fun sceneryFacetColor(material: SceneryMaterial, plane: SceneryPlane, params: SceneParams, skyBottom: Int, facet: SceneryFacet, light: LightDirection): Int {
	val base = sceneryLayerColor(material, plane, params, skyBottom)
	if (params.dayPhase == DayPhase.NIGHT) {
		return base
	}

	val contrast = SCENERY_FACET_MAX_CONTRAST * (1f - atmosphereAmount(params, plane))
	if (contrast <= 0f) {
		return base
	}

	val dot = facet.normalX * light.x + facet.normalY * light.y
	val neutralDot = -light.y
	val response = if (dot >= neutralDot) {
		((dot - neutralDot) / (1f - neutralDot).coerceAtLeast(SCENERY_FACET_MIN_LIGHT_HEADROOM)).coerceIn(0f, 1f)
	} else {
		((dot - neutralDot) / SCENERY_FACET_SHADOW_DOT_SPAN).coerceIn(-1f, 0f)
	}

	val tint = if (response >= 0f) {
		sceneryFacetLightTint(params.dayPhase)
	} else {
		SCENERY_FACET_SHADOW_TINT
	}

	return lerpColor(base, tint, abs(response) * contrast)
}

/** Flat-illustration daylight colors; the atmosphere blend does all weather and time-of-day adaptation. */
private fun intrinsicColor(material: SceneryMaterial) = when (material) {
	SceneryMaterial.SILHOUETTE -> BLACK
	SceneryMaterial.WATER -> rgb(64, 142, 152)
	SceneryMaterial.SAND -> rgb(233, 209, 164)
	SceneryMaterial.HULL -> rgb(70, 62, 58)
	SceneryMaterial.SAIL -> rgb(238, 234, 224)
	SceneryMaterial.PARASOL -> rgb(214, 84, 70)
	SceneryMaterial.ROCK -> rgb(136, 132, 138)
	SceneryMaterial.FOREST -> rgb(108, 165, 92)
	SceneryMaterial.MEADOW -> rgb(158, 192, 110)
	SceneryMaterial.SNOW -> rgb(240, 244, 248)
	SceneryMaterial.PASTURE -> rgb(129, 172, 121)
	SceneryMaterial.WHEAT -> rgb(235, 198, 92)
	SceneryMaterial.BARN -> rgb(170, 66, 54)
	SceneryMaterial.STEEL -> rgb(118, 132, 148)
	SceneryMaterial.MASONRY -> rgb(163, 148, 132)
}

/**
 * How far a layer's intrinsic color gives way to the atmospheric tone: day phase sets the floor (night is nearly full silhouette), the far plane sits deeper in haze, and the same overcast/fog/precipitation signals that gray the sky push it the rest of the way to 1.
 */
private fun atmosphereAmount(params: SceneParams, plane: SceneryPlane): Float {
	val base = when (params.dayPhase) {
		DayPhase.DAY -> 0.35f
		DayPhase.DAWN, DayPhase.DUSK -> 0.75f
		DayPhase.NIGHT -> 0.95f
	}

	val depth = if (plane == SceneryPlane.FAR) {
		0.12f
	} else {
		0f
	}

	val weather = maxOf(
		overcastAmount(params),
		if (params.thunder) {
			1f
		} else {
			0f
		}
	)

	return lerp((base + depth).coerceAtMost(1f), 1f, weather)
}

/** Sun-facing ridge tint; dawn and dusk deliberately carry more amber than midday. */
private fun sceneryFacetLightTint(dayPhase: DayPhase) = when (dayPhase) {
	DayPhase.DAY -> rgb(246, 220, 184)
	DayPhase.DAWN, DayPhase.DUSK -> rgb(255, 184, 128)
	DayPhase.NIGHT -> rgb(246, 220, 184)
}

private fun basePhaseGradient(dayPhase: DayPhase) = when (dayPhase) {
	DayPhase.DAY -> SkyGradient(rgb(74, 144, 217), rgb(169, 214, 245))
	DayPhase.DAWN -> SkyGradient(rgb(52, 64, 107), rgb(246, 169, 132))
	DayPhase.DUSK -> SkyGradient(rgb(38, 49, 79), rgb(232, 130, 91))
	DayPhase.NIGHT -> SkyGradient(rgb(11, 16, 38), rgb(27, 36, 80))
}

private fun phaseGray(dayPhase: DayPhase) = when (dayPhase) {
	DayPhase.DAY -> rgb(150, 160, 170)
	DayPhase.DAWN -> rgb(120, 120, 140)
	DayPhase.DUSK -> rgb(110, 110, 130)
	DayPhase.NIGHT -> rgb(28, 32, 42)
}

/** Snowfall gets a brighter, milkier sky than rain so flakes read against it and the scene feels wintry rather than gloomy. */
private fun snowGray(dayPhase: DayPhase) = when (dayPhase) {
	DayPhase.DAY -> rgb(196, 204, 214)
	DayPhase.DAWN -> rgb(168, 164, 182)
	DayPhase.DUSK -> rgb(148, 146, 166)
	DayPhase.NIGHT -> rgb(54, 60, 76)
}

/**
 * How far the sky blends toward gray. Fog and precipitation force their own grayness; otherwise cloud cover drives it (calibrated so 5% cover reads clear and 85% reads fully overcast).
 */
/**
 * How far the sky is pulled from its clear color toward gray.
 * A dry sky holds its full blue until the overcast ceiling starts drawing, because scattered cumulus darken a sky by covering it, not by draining the color out of the gaps between them.
 * Graying earlier than that leaves white clouds sitting on a washed-out sky with nothing to read against, which is the opposite of what cloud cover looks like.
 */
private fun overcastAmount(params: SceneParams): Float = when {
	params.fogDensity > 0f -> 0.85f
	params.precipitation != null -> precipitationGray(params.precipitation)
	else -> ((params.cloudiness - OVERCAST_GRAY_FLOOR) / (OVERCAST_GRAY_FULL - OVERCAST_GRAY_FLOOR)).coerceIn(0f, 1f)
}

/** Maximum daylight color shift applied to a ridge face before atmosphere reduces it. */
private const val SCENERY_FACET_MAX_CONTRAST = 0.09f

/** Shadow-side dot-product distance from the straight-up neutral that reaches the facet color shift ceiling. */
private const val SCENERY_FACET_SHADOW_DOT_SPAN = 0.35f

/** Prevents a near-vertical key light from dividing by vanishing positive headroom. */
private const val SCENERY_FACET_MIN_LIGHT_HEADROOM = 0.01f

/** Cool target for ridge faces turned away from the key light. */
private val SCENERY_FACET_SHADOW_TINT = rgb(90, 112, 142)

/** The cloudiness at which a dry sky starts graying, matching where the renderer starts drawing an overcast ceiling. */
private const val OVERCAST_GRAY_FLOOR = 0.55f

/** The cloudiness at which a dry sky has given up its blue entirely. */
private const val OVERCAST_GRAY_FULL = 0.85f

private fun precipitationGray(precipitation: Precipitation) = when (precipitation.kind) {
	PrecipitationKind.SNOW -> 0.6f
	// Rain/sleet skies sit at 0.7 up to a steady fall, then gray further toward a downpour's 0.82.
	else -> lerp(0.7f, 0.82f, unlerp(SEVERITY_STEADY, 1f, precipitation.severity))
}

/** Storm gloom: thunder darkens hardest, rain by how hard it falls, and a dry overcast sky slightly. */
private fun darkenAmount(params: SceneParams): Float = when {
	params.thunder -> 0.35f
	params.precipitation != null && params.precipitation.kind != PrecipitationKind.SNOW -> {
		val severity = params.precipitation.severity
		when {
			severity >= 0.85f -> 0.22f
			severity >= 0.5f -> 0.12f
			else -> 0.07f
		}
	}
	params.precipitation == null && params.cloudiness > 0.75f -> 0.07f
	else -> 0f
}

private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction.coerceIn(0f, 1f)

/** Inverse lerp: where [value] sits between [from] and [to], clamped to 0..1. */
private fun unlerp(from: Float, to: Float, value: Float) = ((value - from) / (to - from)).coerceIn(0f, 1f)

private fun darkenColor(color: Int, amount: Float) = lerpColor(color, BLACK, amount)

private fun lerpColor(from: Int, to: Int, fraction: Float): Int {
	val clamped = fraction.coerceIn(0f, 1f)
	val red = lerpChannel(from ushr 16 and 0xFF, to ushr 16 and 0xFF, clamped)
	val green = lerpChannel(from ushr 8 and 0xFF, to ushr 8 and 0xFF, clamped)
	val blue = lerpChannel(from and 0xFF, to and 0xFF, clamped)

	return rgb(red, green, blue)
}

private fun lerpChannel(from: Int, to: Int, fraction: Float) = (from + (to - from) * fraction).roundToInt()

private fun rgb(red: Int, green: Int, blue: Int) = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

private val BLACK = rgb(0, 0, 0)
