package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.TreeStyle
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class TreesSettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile("trees-settings.preferences_pb")
	}

	@Test
	fun `existing settings do not gain trees and explicit choices survive reopening`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)
		assertFalse(repository.settings.first().treesEnabled)

		repository.save(AppSettings(backdropScene = BackdropScene.NONE, treesEnabled = true))
		assertTrue(SettingsRepository(dataStore).settings.first().treesEnabled)

		repository.update(listOf(SettingsMutation.TreesEnabled(false)))
		assertFalse(SettingsRepository(dataStore).settings.first().treesEnabled)
	}

	@Test
	fun `tree toggle from a stale appearance snapshot preserves a newer backdrop`() = runTest {
		val repository = SettingsRepository(dataStore())
		val stale = repository.settings.first()
		repository.update(listOf(SettingsMutation.Backdrop(BackdropScene.PHOTO)))
		repository.update(settingsMutationsBetween(stale, stale.copy(treesEnabled = true)))

		val persisted = repository.settings.first()
		assertTrue(persisted.treesEnabled)
		assertEquals(BackdropScene.PHOTO, persisted.backdropScene)
	}

	@Test
	fun `saved appearance presets restore trees in both directions without replacing weather preferences`() = runTest {
		val dataStore = dataStore()
		val presets = AppearancePresetRepository(dataStore)
		assertTrue(presets.create("Forest", AppSettings(treesEnabled = true).toAppearancePresetSnapshot()).isSuccess)
		assertTrue(presets.create("Open sky", AppSettings(treesEnabled = false).toAppearancePresetSnapshot()).isSuccess)
		val restored = (AppearancePresetRepository(dataStore).state.first() as AppearancePresetStorageState.Ready).presets
		val settings = SettingsRepository(dataStore)
		settings.save(AppSettings(updateIntervalMinutes = 180, manualLatitude = 41.9, manualLongitude = 12.5))

		settings.applyAppearancePreset(restored.first { it.name == "Forest" }.snapshot)
		assertTrue(settings.settings.first().treesEnabled)
		settings.applyAppearancePreset(restored.first { it.name == "Open sky" }.snapshot)

		val persisted = settings.settings.first()
		assertFalse(persisted.treesEnabled)
		assertEquals(180, persisted.updateIntervalMinutes)
		assertEquals(41.9, persisted.manualLatitude!!, 0.0)
		assertEquals(12.5, persisted.manualLongitude!!, 0.0)
	}

	@Test
	fun `tree style defaults to evergreen and explicit choices survive reopening`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)
		assertEquals(TreeStyle.EVERGREEN, repository.settings.first().treeStyle)

		repository.update(listOf(SettingsMutation.TreeStyle(TreeStyle.BROADLEAF)))
		assertEquals(TreeStyle.BROADLEAF, SettingsRepository(dataStore).settings.first().treeStyle)

		repository.update(listOf(SettingsMutation.TreeStyle(TreeStyle.EVERGREEN)))
		assertEquals(TreeStyle.EVERGREEN, SettingsRepository(dataStore).settings.first().treeStyle)
	}

	@Test
	fun `legacy settings missing tree style default to evergreen`() = runTest {
		val dataStore = dataStore()
		dataStore.edit { preferences ->
			preferences[booleanPreferencesKey("trees_enabled")] = true
		}

		val settings = SettingsRepository(dataStore).settings.first()
		assertTrue(settings.treesEnabled)
		assertEquals(TreeStyle.EVERGREEN, settings.treeStyle)
	}

	@Test
	fun `stale tree-style mutation preserves newer backdrop and tree toggle`() = runTest {
		val repository = SettingsRepository(dataStore())
		val stale = repository.settings.first()
		repository.update(listOf(SettingsMutation.Backdrop(BackdropScene.PHOTO), SettingsMutation.TreesEnabled(true)))
		repository.update(settingsMutationsBetween(stale, stale.copy(treeStyle = TreeStyle.BROADLEAF)))

		val persisted = repository.settings.first()
		assertEquals(TreeStyle.BROADLEAF, persisted.treeStyle)
		assertTrue(persisted.treesEnabled)
		assertEquals(BackdropScene.PHOTO, persisted.backdropScene)
	}

	@Test
	fun `disabling trees retains chosen tree style across updates and reopening`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)
		repository.save(AppSettings(treesEnabled = true, treeStyle = TreeStyle.BROADLEAF))
		assertEquals(TreeStyle.BROADLEAF, repository.settings.first().treeStyle)

		repository.update(listOf(SettingsMutation.TreesEnabled(false)))
		val persisted = SettingsRepository(dataStore).settings.first()
		assertFalse(persisted.treesEnabled)
		assertEquals(TreeStyle.BROADLEAF, persisted.treeStyle)
	}

	@Test
	fun `saved appearance presets preserve tree style when trees are disabled`() = runTest {
		val dataStore = dataStore()
		val presets = AppearancePresetRepository(dataStore)
		assertTrue(presets.create("Dormant broadleaf", AppSettings(treesEnabled = false, treeStyle = TreeStyle.BROADLEAF).toAppearancePresetSnapshot()).isSuccess)
		val restored = (AppearancePresetRepository(dataStore).state.first() as AppearancePresetStorageState.Ready).presets
		val snapshot = restored.first { it.name == "Dormant broadleaf" }.snapshot
		assertFalse(snapshot.treesEnabled)
		assertEquals(TreeStyle.BROADLEAF, snapshot.treeStyle)

		val settings = SettingsRepository(dataStore)
		settings.save(AppSettings(treesEnabled = true, treeStyle = TreeStyle.EVERGREEN))
		settings.applyAppearancePreset(snapshot)

		val persisted = settings.settings.first()
		assertFalse(persisted.treesEnabled)
		assertEquals(TreeStyle.BROADLEAF, persisted.treeStyle)
	}
}
