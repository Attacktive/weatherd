package xyz.attacktive.weatherd.domain.weather

import xyz.attacktive.weatherd.domain.model.DayPhase

/**
 * Classifies a moment into a lighting phase from the sun times, with a twilight window straddling sunrise and sunset.
 * Falls back to [isDay] when sun times are unavailable.
 */
fun dayPhaseFor(nowEpochSeconds: Long, sunriseEpochSeconds: Long?, sunsetEpochSeconds: Long?, isDay: Boolean): DayPhase {
	if (sunriseEpochSeconds == null || sunsetEpochSeconds == null) {
		return if (isDay) {
			DayPhase.DAY
		} else {
			DayPhase.NIGHT
		}
	}

	return when (nowEpochSeconds) {
		in (sunriseEpochSeconds - TWILIGHT_SECONDS)..(sunriseEpochSeconds + TWILIGHT_SECONDS) -> DayPhase.DAWN
		in (sunsetEpochSeconds - TWILIGHT_SECONDS)..(sunsetEpochSeconds + TWILIGHT_SECONDS) -> DayPhase.DUSK
		in sunriseEpochSeconds..sunsetEpochSeconds -> DayPhase.DAY
		else -> DayPhase.NIGHT
	}
}

/**
 * How far through its current phase window the moment sits, 0..1, quantized to coarse steps so scene params hold stable for minutes at a time.
 * Night reports a fixed midpoint: its window would span two calendar days' sun times, and a moon hanging steady beats one that jumps at midnight.
 */
fun dayPhaseProgressFor(nowEpochSeconds: Long, sunriseEpochSeconds: Long?, sunsetEpochSeconds: Long?, dayPhase: DayPhase): Float {
	if (sunriseEpochSeconds == null || sunsetEpochSeconds == null || dayPhase == DayPhase.NIGHT) {
		return 0.5f
	}

	val window = when (dayPhase) {
		DayPhase.DAWN -> (sunriseEpochSeconds - TWILIGHT_SECONDS)..(sunriseEpochSeconds + TWILIGHT_SECONDS)
		DayPhase.DUSK -> (sunsetEpochSeconds - TWILIGHT_SECONDS)..(sunsetEpochSeconds + TWILIGHT_SECONDS)
		else -> (sunriseEpochSeconds + TWILIGHT_SECONDS)..(sunsetEpochSeconds - TWILIGHT_SECONDS)
	}

	if (window.isEmpty()) {
		return 0.5f
	}

	val raw = (nowEpochSeconds - window.first).toFloat() / (window.last - window.first).toFloat()
	val stepped = (raw * PROGRESS_STEPS).toInt().toFloat() / PROGRESS_STEPS

	return stepped.coerceIn(0f, 1f)
}

private const val TWILIGHT_SECONDS = 45L * 60L

/** Progress quantization steps; at 32 a typical 90-minute twilight ticks roughly every three minutes. */
private const val PROGRESS_STEPS = 32
