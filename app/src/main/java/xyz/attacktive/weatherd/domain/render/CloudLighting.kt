package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import xyz.attacktive.weatherd.domain.model.DayPhase

/** RGB material endpoints; strength zero preserves the baked artwork. */
internal data class CloudColorGrade(val shadowColor: Int, val highlightColor: Int, val strength: Float) {
	companion object {
		val IDENTITY = CloudColorGrade(0, 0, 0f)
	}
}

/** Shares the sky's phase curves, with cool shadows and warm illuminated material at low sun. */
internal fun cloudColorGradeFor(dayPhase: DayPhase, celestialProgress: Float): CloudColorGrade = when (dayPhase) {
	DayPhase.DAY -> CloudColorGrade.IDENTITY
	DayPhase.NIGHT -> NIGHT_CLOUD_GRADE
	DayPhase.DAWN -> {
		val progress = (celestialProgress / 0.5f).coerceIn(0f, 1f)
		val warmth = progress * progress * (3f - 2f * progress)
		val strength = 1f - dawnDaylightStrength(celestialProgress)
		blendCloudGrade(NIGHT_CLOUD_GRADE, LOW_SUN_CLOUD_GRADE, warmth, strength)
	}

	DayPhase.DUSK -> {
		val nightfall = duskNightStrength(celestialProgress)
		val strength = maxOf(duskWarmStrength(celestialProgress), nightfall)
		blendCloudGrade(LOW_SUN_CLOUD_GRADE, NIGHT_CLOUD_GRADE, nightfall, strength)
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

private fun blendCloudGrade(from: CloudColorGrade, to: CloudColorGrade, progress: Float, strength: Float): CloudColorGrade {
	if (strength == 0f) {
		return CloudColorGrade.IDENTITY
	}

	return CloudColorGrade(blendCloudColor(from.shadowColor, to.shadowColor, progress), blendCloudColor(from.highlightColor, to.highlightColor, progress), strength)
}

private fun blendCloudColor(from: Int, to: Int, progress: Float): Int {
	var color = 0xFF000000.toInt()
	for (shift in 0..16 step 8) {
		val start = (from ushr shift) and 255
		val end = (to ushr shift) and 255
		color = color or ((start + (end - start) * progress).roundToInt() shl shift)
	}

	return color
}

private val LOW_SUN_CLOUD_GRADE = CloudColorGrade(0xFF597999.toInt(), 0xFFFFF6D5.toInt(), 1f)
private val NIGHT_CLOUD_GRADE = CloudColorGrade(0xFF1E283D.toInt(), 0xFF6D7D96.toInt(), 1f)
