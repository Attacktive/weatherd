package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.TreeSeason
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class TreeSeasonDefaultsMigrationTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
		migrations = listOf(TreeSeasonDefaultsMigration()),
		scope = backgroundScope
	) {
		temporaryFolder.newFile("season-migration.preferences_pb")
	}

	@Test
	fun `saving the first preset retains auto without a settings mutation`() = runTest {
		val dataStore = dataStore()
		val settings = SettingsRepository(dataStore)
		val presets = AppearancePresetRepository(dataStore)
		val before = settings.settings.first()
		assertEquals(TreeSeason.AUTO, before.treeSeason)

		assertTrue(presets.create("Initial appearance", before.toAppearancePresetSnapshot()).isSuccess)

		val after = SettingsRepository(dataStore).settings.first()
		assertEquals(TreeSeason.AUTO, after.treeSeason)
		assertEquals(TreeSeason.AUTO.name, dataStore.data.first()[TREE_SEASON_PREFERENCE_KEY])
		assertEquals(TreeSeason.AUTO, (presets.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot.treeSeason)

		settings.update(listOf(SettingsMutation.TreesEnabled(true)))
		assertEquals(TreeSeason.AUTO, SettingsRepository(dataStore).settings.first().treeSeason)
	}

	@Test
	fun `a preset as the very first writer still initializes auto`() = runTest {
		val dataStore = dataStore()
		val presets = AppearancePresetRepository(dataStore)
		assertTrue(presets.create("First preset", AppSettings().toAppearancePresetSnapshot()).isSuccess)

		assertEquals(TreeSeason.AUTO, SettingsRepository(dataStore).settings.first().treeSeason)
		assertEquals(TreeSeason.AUTO.name, dataStore.data.first()[TREE_SEASON_PREFERENCE_KEY])
	}

	@Test
	fun `migration preserves spring for existing settings and never overwrites a chosen season`() = runTest {
		val migration = TreeSeasonDefaultsMigration()
		val legacy = emptyPreferences().toMutablePreferences().apply {
			this[booleanPreferencesKey("trees_enabled")] = true
		}
		assertTrue(migration.shouldMigrate(legacy))

		val upgraded = migration.migrate(legacy)
		assertEquals(TreeSeason.SPRING.name, upgraded[TREE_SEASON_PREFERENCE_KEY])
		assertEquals(true, upgraded[booleanPreferencesKey("trees_enabled")])
		assertFalse(migration.shouldMigrate(upgraded))

		val explicit = emptyPreferences().toMutablePreferences().apply {
			this[TREE_SEASON_PREFERENCE_KEY] = TreeSeason.WINTER.name
		}
		assertFalse(migration.shouldMigrate(explicit))
	}

	@Test
	fun `old original value migrates to spring and empty stores initialize auto`() = runTest {
		val migration = TreeSeasonDefaultsMigration()
		val original = emptyPreferences().toMutablePreferences().apply {
			this[TREE_SEASON_PREFERENCE_KEY] = "ORIGINAL"
		}
		assertTrue(migration.shouldMigrate(original))
		assertEquals(TreeSeason.SPRING.name, migration.migrate(original)[TREE_SEASON_PREFERENCE_KEY])

		val fresh = migration.migrate(emptyPreferences())
		assertEquals(TreeSeason.AUTO.name, fresh[TREE_SEASON_PREFERENCE_KEY])
		assertFalse(migration.shouldMigrate(fresh))
	}
}
