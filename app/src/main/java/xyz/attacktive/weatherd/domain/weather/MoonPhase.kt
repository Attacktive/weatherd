package xyz.attacktive.weatherd.domain.weather

/**
 * Fraction through the synodic month at the given moment: 0 = new moon, 0.5 = full, wrapping just below 1.
 * Quantised to the UTC day so scene params stay stable between frames; the real terminator moves ~12° a day, far below what the wallpaper's moon resolves.
 */
fun moonPhaseFor(nowEpochSeconds: Long): Float {
	// Whole UTC days on both sides, so the phase steps exactly at midnight UTC instead of at the reference's wall time.
	val daysSinceReferenceNewMoon = nowEpochSeconds / SECONDS_PER_DAY - REFERENCE_NEW_MOON_EPOCH_SECONDS / SECONDS_PER_DAY

	return (((daysSinceReferenceNewMoon % SYNODIC_MONTH_DAYS) + SYNODIC_MONTH_DAYS) % SYNODIC_MONTH_DAYS / SYNODIC_MONTH_DAYS).toFloat()
}

/** 2000-01-06 18:14 UTC, the first new moon of 2000 — the standard reference epoch for mean-phase arithmetic. */
private const val REFERENCE_NEW_MOON_EPOCH_SECONDS = 947_182_440L

private const val SECONDS_PER_DAY = 86_400L
private const val SYNODIC_MONTH_DAYS = 29.530589
