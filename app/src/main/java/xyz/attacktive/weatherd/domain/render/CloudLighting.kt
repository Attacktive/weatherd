package xyz.attacktive.weatherd.domain.render

import xyz.attacktive.weatherd.domain.model.DayPhase

/** RGB material endpoints; strength zero preserves the baked artwork. */
internal data class CloudColorGrade(val shadowColor: Int, val highlightColor: Int, val strength: Float) {
	companion object {
		val IDENTITY = CloudColorGrade(0, 0, 0f)
	}
}

/**
 * Returns luminance-aware material grade for dawn and dusk low sun.
 * Day and night return [CloudColorGrade.IDENTITY], preserving restored appearance.
 */
internal fun cloudColorGradeFor(dayPhase: DayPhase, celestialProgress: Float): CloudColorGrade = when (dayPhase) {
	DayPhase.DAY, DayPhase.NIGHT -> CloudColorGrade.IDENTITY
	DayPhase.DAWN -> {
		val sunrise = dawnSunriseStrength(celestialProgress)
		val daylight = dawnDaylightStrength(celestialProgress)
		val strength = sunrise * (1f - daylight)
		if (strength <= 0f) {
			CloudColorGrade.IDENTITY
		} else {
			CloudColorGrade(LOW_SUN_CLOUD_SHADOW, LOW_SUN_CLOUD_HIGHLIGHT, strength)
		}
	}

	DayPhase.DUSK -> {
		val sunsetWarmth = duskWarmStrength(celestialProgress)
		val nightfall = duskNightStrength(celestialProgress)
		val strength = sunsetWarmth * (1f - nightfall)
		if (strength <= 0f) {
			CloudColorGrade.IDENTITY
		} else {
			CloudColorGrade(LOW_SUN_CLOUD_SHADOW, LOW_SUN_CLOUD_HIGHLIGHT, strength)
		}
	}
}

/** Writes grade, contrast around 128, then depth tint; the fourth row preserves alpha exactly. */
internal fun writeCloudColorMatrix(destination: FloatArray, tint: Int, contrast: Float, grade: CloudColorGrade) {
	destination.fill(0f)
	for (row in 0..2) {
		val shift = 16 - row * 8
		val shadow = ((grade.shadowColor ushr shift) and 255).toFloat()
		val highlight = ((grade.highlightColor ushr shift) and 255).toFloat()
		val multiply = ((tint ushr shift) and 255) / 255f
		val slope = grade.strength * (highlight - shadow) / 127f
		val factor = contrast * multiply
		val start = row * 5
		destination[start] = slope * 0.2126f * factor
		destination[start + 1] = slope * 0.7152f * factor
		destination[start + 2] = slope * 0.0722f * factor
		destination[start + row] += (1f - grade.strength) * factor
		destination[start + 4] = (contrast * (grade.strength * shadow - 128f * slope) + 128f * (1f - contrast)) * multiply
	}

	destination[18] = 1f
}

private const val LOW_SUN_CLOUD_SHADOW = 0xFF597999.toInt()
private const val LOW_SUN_CLOUD_HIGHLIGHT = 0xFFFFF6D5.toInt()
