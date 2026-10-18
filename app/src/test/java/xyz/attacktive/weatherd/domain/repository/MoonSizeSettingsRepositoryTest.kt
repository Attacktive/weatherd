package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
class MoonSizeSettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(name: String = "moon-size-settings.preferences_pb") = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile(name)
	}

	@Test
	fun `existing settings missing moon size key default to 100 percent and explicit choices survive reopening`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)
		assertEquals(1.0f, repository.settings.first().moonSizeScale, 0.0001f)

		repository.save(AppSettings(moonSizeScale = 1.45f))
		val reopened = SettingsRepository(dataStore)
		assertEquals(1.45f, reopened.settings.first().moonSizeScale, 0.0001f)

		repository.update(listOf(SettingsMutation.MoonSize(0.75f)))
		val afterUpdate = SettingsRepository(dataStore)
		assertEquals(0.75f, afterUpdate.settings.first().moonSizeScale, 0.0001f)
	}

	@Test
	fun `moon size scale throughout valid range survives persistence without clipping`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		for (scale in listOf(0.5f, 0.8f, 1.0f, 1.6f, 2.0f)) {
			repository.save(AppSettings(moonSizeScale = scale))
			val settings = SettingsRepository(store).settings.first()
			assertEquals(scale, settings.moonSizeScale, 0.0001f)
		}
	}

	@Test
	fun `out-of-range and non-finite values are clamped or resolved to 100 percent on read and save`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)

		repository.save(AppSettings(moonSizeScale = 0.1f))
		val savedLow = SettingsRepository(dataStore).settings.first()
		assertEquals(0.5f, savedLow.moonSizeScale, 0.0001f)

		repository.save(AppSettings(moonSizeScale = 3.5f))
		val savedHigh = SettingsRepository(dataStore).settings.first()
		assertEquals(2.0f, savedHigh.moonSizeScale, 0.0001f)

		repository.update(listOf(SettingsMutation.MoonSize(0.2f)))
		assertEquals(0.5f, repository.settings.first().moonSizeScale, 0.0001f)

		repository.update(listOf(SettingsMutation.MoonSize(4.0f)))
		assertEquals(2.0f, repository.settings.first().moonSizeScale, 0.0001f)

		repository.save(AppSettings(moonSizeScale = Float.NaN))
		val savedNan = SettingsRepository(dataStore).settings.first()
		assertEquals(1.0f, savedNan.moonSizeScale, 0.0001f)

		repository.save(AppSettings(moonSizeScale = Float.POSITIVE_INFINITY))
		val savedInfinity = SettingsRepository(dataStore).settings.first()
		assertEquals(1.0f, savedInfinity.moonSizeScale, 0.0001f)

		repository.update(listOf(SettingsMutation.MoonSize(Float.NEGATIVE_INFINITY)))
		assertEquals(1.0f, repository.settings.first().moonSizeScale, 0.0001f)

		dataStore.edit { preferences ->
			preferences[floatPreferencesKey("moon_size_scale")] = 0.25f
		}
		assertEquals(0.5f, SettingsRepository(dataStore).settings.first().moonSizeScale, 0.0001f)

		dataStore.edit { preferences ->
			preferences[floatPreferencesKey("moon_size_scale")] = 2.85f
		}
		assertEquals(2.0f, SettingsRepository(dataStore).settings.first().moonSizeScale, 0.0001f)

		dataStore.edit { preferences ->
			preferences[floatPreferencesKey("moon_size_scale")] = Float.NaN
		}
		assertEquals(1.0f, SettingsRepository(dataStore).settings.first().moonSizeScale, 0.0001f)
	}

	@Test
	fun `moon size scale mutation from a stale appearance snapshot preserves newer unrelated settings`() = runTest {
		val repository = SettingsRepository(dataStore())
		val initial = repository.settings.first()

		repository.update(listOf(SettingsMutation.Backdrop(BackdropScene.BEACH)))
		repository.update(settingsMutationsBetween(initial, initial.copy(moonSizeScale = 1.75f)))

		val persisted = repository.settings.first()
		assertEquals(1.75f, persisted.moonSizeScale, 0.0001f)
		assertEquals(BackdropScene.BEACH, persisted.backdropScene)
	}

	@Test
	fun `saved appearance presets capture and restore moon size scale in both directions without replacing weather preferences`() = runTest {
		val dataStore = dataStore()
		val presets = AppearancePresetRepository(dataStore)
		assertTrue(presets.create("Giant Moon", AppSettings(moonSizeScale = 1.9f).toAppearancePresetSnapshot()).isSuccess)
		assertTrue(presets.create("Small Moon", AppSettings(moonSizeScale = 0.6f).toAppearancePresetSnapshot()).isSuccess)

		val restored = (AppearancePresetRepository(dataStore).state.first() as AppearancePresetStorageState.Ready).presets
		val settings = SettingsRepository(dataStore)
		settings.save(AppSettings(updateIntervalMinutes = 180, manualLatitude = 41.9, manualLongitude = 12.5))

		settings.applyAppearancePreset(restored.first { it.name == "Giant Moon" }.snapshot)
		assertEquals(1.9f, settings.settings.first().moonSizeScale, 0.0001f)
		assertEquals(180, settings.settings.first().updateIntervalMinutes)

		settings.applyAppearancePreset(restored.first { it.name == "Small Moon" }.snapshot)
		val persisted = settings.settings.first()
		assertEquals(0.6f, persisted.moonSizeScale, 0.0001f)
		assertEquals(180, persisted.updateIntervalMinutes)
		assertEquals(41.9, persisted.manualLatitude!!, 0.0)
		assertEquals(12.5, persisted.manualLongitude!!, 0.0)
	}

	@Test
	fun `legacy version eleven appearance preset JSON missing moon size scale defaults to 100 percent and migrates on rewrite`() = runTest {
		val store = dataStore("preset-settings-v11.preferences_pb")
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val legacyV11Raw = """{"schemaVersion":11,"presets":[{"id":"legacy-11","name":"Legacy 11","snapshot":{"backdropScene":"NONE","showWeatherLabel":true,"showLocationLabel":true,"precipitationIntensityScale":1.0,"glassDropletsEnabled":true,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunColorPreset":"NATURAL","sunStyle":"CLASSIC","sunbeamMode":"THROUGH_CLOUDS","lensFlareEnabled":true,"lensFlareMotionEnabled":false,"distantAircraftEnabled":true,"treesEnabled":false,"birdFlockSize":"SMALL","birdAppearanceInterval":"NATURAL","labelVerticalPosition":0.09,"wallpaperDimmingStrength":0.0,"statusBarShadeStrength":0.0}}]}"""
		store.edit { preferences ->
			preferences[key] = legacyV11Raw
		}

		val repository = AppearancePresetRepository(store)
		val snapshot = (repository.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertEquals(1.0f, snapshot.moonSizeScale, 0.0001f)
		assertEquals(legacyV11Raw, store.data.first()[key])

		assertTrue(repository.rename("legacy-11", "Migrated 11").isSuccess)
		val rewritten = store.data.first()[key]!!
		assertEquals(APPEARANCE_PRESET_SCHEMA_VERSION, Json.parseToJsonElement(rewritten).jsonObject.getValue("schemaVersion").jsonPrimitive.int)
		val migrated = (AppearancePresetRepository(store).state.first() as AppearancePresetStorageState.Ready).presets.single()
		assertEquals(1.0f, migrated.snapshot.moonSizeScale, 0.0001f)
	}
}
