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
class RainbowSizeSettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(name: String = "rainbow-size-settings.preferences_pb") = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile(name)
	}

	@Test
	fun `existing settings missing rainbow size key default to 100 percent and explicit choices survive reopening`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)
		assertEquals(1.0f, repository.settings.first().rainbowSizeScale, 0.0001f)

		repository.save(AppSettings(rainbowSizeScale = 1.35f))
		val reopened = SettingsRepository(dataStore)
		assertEquals(1.35f, reopened.settings.first().rainbowSizeScale, 0.0001f)

		repository.update(listOf(SettingsMutation.RainbowSize(0.75f)))
		val afterUpdate = SettingsRepository(dataStore)
		assertEquals(0.75f, afterUpdate.settings.first().rainbowSizeScale, 0.0001f)

		repository.update(listOf(SettingsMutation.RainbowSize(1.0f)))
		val afterReset = SettingsRepository(dataStore)
		assertEquals(1.0f, afterReset.settings.first().rainbowSizeScale, 0.0001f)
	}

	@Test
	fun `rainbow size scale throughout valid range survives persistence without clipping`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		for (scale in listOf(0.6f, 0.8f, 1.0f, 1.2f, 1.4f)) {
			repository.save(AppSettings(rainbowSizeScale = scale))
			val settings = SettingsRepository(store).settings.first()
			assertEquals(scale, settings.rainbowSizeScale, 0.0001f)
		}
	}

	@Test
	fun `out-of-range and non-finite values are clamped or resolved to 100 percent on read and save`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)

		repository.save(AppSettings(rainbowSizeScale = 0.1f))
		val savedLow = SettingsRepository(dataStore).settings.first()
		assertEquals(0.6f, savedLow.rainbowSizeScale, 0.0001f)

		repository.save(AppSettings(rainbowSizeScale = 3.5f))
		val savedHigh = SettingsRepository(dataStore).settings.first()
		assertEquals(1.4f, savedHigh.rainbowSizeScale, 0.0001f)

		repository.update(listOf(SettingsMutation.RainbowSize(0.2f)))
		assertEquals(0.6f, repository.settings.first().rainbowSizeScale, 0.0001f)

		repository.update(listOf(SettingsMutation.RainbowSize(4.0f)))
		assertEquals(1.4f, repository.settings.first().rainbowSizeScale, 0.0001f)

		repository.save(AppSettings(rainbowSizeScale = Float.NaN))
		val savedNan = SettingsRepository(dataStore).settings.first()
		assertEquals(1.0f, savedNan.rainbowSizeScale, 0.0001f)

		repository.save(AppSettings(rainbowSizeScale = Float.POSITIVE_INFINITY))
		val savedInfinity = SettingsRepository(dataStore).settings.first()
		assertEquals(1.0f, savedInfinity.rainbowSizeScale, 0.0001f)

		repository.update(listOf(SettingsMutation.RainbowSize(Float.NEGATIVE_INFINITY)))
		assertEquals(1.0f, repository.settings.first().rainbowSizeScale, 0.0001f)

		dataStore.edit { preferences ->
			preferences[floatPreferencesKey("rainbow_size_scale")] = 0.25f
		}
		assertEquals(0.6f, SettingsRepository(dataStore).settings.first().rainbowSizeScale, 0.0001f)

		dataStore.edit { preferences ->
			preferences[floatPreferencesKey("rainbow_size_scale")] = 2.85f
		}
		assertEquals(1.4f, SettingsRepository(dataStore).settings.first().rainbowSizeScale, 0.0001f)

		dataStore.edit { preferences ->
			preferences[floatPreferencesKey("rainbow_size_scale")] = Float.NaN
		}
		assertEquals(1.0f, SettingsRepository(dataStore).settings.first().rainbowSizeScale, 0.0001f)
	}

	@Test
	fun `rainbow size scale mutation from a stale appearance snapshot preserves newer unrelated settings`() = runTest {
		val repository = SettingsRepository(dataStore())
		val initial = repository.settings.first()

		repository.update(listOf(SettingsMutation.Backdrop(BackdropScene.BEACH)))
		repository.update(settingsMutationsBetween(initial, initial.copy(rainbowSizeScale = 1.3f)))

		val persisted = repository.settings.first()
		assertEquals(1.3f, persisted.rainbowSizeScale, 0.0001f)
		assertEquals(BackdropScene.BEACH, persisted.backdropScene)
	}

	@Test
	fun `saved appearance presets capture and restore rainbow size scale in both directions without replacing weather preferences`() = runTest {
		val dataStore = dataStore()
		val presets = AppearancePresetRepository(dataStore)
		assertTrue(presets.create("Large Rainbow", AppSettings(rainbowSizeScale = 1.35f).toAppearancePresetSnapshot()).isSuccess)
		assertTrue(presets.create("Small Rainbow", AppSettings(rainbowSizeScale = 0.65f).toAppearancePresetSnapshot()).isSuccess)

		val restored = (AppearancePresetRepository(dataStore).state.first() as AppearancePresetStorageState.Ready).presets
		val settings = SettingsRepository(dataStore)
		settings.save(AppSettings(updateIntervalMinutes = 180, manualLatitude = 41.9, manualLongitude = 12.5))

		settings.applyAppearancePreset(restored.first { it.name == "Large Rainbow" }.snapshot)
		assertEquals(1.35f, settings.settings.first().rainbowSizeScale, 0.0001f)
		assertEquals(180, settings.settings.first().updateIntervalMinutes)

		settings.applyAppearancePreset(restored.first { it.name == "Small Rainbow" }.snapshot)
		val persisted = settings.settings.first()
		assertEquals(0.65f, persisted.rainbowSizeScale, 0.0001f)
		assertEquals(180, persisted.updateIntervalMinutes)
		assertEquals(41.9, persisted.manualLatitude!!, 0.0)
		assertEquals(12.5, persisted.manualLongitude!!, 0.0)
	}

	@Test
	fun `legacy version thirteen appearance preset JSON missing rainbow size scale defaults to 100 percent and migrates on rewrite`() = runTest {
		val store = dataStore("preset-settings-v13.preferences_pb")
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val legacyV13Raw = """{"schemaVersion":13,"presets":[{"id":"legacy-13","name":"Legacy 13","snapshot":{"backdropScene":"NONE","showWeatherLabel":true,"showLocationLabel":true,"precipitationIntensityScale":1.0,"glassDropletsEnabled":true,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"moonSizeScale":1.0,"sunColorPreset":"NATURAL","sunStyle":"CLASSIC","sunbeamMode":"THROUGH_CLOUDS","lensFlareEnabled":true,"lensFlareMotionEnabled":false,"distantAircraftEnabled":true,"birdFlockSize":"SMALL","birdAppearanceInterval":"NATURAL","treesEnabled":false,"treeStyle":"EVERGREEN","labelVerticalPosition":0.09,"wallpaperDimmingStrength":0.0,"statusBarShadeStrength":0.0}}]}"""
		store.edit { preferences ->
			preferences[key] = legacyV13Raw
		}

		val repository = AppearancePresetRepository(store)
		val snapshot = (repository.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertEquals(1.0f, snapshot.rainbowSizeScale, 0.0001f)
		assertEquals(legacyV13Raw, store.data.first()[key])

		assertTrue(repository.rename("legacy-13", "Migrated 13").isSuccess)
		val rewritten = store.data.first()[key]!!
		assertEquals(APPEARANCE_PRESET_SCHEMA_VERSION, Json.parseToJsonElement(rewritten).jsonObject.getValue("schemaVersion").jsonPrimitive.int)
		val migrated = (AppearancePresetRepository(store).state.first() as AppearancePresetStorageState.Ready).presets.single()
		assertEquals(1.0f, migrated.snapshot.rainbowSizeScale, 0.0001f)
	}
}
