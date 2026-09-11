package xyz.attacktive.weatherd.domain.model

/** What stands on the horizon behind the weather; [NONE] keeps the bare sky the app launched with, [PHOTO] hands the whole backdrop to the user's own images. */
enum class BackdropScene {
	NONE,
	METROPOLIS,
	BEACH,
	MOUNTAINS,
	COUNTRYSIDE,
	PHOTO;

	companion object {
		/** The scene stored under [name], or [NONE] when the value is absent or unrecognized (e.g. read by an older build after a downgrade). */
		fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: NONE
	}
}

/**
 * Whether this scene has a silhouette to raise on the horizon.
 * False for the two scenes that leave the frame behind the weather to something else: [BackdropScene.NONE] to the bare sky, [BackdropScene.PHOTO] to the user's photo.
 */
val BackdropScene.drawsScenery
	get() = when (this) {
		BackdropScene.NONE, BackdropScene.PHOTO -> false
		BackdropScene.METROPOLIS, BackdropScene.BEACH, BackdropScene.MOUNTAINS, BackdropScene.COUNTRYSIDE -> true
	}
