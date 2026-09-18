package xyz.attacktive.weatherd.domain.render

import kotlin.math.sqrt
import xyz.attacktive.weatherd.domain.model.DayPhase

/** The shared horizontal sun/moon position in unit-frame coordinates. */
internal const val CELESTIAL_X_FRACTION = 0.72f

/** The unit-space direction from the scene toward the key light, normalized; x points right and upward light has negative y. */
data class LightDirection(val x: Float, val y: Float)

/**
 * Where the sun/moon hangs as a fraction of screen height.
 * The phase progress eases it along a continuous arc: it climbs through dawn, sweeps a shallow parabola across the day whose ends meet the twilight heights exactly, and sinks back through dusk.
 */
internal fun celestialHeightFraction(dayPhase: DayPhase, progress: Float) = when (dayPhase) {
	DayPhase.DAY -> 0.26f - 0.09f * (4f * progress * (1f - progress))
	DayPhase.DAWN -> lerpArc(0.42f, 0.26f, progress)
	DayPhase.DUSK -> lerpArc(0.26f, 0.42f, progress)
	DayPhase.NIGHT -> 0.24f
}

/**
 * Unit-space direction from the horizon band toward the current sun or moon position.
 * This intentionally uses the same anisotropic unit-frame coordinates as ridge normals rather than aspect-correcting one side of the dot product.
 */
fun lightDirectionFor(params: SceneParams): LightDirection {
	val dx = CELESTIAL_X_FRACTION - LIGHT_REFERENCE_X
	val dy = celestialHeightFraction(params.dayPhase, params.celestialProgress) - LIGHT_REFERENCE_Y
	val length = sqrt(dx * dx + dy * dy)

	return LightDirection(dx / length, dy / length)
}

private fun lerpArc(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction

private const val LIGHT_REFERENCE_X = 0.5f
private const val LIGHT_REFERENCE_Y = 0.85f
