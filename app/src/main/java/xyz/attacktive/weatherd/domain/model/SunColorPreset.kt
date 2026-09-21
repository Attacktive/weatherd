package xyz.attacktive.weatherd.domain.model

enum class SunColorPreset {
	NATURAL,
	WHITE,
	GOLDEN,
	ORANGE;

	companion object {
		fun fromName(value: String?): SunColorPreset = entries.firstOrNull { it.name == value } ?: NATURAL
	}
}

val SUN_SIZE_SCALE_RANGE = 0.5f..2f
