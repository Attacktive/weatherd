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
import xyz.attacktive.weatherd.domain.model.SunStyle
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class LabelVerticalPositionSettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(name: String = "label-position-settings.preferences_pb") = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile(name)
	}

	@Test
	fun `explicit position persists and survives reopening`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)

		repository.save(AppSettings(labelVerticalPosition = 0.45f))
		assertEquals(0.45f, SettingsRepository(dataStore).settings.first().labelVerticalPosition, 0.0001f)

		repository.update(listOf(SettingsMutation.LabelVerticalPosition(0.72f)))
		assertEquals(0.72f, SettingsRepository(dataStore).settings.first().labelVerticalPosition, 0.0001f)
	}

	@Test
	fun `positions throughout the full travel survive persistence without clipping`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		for (position in listOf(0f, 0.01f, 0.99f, 1f)) {
			repository.save(AppSettings(labelVerticalPosition = position))
			assertEquals(position, SettingsRepository(store).settings.first().labelVerticalPosition, 0.0001f)
			val mutatedPosition = 1f - position
			repository.update(listOf(SettingsMutation.LabelVerticalPosition(mutatedPosition)))
			assertEquals(mutatedPosition, repository.settings.first().labelVerticalPosition, 0.0001f)
		}
	}

	@Test
	fun `out-of-range values are clamped on read and save`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)

		repository.save(AppSettings(labelVerticalPosition = -0.01f))
		assertEquals(0f, SettingsRepository(dataStore).settings.first().labelVerticalPosition, 0.0001f)

		repository.save(AppSettings(labelVerticalPosition = 1.01f))
		assertEquals(1f, SettingsRepository(dataStore).settings.first().labelVerticalPosition, 0.0001f)

		repository.update(listOf(SettingsMutation.LabelVerticalPosition(-0.5f)))
		assertEquals(0f, SettingsRepository(dataStore).settings.first().labelVerticalPosition, 0.0001f)

		repository.update(listOf(SettingsMutation.LabelVerticalPosition(1.5f)))
		assertEquals(1f, SettingsRepository(dataStore).settings.first().labelVerticalPosition, 0.0001f)

		dataStore.edit { preferences ->
			preferences[floatPreferencesKey("label_vertical_position")] = -0.02f
		}

		assertEquals(0f, SettingsRepository(dataStore).settings.first().labelVerticalPosition, 0.0001f)
	}

	@Test
	fun `stale-snapshot update preserves newer unrelated setting`() = runTest {
		val repository = SettingsRepository(dataStore())
		val initial = repository.settings.first()

		repository.update(listOf(SettingsMutation.Backdrop(BackdropScene.PHOTO)))
		repository.update(settingsMutationsBetween(initial, initial.copy(labelVerticalPosition = 0.6f)))

		val persisted = repository.settings.first()
		assertEquals(0.6f, persisted.labelVerticalPosition, 0.0001f)
		assertEquals(BackdropScene.PHOTO, persisted.backdropScene)
	}

	@Test
	fun `applying preset restores position while preserving nonappearance state`() = runTest {
		val dataStore = dataStore()
		val presets = AppearancePresetRepository(dataStore)
		val customPreset = AppSettings(labelVerticalPosition = 0.55f).toAppearancePresetSnapshot()

		assertTrue(presets.create("Centered labels", customPreset).isSuccess)
		val restoredPresets = (AppearancePresetRepository(dataStore).state.first() as AppearancePresetStorageState.Ready).presets
		val settings = SettingsRepository(dataStore)
		settings.save(AppSettings(updateIntervalMinutes = 180, manualLatitude = 41.9, manualLongitude = 12.5, labelVerticalPosition = 0.09f))

		settings.applyAppearancePreset(restoredPresets.first { it.name == "Centered labels" }.snapshot)

		val persisted = settings.settings.first()
		assertEquals(0.55f, persisted.labelVerticalPosition, 0.0001f)
		assertEquals(180, persisted.updateIntervalMinutes)
		assertEquals(41.9, persisted.manualLatitude!!, 0.0)
		assertEquals(12.5, persisted.manualLongitude!!, 0.0)
	}

	@Test
	fun `current preset schema serializes and round-trips position`() = runTest {
		val dataStore = dataStore()
		val repository = AppearancePresetRepository(dataStore)
		val snapshot = AppSettings(labelVerticalPosition = 0.8f, sunStyle = SunStyle.RADIANT).toAppearancePresetSnapshot()

		assertTrue(repository.create("Shifted", snapshot).isSuccess)

		val reopened = AppearancePresetRepository(dataStore)
		val restored = (reopened.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertEquals(0.8f, restored.labelVerticalPosition, 0.0001f)
		assertEquals(SunStyle.RADIANT, restored.sunStyle)

		val raw = dataStore.data.first()[stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)]!!
		assertEquals(8, Json.parseToJsonElement(raw).jsonObject.getValue("schemaVersion").jsonPrimitive.int)
	}

	@Test
	fun `version five presets migrate missing position to default 0_09f`() = runTest {
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val store = dataStore("preset-settings-v5.preferences_pb")
		val raw = """{"schemaVersion":5,"presets":[{"id":"legacy-5","name":"Legacy 5","snapshot":{"backdropScene":"NONE","showWeatherLabel":false,"showLocationLabel":false,"precipitationIntensityScale":1.0,"glassDropletsEnabled":true,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunColorPreset":"NATURAL","sunStyle":"CLASSIC","lensFlareEnabled":true,"lensFlareMotionEnabled":false,"distantAircraftEnabled":true,"treesEnabled":false}}]}"""
		store.edit { preferences ->
			preferences[key] = raw
		}

		val repository = AppearancePresetRepository(store)
		val snapshot = (repository.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertEquals(0.09f, snapshot.labelVerticalPosition, 0.0001f)
		assertEquals(raw, store.data.first()[key])

		assertTrue(repository.rename("legacy-5", "Migrated 5").isSuccess)
		val rewritten = store.data.first()[key]!!
		assertEquals(8, Json.parseToJsonElement(rewritten).jsonObject.getValue("schemaVersion").jsonPrimitive.int)
		val migrated = (AppearancePresetRepository(store).state.first() as AppearancePresetStorageState.Ready).presets.single()
		assertEquals(0.09f, migrated.snapshot.labelVerticalPosition, 0.0001f)
	}

	@Test
	fun `version six and seven presets retain numeric positions under anchored travel`() = runTest {
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		for (version in listOf(6, 7)) {
			val store = dataStore("preset-settings-v$version.preferences_pb")
			val raw = """{"schemaVersion":$version,"presets":[{"id":"legacy-$version","name":"Legacy $version","snapshot":{"backdropScene":"NONE","showWeatherLabel":true,"showLocationLabel":true,"precipitationIntensityScale":1.0,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunColorPreset":"NATURAL","sunStyle":"RADIANT","lensFlareEnabled":true,"lensFlareMotionEnabled":false,"labelVerticalPosition":0.75}}]}"""
			store.edit { it[key] = raw }

			val repository = AppearancePresetRepository(store)
			val snapshot = (repository.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
			assertEquals(0.75f, snapshot.labelVerticalPosition, 0.0001f)
			assertEquals(SunStyle.RADIANT, snapshot.sunStyle)
			assertTrue(repository.rename("legacy-$version", "Anchored").isSuccess)
			val rewritten = store.data.first()[key]!!
			assertEquals(8, Json.parseToJsonElement(rewritten).jsonObject.getValue("schemaVersion").jsonPrimitive.int)
			assertEquals(0.75f, (repository.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot.labelVerticalPosition, 0.0001f)
		}
	}
}
