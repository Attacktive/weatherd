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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.TreeStyle
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class TreeStylePersistenceTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(name: String): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile(name)
	}

	@Test
	fun `missing and unknown tree style preferences default to rounded`() = runTest {
		val dataStore = dataStore("settings.preferences_pb")
		val repository = SettingsRepository(dataStore)

		assertEquals(TreeStyle.ROUNDED, repository.settings.first().treeStyle)

		dataStore.edit { preferences ->
			preferences[stringPreferencesKey("tree_style")] = "TOPIARY_9000"
		}

		assertEquals(TreeStyle.ROUNDED, repository.settings.first().treeStyle)
	}

	@Test
	fun `conifer tree style survives repository reopen without changing backdrop`() = runTest {
		val dataStore = dataStore("settings.preferences_pb")
		val repository = SettingsRepository(dataStore)

		repository.save(AppSettings(backdropScene = BackdropScene.MOUNTAINS, treeStyle = TreeStyle.CONIFER))

		val reopened = SettingsRepository(dataStore).settings.first()
		assertEquals(BackdropScene.MOUNTAINS, reopened.backdropScene)
		assertEquals(TreeStyle.CONIFER, reopened.treeStyle)
	}

	@Test
	fun `legacy schema three preset defaults tree style to rounded`() = runTest {
		val dataStore = dataStore("presets.preferences_pb")
		val repository = AppearancePresetRepository(dataStore)
		dataStore.edit { preferences ->
			preferences[stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)] = presetEnvelope(schemaVersion = 3, treeStyle = null)
		}

		val state = repository.state.first() as AppearancePresetStorageState.Ready
		assertEquals(TreeStyle.ROUNDED, state.presets.single().snapshot.treeStyle)
	}

	@Test
	fun `current preset schema round trips conifer`() = runTest {
		val dataStore = dataStore("presets.preferences_pb")
		val repository = AppearancePresetRepository(dataStore)

		val result = repository.create(
			name = "Pines",
			snapshot = AppSettings(backdropScene = BackdropScene.METROPOLIS, treeStyle = TreeStyle.CONIFER).toAppearancePresetSnapshot()
		)

		assertTrue(result.isSuccess)
		val state = repository.state.first() as AppearancePresetStorageState.Ready
		assertEquals(TreeStyle.CONIFER, state.presets.single().snapshot.treeStyle)
	}

	@Test
	fun `unknown tree style keeps current preset storage unreadable`() = runTest {
		val dataStore = dataStore("presets.preferences_pb")
		val repository = AppearancePresetRepository(dataStore)
		dataStore.edit { preferences ->
			preferences[stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)] = presetEnvelope(
				schemaVersion = APPEARANCE_PRESET_SCHEMA_VERSION,
				treeStyle = "TOPIARY_9000"
			)
		}

		assertEquals(
			AppearancePresetStorageState.Unreadable(AppearancePresetUnreadableReason.Malformed),
			repository.state.first()
		)
	}

	private fun presetEnvelope(schemaVersion: Int, treeStyle: String?): String {
		val treeStyleField = treeStyle?.let { ",\"treeStyle\":\"$it\"" } ?: ""

		return """{"schemaVersion":$schemaVersion,"presets":[{"id":"tree-style","name":"Tree style","snapshot":{"backdropScene":"METROPOLIS","showWeatherLabel":false,"showLocationLabel":false,"precipitationIntensityScale":1.0,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunColorPreset":"NATURAL","lensFlareEnabled":true,"lensFlareMotionEnabled":false,"distantAircraftEnabled":true$treeStyleField}}]}"""
	}
}
