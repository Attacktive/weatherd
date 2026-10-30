package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class MoonOpacitySettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(name: String = "moon-opacity-settings.preferences_pb") = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile(name)
	}

	@Test
	fun `older installs default to opaque and choices survive save update and reopening`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		assertEquals(1f, repository.settings.first().moonOpacity, 0.0001f)

		repository.save(AppSettings(moonOpacity = 0.6f))
		assertEquals(0.6f, SettingsRepository(store).settings.first().moonOpacity, 0.0001f)

		repository.update(listOf(SettingsMutation.MoonOpacity(0.35f)))
		assertEquals(0.35f, SettingsRepository(store).settings.first().moonOpacity, 0.0001f)
	}

	@Test
	fun `invalid values normalize on write and when reading corrupt preferences`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		repository.save(AppSettings(moonOpacity = 0f))
		assertEquals(0.25f, repository.settings.first().moonOpacity, 0.0001f)

		repository.update(listOf(SettingsMutation.MoonOpacity(2f)))
		assertEquals(1f, repository.settings.first().moonOpacity, 0.0001f)

		repository.update(listOf(SettingsMutation.MoonOpacity(Float.NaN)))
		assertEquals(1f, repository.settings.first().moonOpacity, 0.0001f)

		store.edit { it[floatPreferencesKey("moon_opacity")] = Float.NEGATIVE_INFINITY }
		assertEquals(1f, SettingsRepository(store).settings.first().moonOpacity, 0.0001f)
	}

	@Test
	fun `stale appearance updates do not roll back unrelated settings`() = runTest {
		val repository = SettingsRepository(dataStore())
		val original = repository.settings.first()
		repository.update(listOf(SettingsMutation.Backdrop(BackdropScene.BEACH)))
		repository.update(settingsMutationsBetween(original, original.copy(moonOpacity = 0.45f)))
		assertEquals(BackdropScene.BEACH, repository.settings.first().backdropScene)
		assertEquals(0.45f, repository.settings.first().moonOpacity, 0.0001f)
	}

	@Test
	fun `appearance presets retain moon opacity without replacing weather preferences`() = runTest {
		val store = dataStore()
		val presets = AppearancePresetRepository(store)
		assertTrue(presets.create("Soft Moon", AppSettings(moonOpacity = 0.4f).toAppearancePresetSnapshot()).isSuccess)

		val saved = (AppearancePresetRepository(store).state.first() as AppearancePresetStorageState.Ready).presets.single()
		val settings = SettingsRepository(store)
		settings.save(AppSettings(updateIntervalMinutes = 180))
		settings.applyAppearancePreset(saved.snapshot)
		assertEquals(0.4f, settings.settings.first().moonOpacity, 0.0001f)
		assertEquals(180, settings.settings.first().updateIntervalMinutes)
	}

	@Test
	fun `version fourteen appearance presets missing moon opacity keep the existing look`() = runTest {
		val store = dataStore("legacy-moon-opacity.preferences_pb")
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val presets = AppearancePresetRepository(store)
		assertTrue(presets.create("Previous", AppSettings().toAppearancePresetSnapshot()).isSuccess)

		val current = store.data.first()[key]!!
		val parsed = Json.parseToJsonElement(current).jsonObject
		val v14Presets = parsed["presets"]!!.toString().replace(Regex(""""moonOpacity":\s*[\d.]+,?"""), "")
		val legacy = """{"schemaVersion":14,"presets":$v14Presets}"""
		store.edit { it[key] = legacy }

		val imported = (AppearancePresetRepository(store).state.first() as AppearancePresetStorageState.Ready).presets.single()
		assertEquals(1f, imported.snapshot.moonOpacity, 0.0001f)

		val settings = SettingsRepository(store)
		settings.save(AppSettings(moonOpacity = 0.5f))
		settings.applyAppearancePreset(imported.snapshot)
		assertEquals(1f, settings.settings.first().moonOpacity, 0.0001f)

		assertTrue(AppearancePresetRepository(store).rename(imported.id, "Migrated").isSuccess)
		val migrated = (AppearancePresetRepository(store).state.first() as AppearancePresetStorageState.Ready).presets.single()
		assertEquals("Migrated", migrated.name)
		assertEquals(1f, migrated.snapshot.moonOpacity, 0.0001f)
	}
}
