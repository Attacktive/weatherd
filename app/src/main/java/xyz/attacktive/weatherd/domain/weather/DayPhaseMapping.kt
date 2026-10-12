package xyz.attacktive.weatherd.domain.weather

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.roundToLong
import xyz.attacktive.weatherd.domain.model.DayPhase

/**
 * Classifies a moment into a lighting phase from the sun times, with a twilight window straddling sunrise and sunset.
 * Falls back to [isDay] when sun times are unavailable.
 */
fun dayPhaseFor(nowEpochSeconds: Long, sunriseEpochSeconds: Long?, sunsetEpochSeconds: Long?, isDay: Boolean): DayPhase = dayPhaseFor(
	nowEpochSeconds,
	sunriseEpochSeconds,
	sunsetEpochSeconds,
	isDay,
	observedAtEpochSeconds = null,
	fallbackLongitude = null,
	isDayAuthoritativeWithoutCompleteSolarTimes = false
)

/**
 * Classifies a cached observation at [nowEpochSeconds].
 * When solar timestamps are incomplete, an explicitly authoritative provider classification remains pinned; otherwise fresh [isDay] is trusted for one twilight window before the selected location's longitude-based solar clock keeps cached lighting moving without replacing cached weather.
 */
internal fun dayPhaseFor(
	nowEpochSeconds: Long,
	sunriseEpochSeconds: Long?,
	sunsetEpochSeconds: Long?,
	isDay: Boolean,
	observedAtEpochSeconds: Long?,
	fallbackLongitude: Double?,
	isDayAuthoritativeWithoutCompleteSolarTimes: Boolean = false
): DayPhase {
	if (sunriseEpochSeconds == null || sunsetEpochSeconds == null) {
		return missingSolarTimesDayPhaseFor(
			nowEpochSeconds,
			isDay,
			observedAtEpochSeconds,
			fallbackLongitude,
			isDayAuthoritativeWithoutCompleteSolarTimes
		)
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

private fun missingSolarTimesDayPhaseFor(
	nowEpochSeconds: Long,
	isDay: Boolean,
	observedAtEpochSeconds: Long?,
	fallbackLongitude: Double?,
	isDayAuthoritativeWithoutCompleteSolarTimes: Boolean
): DayPhase {
	if (isDayAuthoritativeWithoutCompleteSolarTimes) {
		return providerDayPhase(isDay)
	}

	val observationAgeSeconds = observedAtEpochSeconds?.let { nowEpochSeconds - it }
	if (observationAgeSeconds == null || observationAgeSeconds <= MISSING_SOLAR_IS_DAY_FRESHNESS_SECONDS || fallbackLongitude == null) {
		return providerDayPhase(isDay)
	}

	return when (solarClockHour(nowEpochSeconds, fallbackLongitude)) {
		in DAWN_HOUR until DAY_HOUR -> DayPhase.DAWN
		in DAY_HOUR until DUSK_HOUR -> DayPhase.DAY
		in DUSK_HOUR until NIGHT_HOUR -> DayPhase.DUSK
		else -> DayPhase.NIGHT
	}
}

private fun providerDayPhase(isDay: Boolean) = if (isDay) {
	DayPhase.DAY
} else {
	DayPhase.NIGHT
}

private fun solarClockHour(nowEpochSeconds: Long, longitude: Double): Int {
	val longitudeOffsetSeconds = (longitude.coerceIn(MIN_LONGITUDE, MAX_LONGITUDE) / FULL_CIRCLE_DEGREES * SECONDS_PER_DAY).roundToLong()

	return Instant.ofEpochSecond(nowEpochSeconds + longitudeOffsetSeconds)
		.atZone(ZoneOffset.UTC)
		.hour
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
private const val MISSING_SOLAR_IS_DAY_FRESHNESS_SECONDS = TWILIGHT_SECONDS
private const val FULL_CIRCLE_DEGREES = 360.0
private const val MIN_LONGITUDE = -180.0
private const val MAX_LONGITUDE = 180.0
private const val DAWN_HOUR = 5
private const val DAY_HOUR = 7
private const val DUSK_HOUR = 17
private const val NIGHT_HOUR = 19

/** Progress quantization steps; at 32 a typical 90-minute twilight ticks roughly every three minutes. */
private const val PROGRESS_STEPS = 32
