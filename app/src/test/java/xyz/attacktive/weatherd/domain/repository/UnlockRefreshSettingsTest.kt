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
import androidx.datastore.preferences.core.intPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.defaultAppSettings
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockRefreshSettingsTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile("unlock-settings.preferences_pb")
	}

	@Test
	fun `new installations and old preferences default to disabled`() = runTest {
		val store = dataStore()
		assertFalse(AppSettings().refreshOnUnlockEnabled)
		assertFalse(SettingsRepository(store).settings.first().refreshOnUnlockEnabled)

		store.edit { it[intPreferencesKey("update_interval_minutes")] = 120 }

		val legacy = SettingsRepository(store).settings.first()
		assertFalse(legacy.refreshOnUnlockEnabled)
		assertEquals(120, legacy.updateIntervalMinutes)
	}

	@Test
	fun `the opt in persists and can be disabled after reopening`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		repository.update(listOf(SettingsMutation.RefreshOnUnlock(true)))

		assertTrue(SettingsRepository(store).settings.first().refreshOnUnlockEnabled)
		assertEquals(true, store.data.first()[booleanPreferencesKey("refresh_on_unlock_enabled")])

		SettingsRepository(store).update(listOf(SettingsMutation.RefreshOnUnlock(false)))

		assertFalse(SettingsRepository(store).settings.first().refreshOnUnlockEnabled)
	}

	@Test
	fun `full settings save retains the opt in`() = runTest {
		val store = dataStore()
		val settings = defaultAppSettings().copy(refreshOnUnlockEnabled = true, updateIntervalMinutes = 180)
		SettingsRepository(store).save(settings)

		assertEquals(settings, SettingsRepository(store).settings.first())
	}

	@Test
	fun `stale interval and unlock callbacks do not overwrite one another`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		val original = repository.settings.first()
		val unlock = settingsMutationsBetween(original, original.copy(refreshOnUnlockEnabled = true))
		val interval = settingsMutationsBetween(original, original.copy(updateIntervalMinutes = 120))
		assertEquals(listOf(SettingsMutation.RefreshOnUnlock(true)), unlock)

		for (changes in listOf(listOf(unlock, interval), listOf(interval, unlock))) {
			repository.save(original)
			changes.forEach { repository.update(it) }

			val settings = SettingsRepository(store).settings.first()
			assertTrue(settings.refreshOnUnlockEnabled)
			assertEquals(120, settings.updateIntervalMinutes)
		}
	}

	@Test
	fun `appearance presets and appearance reset cannot enable or disable unlock refresh`() = runTest {
		val repository = SettingsRepository(dataStore())
		for (enabled in listOf(false, true)) {
			repository.update(listOf(SettingsMutation.RefreshOnUnlock(enabled)))
			val preset = defaultAppSettings().copy(refreshOnUnlockEnabled = !enabled, showWeatherLabel = true).toAppearancePresetSnapshot()
			repository.applyAppearancePreset(preset)

			assertEquals(enabled, repository.settings.first().refreshOnUnlockEnabled)
			assertTrue(repository.settings.first().showWeatherLabel)

			repository.applyAppearancePreset(defaultAppSettings().toAppearancePresetSnapshot())

			assertEquals(enabled, repository.settings.first().refreshOnUnlockEnabled)
		}
	}
}
