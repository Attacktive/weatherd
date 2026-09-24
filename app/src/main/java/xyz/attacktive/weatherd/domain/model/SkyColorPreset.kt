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
