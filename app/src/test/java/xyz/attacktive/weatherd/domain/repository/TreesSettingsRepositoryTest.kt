package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
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
}
