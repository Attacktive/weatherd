package xyz.attacktive.weatherd.domain.weather

import xyz.attacktive.weatherd.domain.model.DayPhase

/**
 * Classifies a moment into a lighting phase from the sun times, with a twilight window
 * straddling sunrise and sunset. Falls back to [isDay] when sun times are unavailable.
 */
fun dayPhaseFor(nowEpochSeconds: Long, sunriseEpochSeconds: Long?, sunsetEpochSeconds: Long?, isDay: Boolean): DayPhase {
	if (sunriseEpochSeconds == null || sunsetEpochSeconds == null) {
		return if (isDay) DayPhase.DAY else DayPhase.NIGHT
	}

	return when (nowEpochSeconds) {
		in (sunriseEpochSeconds - TWILIGHT_SECONDS)..(sunriseEpochSeconds + TWILIGHT_SECONDS) -> DayPhase.DAWN
		in (sunsetEpochSeconds - TWILIGHT_SECONDS)..(sunsetEpochSeconds + TWILIGHT_SECONDS) -> DayPhase.DUSK
		in sunriseEpochSeconds..sunsetEpochSeconds -> DayPhase.DAY
		else -> DayPhase.NIGHT
	}
}

private const val TWILIGHT_SECONDS = 45L * 60L
