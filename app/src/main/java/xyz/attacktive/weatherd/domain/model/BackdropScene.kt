package xyz.attacktive.weatherd.domain.model

/** What stands on the horizon behind the weather; [NONE] keeps the bare sky the app launched with, [PHOTO] hands the whole backdrop to the user's own images. */
enum class BackdropScene {
	NONE,
	METROPOLIS,
	BEACH,
	MOUNTAINS,
	COUNTRYSIDE,
	PHOTO,
	CLOUD_MOUNTAINS,
	TREESCAPE,
	LAKE;

	companion object {
		/** The scene stored under [name], or [NONE] when the value is absent or unrecognized (e.g. read by an older build after a downgrade). */
		fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: NONE
	}
}

/**
 * Whether this scene uses one of the legacy procedural silhouette renderers.
 * Bitmap scenery has dedicated layers; the bare sky and photos have no generated silhouette.
 */
val BackdropScene.drawsScenery
	get() = when (this) {
		BackdropScene.NONE, BackdropScene.PHOTO, BackdropScene.CLOUD_MOUNTAINS, BackdropScene.TREESCAPE, BackdropScene.LAKE -> false
		BackdropScene.METROPOLIS, BackdropScene.BEACH, BackdropScene.MOUNTAINS, BackdropScene.COUNTRYSIDE -> true
	}

/** Existing scenes remain selectable only while currently active, so older saved settings and appearance presets still work. */
fun availableBackdropScenes(current: BackdropScene) = buildList {
	add(BackdropScene.NONE)
	add(BackdropScene.PHOTO)
	add(BackdropScene.CLOUD_MOUNTAINS)
	add(BackdropScene.TREESCAPE)
	add(BackdropScene.LAKE)
	if (current.drawsScenery) {
		add(current)
	}
}
