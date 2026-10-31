package xyz.attacktive.weatherd.domain.repository

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import xyz.attacktive.weatherd.domain.model.TreeSeason

internal val TREE_SEASON_PREFERENCE_KEY = stringPreferencesKey("tree_season")

/**
 * Return the explicitly saved season, or infer the original tree appearance for pre-season preferences.
 * A truly empty store belongs to a new installation; older empty stores cannot be distinguished from new ones.
 */
internal fun storedTreeSeason(preferences: Preferences): TreeSeason {
	val raw = preferences[TREE_SEASON_PREFERENCE_KEY]
	if (raw == "ORIGINAL") {
		return TreeSeason.SPRING
	}

	return TreeSeason.entries.firstOrNull { it.name == raw } ?: if (preferences.asMap().isEmpty()) {
		TreeSeason.AUTO
	} else {
		TreeSeason.SPRING
	}
}

/** Pin the initial season before any DataStore writer can change the clean/legacy distinction. */
internal class TreeSeasonDefaultsMigration: DataMigration<Preferences> {
	override suspend fun shouldMigrate(currentData: Preferences) =
		currentData[TREE_SEASON_PREFERENCE_KEY] == null || currentData[TREE_SEASON_PREFERENCE_KEY] == "ORIGINAL"

	override suspend fun migrate(currentData: Preferences) = currentData.toMutablePreferences().apply {
		this[TREE_SEASON_PREFERENCE_KEY] = storedTreeSeason(currentData).name
	}

	override suspend fun cleanUp() = Unit
}
