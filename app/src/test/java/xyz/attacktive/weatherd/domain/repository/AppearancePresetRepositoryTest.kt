package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class AppearancePresetRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(name: String = "preset-settings.preferences_pb"): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile(name)
	}

	@Test
	fun `create rename replace delete and reopen preserve stable identity`() = runTest {
		val dataStore = dataStore()
		val repository = AppearancePresetRepository(dataStore)
		val firstSnapshot = AppSettings(backdropScene = BackdropScene.BEACH).toAppearancePresetSnapshot()
		val secondSnapshot = AppSettings(backdropScene = BackdropScene.MOUNTAINS, skyColorPreset = SkyColorPreset.PASTEL, glassDropletsEnabled = true, distantAircraftEnabled = false).toAppearancePresetSnapshot()

		assertTrue(repository.create("Coast", firstSnapshot).isSuccess)
		val created = (repository.state.first() as AppearancePresetStorageState.Ready).presets.single()
		assertTrue(repository.rename(created.id, "Evening coast").isSuccess)
		assertTrue(repository.replace(created.id, "Evening coast", secondSnapshot).isSuccess)

		val reopened = AppearancePresetRepository(dataStore)
		val replaced = (reopened.state.first() as AppearancePresetStorageState.Ready).presets.single()
		assertEquals(created.id, replaced.id)
		assertEquals("Evening coast", replaced.name)
		assertEquals(secondSnapshot, replaced.snapshot)
		assertTrue(replaced.snapshot.glassDropletsEnabled)
		assertFalse(replaced.snapshot.distantAircraftEnabled)

		assertTrue(reopened.delete(created.id).isSuccess)
		assertTrue((reopened.state.first() as AppearancePresetStorageState.Ready).presets.isEmpty())
	}

	@Test
	fun `versions one and two presets migrate aircraft to enabled`() = runTest {
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val versionOneDataStore = dataStore("preset-settings-v1.preferences_pb")
		val versionOneRaw = """{"schemaVersion":1,"presets":[{"id":"legacy-1","name":"Legacy 1","snapshot":{"backdropScene":"NONE","showWeatherLabel":false,"showLocationLabel":false,"precipitationIntensityScale":1.0,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunColorPreset":"NATURAL","lensFlareEnabled":true,"lensFlareMotionEnabled":false}}]}"""
		versionOneDataStore.edit { preferences ->
			preferences[key] = versionOneRaw
		}

		val versionOneSnapshot = (AppearancePresetRepository(versionOneDataStore).state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertTrue(versionOneSnapshot.distantAircraftEnabled)
		assertFalse(versionOneSnapshot.glassDropletsEnabled)

		val versionTwoDataStore = dataStore("preset-settings-v2.preferences_pb")
		val versionTwoRaw = """{"schemaVersion":2,"presets":[{"id":"legacy-2","name":"Legacy 2","snapshot":{"backdropScene":"NONE","showWeatherLabel":false,"showLocationLabel":false,"precipitationIntensityScale":1.0,"glassDropletsEnabled":true,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunColorPreset":"NATURAL","lensFlareEnabled":true,"lensFlareMotionEnabled":false}}]}"""
		versionTwoDataStore.edit { preferences ->
			preferences[key] = versionTwoRaw
		}

		val versionTwoSnapshot = (AppearancePresetRepository(versionTwoDataStore).state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertTrue(versionTwoSnapshot.distantAircraftEnabled)
		assertTrue(versionTwoSnapshot.glassDropletsEnabled)
	}

	@Test
	fun `current schema round trips explicit aircraft off`() = runTest {
		val dataStore = dataStore()
		val repository = AppearancePresetRepository(dataStore)
		val snapshot = AppSettings(distantAircraftEnabled = false).toAppearancePresetSnapshot()

		assertTrue(repository.create("No aircraft", snapshot).isSuccess)

		val reopened = AppearancePresetRepository(dataStore)
		val restored = (reopened.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertFalse(restored.distantAircraftEnabled)

		val raw = dataStore.data.first()[stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)]!!
		assertTrue(raw.contains("\"schemaVersion\":$APPEARANCE_PRESET_SCHEMA_VERSION"))
		assertTrue(raw.contains("\"distantAircraftEnabled\":false"))
	}

	@Test
	fun `duplicate names are rejected case-insensitively`() = runTest {
		val repository = AppearancePresetRepository(dataStore())
		val snapshot = AppSettings().toAppearancePresetSnapshot()

		assertTrue(repository.create("Storm", snapshot).isSuccess)
		assertTrue(repository.create("storm", snapshot).isFailure)
	}

	@Test
	fun `unicode-equivalent names are rejected without corrupting the preset bank`() = runTest {
		val repository = AppearancePresetRepository(dataStore())
		val snapshot = AppSettings().toAppearancePresetSnapshot()

		assertTrue(repository.create("İstanbul", snapshot).isSuccess)
		assertTrue(repository.create("i\u0307stanbul", snapshot).isFailure)

		val state = repository.state.first()
		assertTrue(state is AppearancePresetStorageState.Ready)
		assertEquals(listOf("İstanbul"), (state as AppearancePresetStorageState.Ready).presets.map { it.name })
	}

	@Test
	fun `malformed storage is reported and mutations leave the bytes untouched`() = runTest {
		val dataStore = dataStore()
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		dataStore.edit { preferences ->
			preferences[key] = "{ definitely-not-json"
		}
		val repository = AppearancePresetRepository(dataStore)
		val before = dataStore.data.first()[key]

		assertTrue(repository.state.first() is AppearancePresetStorageState.Unreadable)
		assertTrue(repository.create("Safe", AppSettings().toAppearancePresetSnapshot()).isFailure)
		assertEquals(before, dataStore.data.first()[key])
	}

	@Test
	fun `unsupported newer schema is reported and mutations leave the bytes untouched`() = runTest {
		val dataStore = dataStore()
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val raw = """{"schemaVersion":999,"presets":[]}"""
		dataStore.edit { preferences ->
			preferences[key] = raw
		}
		val repository = AppearancePresetRepository(dataStore)
		val state = repository.state.first()

		assertEquals(
			AppearancePresetStorageState.Unreadable(AppearancePresetUnreadableReason.UnsupportedVersion(999)),
			state
		)
		assertTrue(repository.delete("anything").isFailure)
		assertEquals(raw, dataStore.data.first()[key])
	}

	@Test
	fun `missing current-schema snapshot fields make storage unreadable`() = runTest {
		val dataStore = dataStore()
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		dataStore.edit { preferences ->
			preferences[key] = """{"schemaVersion":$APPEARANCE_PRESET_SCHEMA_VERSION,"presets":[{"id":"id","name":"Broken","snapshot":{}}]}"""
		}
		val repository = AppearancePresetRepository(dataStore)

		assertEquals(
			AppearancePresetStorageState.Unreadable(AppearancePresetUnreadableReason.Malformed),
			repository.state.first()
		)
	}
}
