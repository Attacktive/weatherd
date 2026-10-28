package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DEFAULT_SUN_HORIZONTAL_POSITION
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class SunHorizontalPositionSettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(name: String = "sun-horizontal-position-settings.preferences_pb") = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile(name)
	}

	@Test
	fun `older installs default to 0_72 and choices survive save update and reopening`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		assertEquals(DEFAULT_SUN_HORIZONTAL_POSITION, repository.settings.first().sunHorizontalPosition, 0.0001f)

		repository.save(AppSettings(sunHorizontalPosition = 0.3f))
		assertEquals(0.3f, SettingsRepository(store).settings.first().sunHorizontalPosition, 0.0001f)

		repository.update(listOf(SettingsMutation.SunHorizontalPosition(0.85f)))
		assertEquals(0.85f, SettingsRepository(store).settings.first().sunHorizontalPosition, 0.0001f)
	}

	@Test
	fun `invalid values normalize on write and when reading corrupt preferences`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		repository.save(AppSettings(sunHorizontalPosition = -0.5f))
		assertEquals(0f, repository.settings.first().sunHorizontalPosition, 0.0001f)

		repository.update(listOf(SettingsMutation.SunHorizontalPosition(1.5f)))
		assertEquals(1f, repository.settings.first().sunHorizontalPosition, 0.0001f)

		repository.update(listOf(SettingsMutation.SunHorizontalPosition(Float.NaN)))
		assertEquals(DEFAULT_SUN_HORIZONTAL_POSITION, repository.settings.first().sunHorizontalPosition, 0.0001f)

		store.edit { it[floatPreferencesKey("sun_horizontal_position")] = Float.NEGATIVE_INFINITY }
		assertEquals(DEFAULT_SUN_HORIZONTAL_POSITION, SettingsRepository(store).settings.first().sunHorizontalPosition, 0.0001f)
	}

	@Test
	fun `stale appearance updates do not roll back unrelated settings`() = runTest {
		val repository = SettingsRepository(dataStore())
		val original = repository.settings.first()
		repository.update(listOf(SettingsMutation.Backdrop(BackdropScene.BEACH)))
		repository.update(settingsMutationsBetween(original, original.copy(sunHorizontalPosition = 0.25f)))
		assertEquals(BackdropScene.BEACH, repository.settings.first().backdropScene)
		assertEquals(0.25f, repository.settings.first().sunHorizontalPosition, 0.0001f)
	}

	@Test
	fun `appearance presets retain sun horizontal position without replacing weather preferences`() = runTest {
		val store = dataStore()
		val presets = AppearancePresetRepository(store)
		assertTrue(presets.create("Custom Sun Position", AppSettings(sunHorizontalPosition = 0.15f).toAppearancePresetSnapshot()).isSuccess)

		val saved = (AppearancePresetRepository(store).state.first() as AppearancePresetStorageState.Ready).presets.single()
		val settings = SettingsRepository(store)
		settings.save(AppSettings(updateIntervalMinutes = 180))
		settings.applyAppearancePreset(saved.snapshot)
		assertEquals(0.15f, settings.settings.first().sunHorizontalPosition, 0.0001f)
		assertEquals(180, settings.settings.first().updateIntervalMinutes)
	}

	@Test
	fun `version fifteen appearance presets missing sun horizontal position keep the existing look`() = runTest {
		val store = dataStore("legacy-sun-horizontal-position.preferences_pb")
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val presets = AppearancePresetRepository(store)
		assertTrue(presets.create("Previous", AppSettings().toAppearancePresetSnapshot()).isSuccess)
		val current = store.data.first()[key]!!
		val parsed = Json.parseToJsonElement(current).jsonObject
		val v15Presets = parsed["presets"]!!.toString().replace(Regex(""""sunHorizontalPosition":\s*[\d.]+,?"""), "")
		val legacy = """{"schemaVersion":15,"presets":$v15Presets}"""
		val rechecked = Json.parseToJsonElement(legacy).jsonObject["presets"]!!
		assertTrue(rechecked.toString().contains("Previous"))
		assertTrue(!rechecked.toString().contains("sunHorizontalPosition"))
		store.edit { it[key] = legacy }
		val imported = (AppearancePresetRepository(store).state.first() as AppearancePresetStorageState.Ready).presets.single()
		assertEquals(DEFAULT_SUN_HORIZONTAL_POSITION, imported.snapshot.sunHorizontalPosition, 0.0001f)
		assertTrue(AppearancePresetRepository(store).rename(imported.id, "Migrated").isSuccess)
		assertTrue(store.data.first()[key]!!.contains("\"schemaVersion\":16"))
	}
}
