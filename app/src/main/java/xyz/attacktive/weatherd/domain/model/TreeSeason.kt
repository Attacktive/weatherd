package xyz.attacktive.weatherd.domain.model

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

/** Auto selects the calendar season; Spring uses the original foliage artwork. */
enum class TreeSeason {
	AUTO,
	SPRING,
	SUMMER,
	AUTUMN,
	WINTER
}

/** Unresolved Auto is safe to draw with the unmodified Spring sprites while location data is unavailable. */
val TreeSeason.usesSpringArtwork: Boolean
	get() = this == TreeSeason.AUTO || this == TreeSeason.SPRING

/**
 * Resolve Auto from the selected location's hemisphere and UTC meteorological month.
 * Unknown, invalid, or tropical latitudes (within 15 degrees of the equator) use neutral Spring foliage.
 * UTC avoids weather requests and inaccurate longitude-to-civil-timezone guesses.
 */
fun resolvedTreeSeason(selection: TreeSeason, nowEpochSeconds: Long, latitude: Double?): TreeSeason {
	if (selection != TreeSeason.AUTO) {
		return selection
	}

	val hemisphere = seasonalHemisphere(latitude) ?: return TreeSeason.SPRING
	val utcMonth = Instant.ofEpochSecond(nowEpochSeconds).atZone(ZoneOffset.UTC).monthValue
	val month = if (hemisphere < 0) {
		(utcMonth + 5) % 12 + 1
	} else {
		utcMonth
	}

	return when (month) {
		12, 1, 2 -> TreeSeason.WINTER
		3, 4, 5 -> TreeSeason.SPRING
		6, 7, 8 -> TreeSeason.SUMMER
		else -> TreeSeason.AUTUMN
	}
}

/** The optional tropical band has no fixed temperate foliage season. */
private fun seasonalHemisphere(latitude: Double?): Int? {
	if (latitude == null || !latitude.isFinite()) {
		return null
	}

	if (abs(latitude) <= 15.0 || abs(latitude) > 90.0) {
		return null
	}

	return if (latitude > 0.0) 1 else -1
}
