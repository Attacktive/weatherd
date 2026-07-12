package xyz.attacktive.weatherd.domain.model

/** What stands on the horizon behind the weather; [NONE] keeps the bare sky the app launched with. */
enum class BackdropScene {
	NONE,
	METROPOLIS,
	BEACH,
	MOUNTAINS;

	companion object {
		/** The scene stored under [name], or [NONE] when the value is absent or unrecognised (e.g. read by an older build after a downgrade). */
		fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: NONE
	}
}
