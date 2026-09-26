package xyz.attacktive.weatherd.domain.model

enum class SkyColorPreset {
	NATURAL,
	WARM,
	PASTEL,
	CYBERPUNK;

	companion object {
		fun fromName(value: String?) = entries.firstOrNull { it.name == value } ?: NATURAL
	}
}

val NIGHT_BRIGHTNESS_SCALE_RANGE = 0f..1f
val SKY_BRIGHTNESS_SCALE_RANGE = 0.5f..1.5f
val SKY_SATURATION_SCALE_RANGE = 0.5f..1.5f
val CLOUD_CONTRAST_SCALE_RANGE = 0.5f..1.5f
