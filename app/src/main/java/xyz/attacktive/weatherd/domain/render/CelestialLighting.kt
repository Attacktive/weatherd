package xyz.attacktive.weatherd.domain.render

import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.SunColorPreset

internal const val CELESTIAL_X_FRACTION = 0.72f

/** Shared screen-space sun/moon trajectory; daylight endpoints meet the twilight arc exactly. */
internal fun celestialHeightFraction(dayPhase: DayPhase, progress: Float) = when (dayPhase) {
	DayPhase.DAY -> 0.26f - 0.09f * (4f * progress * (1f - progress))
	DayPhase.DAWN -> TWILIGHT_HORIZON_HEIGHT_FRACTION + (0.26f - TWILIGHT_HORIZON_HEIGHT_FRACTION) * progress
	DayPhase.DUSK -> 0.26f + (TWILIGHT_HORIZON_HEIGHT_FRACTION - 0.26f) * progress
	DayPhase.NIGHT -> 0.24f
}

/** Fades all sun components together through dusk without changing the independently painted sky. */
internal fun sunVisibility(dayPhase: DayPhase, progress: Float): Float {
	if (dayPhase != DayPhase.DUSK) {
		return 1f
	}

	val fade = ((progress - SUNSET_FADE_START) / (SUNSET_FADE_END - SUNSET_FADE_START)).coerceIn(0f, 1f)
	val eased = fade * fade

	return 1f - eased
}

/** Packed RGB keeps both sky artwork and terrain lighting usable by JVM-only callers. */
internal fun sunColor(dayPhase: DayPhase, preset: SunColorPreset) = when (preset) {
	SunColorPreset.NATURAL -> when (dayPhase) {
		DayPhase.DAWN -> rgb(255, 224, 190)
		DayPhase.DUSK -> rgb(255, 208, 178)
		else -> rgb(255, 248, 218)
	}

	SunColorPreset.WHITE -> rgb(255, 255, 248)
	SunColorPreset.GOLDEN -> rgb(255, 228, 150)
	SunColorPreset.ORANGE -> rgb(255, 188, 118)
}

private fun rgb(red: Int, green: Int, blue: Int) = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

private const val SUNSET_FADE_START = 0.15f
private const val SUNSET_FADE_END = 0.92f

/** Lowest twilight source height, in the horizon band rather than mid-sky. */
private const val TWILIGHT_HORIZON_HEIGHT_FRACTION = 0.58f
