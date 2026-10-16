package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import xyz.attacktive.weatherd.domain.model.BirdAppearanceInterval
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.SunStyle
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
		val secondSnapshot = AppSettings(backdropScene = BackdropScene.MOUNTAINS, skyColorPreset = SkyColorPreset.PASTEL, glassDropletsEnabled = true, distantAircraftEnabled = false, sunStyle = SunStyle.RADIANT).toAppearancePresetSnapshot()

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
		assertFalse(versionOneSnapshot.treesEnabled)
		assertEquals(SunStyle.CLASSIC, versionOneSnapshot.sunStyle)
		assertEquals(0.09f, versionOneSnapshot.labelVerticalPosition, 0.0001f)
		assertEquals(0f, versionOneSnapshot.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0f, versionOneSnapshot.statusBarShadeStrength, 0.0001f)

		val versionTwoDataStore = dataStore("preset-settings-v2.preferences_pb")
		val versionTwoRaw = """{"schemaVersion":2,"presets":[{"id":"legacy-2","name":"Legacy 2","snapshot":{"backdropScene":"NONE","showWeatherLabel":false,"showLocationLabel":false,"precipitationIntensityScale":1.0,"glassDropletsEnabled":true,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunColorPreset":"NATURAL","lensFlareEnabled":true,"lensFlareMotionEnabled":false}}]}"""
		versionTwoDataStore.edit { preferences ->
			preferences[key] = versionTwoRaw
		}

		val versionTwoSnapshot = (AppearancePresetRepository(versionTwoDataStore).state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertTrue(versionTwoSnapshot.distantAircraftEnabled)
		assertTrue(versionTwoSnapshot.glassDropletsEnabled)
		assertFalse(versionTwoSnapshot.treesEnabled)
		assertEquals(SunStyle.CLASSIC, versionTwoSnapshot.sunStyle)
		assertEquals(0.09f, versionTwoSnapshot.labelVerticalPosition, 0.0001f)
		assertEquals(0f, versionTwoSnapshot.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0f, versionTwoSnapshot.statusBarShadeStrength, 0.0001f)
	}

	@Test
	fun `version three presets missing trees field default trees to disabled`() = runTest {
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val legacyV3DataStore = dataStore("preset-settings-v3-legacy.preferences_pb")
		val legacyV3Raw = """{"schemaVersion":3,"presets":[{"id":"legacy-3","name":"Legacy 3","snapshot":{"backdropScene":"NONE","showWeatherLabel":false,"showLocationLabel":false,"precipitationIntensityScale":1.0,"glassDropletsEnabled":true,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunColorPreset":"NATURAL","lensFlareEnabled":true,"lensFlareMotionEnabled":false,"distantAircraftEnabled":true}}]}"""
		legacyV3DataStore.edit { preferences ->
			preferences[key] = legacyV3Raw
		}

		val legacyV3Snapshot = (AppearancePresetRepository(legacyV3DataStore).state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertFalse(legacyV3Snapshot.treesEnabled)
		assertTrue(legacyV3Snapshot.distantAircraftEnabled)
		assertTrue(legacyV3Snapshot.glassDropletsEnabled)
		assertEquals(SunStyle.CLASSIC, legacyV3Snapshot.sunStyle)
		assertEquals(0.09f, legacyV3Snapshot.labelVerticalPosition, 0.0001f)
		assertEquals(0f, legacyV3Snapshot.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0f, legacyV3Snapshot.statusBarShadeStrength, 0.0001f)
		assertEquals(legacyV3Raw, legacyV3DataStore.data.first()[key])

		val repository = AppearancePresetRepository(legacyV3DataStore)
		assertTrue(repository.rename("legacy-3", "Legacy forest").isSuccess)
		val rewritten = legacyV3DataStore.data.first()[key]!!
		assertEquals(APPEARANCE_PRESET_SCHEMA_VERSION, Json.parseToJsonElement(rewritten).jsonObject.getValue("schemaVersion").jsonPrimitive.int)
		val migrated = (AppearancePresetRepository(legacyV3DataStore).state.first() as AppearancePresetStorageState.Ready).presets.single()
		assertFalse(migrated.snapshot.treesEnabled)
		assertTrue(migrated.snapshot.glassDropletsEnabled)
		assertTrue(migrated.snapshot.distantAircraftEnabled)
	}

	@Test
	fun `version four presets retain their appearance and migrate sun style to classic`() = runTest {
		val store = dataStore()
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val raw = """{"schemaVersion":4,"presets":[{"id":"legacy-4","name":"Legacy 4","snapshot":{"backdropScene":"NONE","showWeatherLabel":false,"showLocationLabel":false,"precipitationIntensityScale":1.0,"glassDropletsEnabled":true,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunColorPreset":"NATURAL","lensFlareEnabled":true,"lensFlareMotionEnabled":false,"distantAircraftEnabled":false,"treesEnabled":true}}]}"""
		store.edit { it[key] = raw }

		val repository = AppearancePresetRepository(store)
		val snapshot = (repository.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertEquals(SunStyle.CLASSIC, snapshot.sunStyle)
		assertEquals(0.09f, snapshot.labelVerticalPosition, 0.0001f)
		assertTrue(snapshot.treesEnabled)
		assertFalse(snapshot.distantAircraftEnabled)
		assertEquals(raw, store.data.first()[key])
		assertTrue(repository.rename("legacy-4", "Migrated").isSuccess)
		assertEquals(APPEARANCE_PRESET_SCHEMA_VERSION, Json.parseToJsonElement(store.data.first()[key]!!).jsonObject.getValue("schemaVersion").jsonPrimitive.int)
		assertEquals(snapshot, (AppearancePresetRepository(store).state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot)
	}

	@Test
	fun `new preset schema preserves tree and aircraft choices while excluding older writers`() = runTest {
		val dataStore = dataStore()
		val repository = AppearancePresetRepository(dataStore)
		val snapshot = AppSettings(treesEnabled = true, distantAircraftEnabled = false, sunStyle = SunStyle.RADIANT).toAppearancePresetSnapshot()

		assertTrue(repository.create("No aircraft", snapshot).isSuccess)

		val reopened = AppearancePresetRepository(dataStore)
		val restored = (reopened.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertFalse(restored.distantAircraftEnabled)
		assertTrue(restored.treesEnabled)
		assertEquals(SunStyle.RADIANT, restored.sunStyle)
		assertEquals(0.09f, restored.labelVerticalPosition, 0.0001f)

		val raw = dataStore.data.first()[stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)]!!
		assertEquals(APPEARANCE_PRESET_SCHEMA_VERSION, Json.parseToJsonElement(raw).jsonObject.getValue("schemaVersion").jsonPrimitive.int)
	}

	@Test
	fun `version eight presets missing readability overlay fields default them to off`() = runTest {
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val store = dataStore("preset-settings-v8.preferences_pb")
		val raw = """{"schemaVersion":8,"presets":[{"id":"legacy-8","name":"Legacy 8","snapshot":{"backdropScene":"NONE","showWeatherLabel":true,"showLocationLabel":true,"precipitationIntensityScale":1.0,"glassDropletsEnabled":true,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunColorPreset":"NATURAL","sunStyle":"CLASSIC","lensFlareEnabled":true,"lensFlareMotionEnabled":false,"distantAircraftEnabled":true,"treesEnabled":false,"labelVerticalPosition":0.35}}]}"""
		store.edit { preferences ->
			preferences[key] = raw
		}

		val repository = AppearancePresetRepository(store)
		val snapshot = (repository.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertEquals(0f, snapshot.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0f, snapshot.statusBarShadeStrength, 0.0001f)
		assertEquals(0.35f, snapshot.labelVerticalPosition, 0.0001f)
		assertEquals(raw, store.data.first()[key])

		assertTrue(repository.rename("legacy-8", "Migrated 8").isSuccess)
		val rewritten = store.data.first()[key]!!
		assertEquals(APPEARANCE_PRESET_SCHEMA_VERSION, Json.parseToJsonElement(rewritten).jsonObject.getValue("schemaVersion").jsonPrimitive.int)
		val migrated = (AppearancePresetRepository(store).state.first() as AppearancePresetStorageState.Ready).presets.single()
		assertEquals(0f, migrated.snapshot.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0f, migrated.snapshot.statusBarShadeStrength, 0.0001f)
		assertEquals(0.35f, migrated.snapshot.labelVerticalPosition, 0.0001f)
	}

	@Test
	fun `version nine presets missing bird behavior fields default them to small and natural`() = runTest {
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val store = dataStore("preset-settings-v9.preferences_pb")
		val raw = """{"schemaVersion":9,"presets":[{"id":"legacy-9","name":"Legacy 9","snapshot":{"backdropScene":"NONE","showWeatherLabel":true,"showLocationLabel":true,"precipitationIntensityScale":1.0,"glassDropletsEnabled":true,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunColorPreset":"NATURAL","sunStyle":"CLASSIC","lensFlareEnabled":true,"lensFlareMotionEnabled":false,"distantAircraftEnabled":true,"treesEnabled":false,"labelVerticalPosition":0.35,"wallpaperDimmingStrength":0.1,"statusBarShadeStrength":0.2}}]}"""
		store.edit { preferences ->
			preferences[key] = raw
		}

		val repository = AppearancePresetRepository(store)
		val snapshot = (repository.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertEquals(BirdFlockSize.SMALL, snapshot.birdFlockSize)
		assertEquals(BirdAppearanceInterval.NATURAL, snapshot.birdAppearanceInterval)
		assertEquals(raw, store.data.first()[key])

		assertTrue(repository.rename("legacy-9", "Migrated 9").isSuccess)
		val rewritten = store.data.first()[key]!!
		assertEquals(APPEARANCE_PRESET_SCHEMA_VERSION, Json.parseToJsonElement(rewritten).jsonObject.getValue("schemaVersion").jsonPrimitive.int)
		val migrated = (AppearancePresetRepository(store).state.first() as AppearancePresetStorageState.Ready).presets.single()
		assertEquals(BirdFlockSize.SMALL, migrated.snapshot.birdFlockSize)
		assertEquals(BirdAppearanceInterval.NATURAL, migrated.snapshot.birdAppearanceInterval)
	}

	@Test
	fun `presets containing invalid bird behavior enum values make storage unreadable`() = runTest {
		val dataStore = dataStore()
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		dataStore.edit { preferences ->
			preferences[key] = """{"schemaVersion":10,"presets":[{"id":"bad-enum","name":"Bad Enum","snapshot":{"backdropScene":"NONE","showWeatherLabel":true,"showLocationLabel":true,"precipitationIntensityScale":1.0,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunColorPreset":"NATURAL","sunStyle":"CLASSIC","lensFlareEnabled":true,"lensFlareMotionEnabled":false,"distantAircraftEnabled":true,"treesEnabled":false,"birdFlockSize":"MASSIVE","birdAppearanceInterval":"NATURAL","labelVerticalPosition":0.09,"wallpaperDimmingStrength":0.0,"statusBarShadeStrength":0.0}}]}"""
		}
		val repository = AppearancePresetRepository(dataStore)

		assertEquals(
			AppearancePresetStorageState.Unreadable(AppearancePresetUnreadableReason.Malformed),
			repository.state.first()
		)
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
