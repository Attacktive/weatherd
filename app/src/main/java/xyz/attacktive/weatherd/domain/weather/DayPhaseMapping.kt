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

	val solarOffsetSeconds = solarDayOffsetSeconds(nowEpochSeconds, sunsetEpochSeconds)
	val sunrise = sunriseEpochSeconds + solarOffsetSeconds
	val sunset = sunsetEpochSeconds + solarOffsetSeconds

	return when (nowEpochSeconds) {
		in (sunrise - TWILIGHT_SECONDS)..(sunrise + TWILIGHT_SECONDS) -> DayPhase.DAWN
		in (sunset - TWILIGHT_SECONDS)..(sunset + TWILIGHT_SECONDS) -> DayPhase.DUSK
		in sunrise..sunset -> DayPhase.DAY
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

	val solarOffsetSeconds = solarDayOffsetSeconds(nowEpochSeconds, sunsetEpochSeconds)
	val sunrise = sunriseEpochSeconds + solarOffsetSeconds
	val sunset = sunsetEpochSeconds + solarOffsetSeconds
	val window = when (dayPhase) {
		DayPhase.DAWN -> (sunrise - TWILIGHT_SECONDS)..(sunrise + TWILIGHT_SECONDS)
		DayPhase.DUSK -> (sunset - TWILIGHT_SECONDS)..(sunset + TWILIGHT_SECONDS)
		else -> (sunrise + TWILIGHT_SECONDS)..(sunset - TWILIGHT_SECONDS)
	}

	if (window.isEmpty()) {
		return 0.5f
	}

	val raw = (nowEpochSeconds - window.first).toFloat() / (window.last - window.first).toFloat()
	val stepped = (raw * PROGRESS_STEPS).toInt().toFloat() / PROGRESS_STEPS

	return stepped.coerceIn(0f, 1f)
}

/** Repeats cached solar times every 24 hours after their dusk window ends so day/night lighting keeps moving while weather is offline. */
private fun solarDayOffsetSeconds(nowEpochSeconds: Long, sunsetEpochSeconds: Long): Long {
	val duskEndEpochSeconds = sunsetEpochSeconds + TWILIGHT_SECONDS
	if (nowEpochSeconds <= duskEndEpochSeconds) {
		return 0L
	}

	val elapsedAfterDuskEnd = nowEpochSeconds - duskEndEpochSeconds
	val daysToAdvance = (elapsedAfterDuskEnd + SECONDS_PER_DAY - 1L) / SECONDS_PER_DAY

	return daysToAdvance * SECONDS_PER_DAY
}

private const val SECONDS_PER_DAY = 24L * 60L * 60L
private const val TWILIGHT_SECONDS = 45L * 60L

/** Progress quantization steps; at 32 a typical 90-minute twilight ticks roughly every three minutes. */
private const val PROGRESS_STEPS = 32
