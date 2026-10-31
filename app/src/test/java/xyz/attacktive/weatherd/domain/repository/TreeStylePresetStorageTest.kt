package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot
import xyz.attacktive.weatherd.domain.model.TreeSeason

@OptIn(ExperimentalCoroutinesApi::class)
class TreeStylePresetStorageTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Test
	fun `version seventeen presets without tree season remain readable and upgrade on edit`() = runTest {
		val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
			temporaryFolder.newFile("tree-season-legacy.preferences_pb")
		}

		val repository = AppearancePresetRepository(store)
		assertTrue(repository.create("Summer", AppSettings(treeSeason = TreeSeason.SUMMER).toAppearancePresetSnapshot()).isSuccess)
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val bank = Json.parseToJsonElement(checkNotNull(store.data.first()[key])).jsonObject
		val preset = bank.getValue("presets").jsonArray.single().jsonObject
		val snapshot = JsonObject(preset.getValue("snapshot").jsonObject - "treeSeason")
		val legacyPreset = JsonObject(preset + ("snapshot" to snapshot))
		val legacyBank = JsonObject(bank + ("schemaVersion" to JsonPrimitive(17)) + ("presets" to JsonArray(listOf(legacyPreset))))
		store.edit { it[key] = legacyBank.toString() }

		val loaded = (repository.state.first() as AppearancePresetStorageState.Ready).presets.single()
		assertEquals(TreeSeason.SPRING, loaded.snapshot.treeSeason)
		assertTrue(repository.replace(loaded.id, "Winter", loaded.snapshot.copy(treeSeason = TreeSeason.WINTER)).isSuccess)

		val upgraded = Json.parseToJsonElement(checkNotNull(store.data.first()[key])).jsonObject
		assertEquals(APPEARANCE_PRESET_SCHEMA_VERSION.toString(), upgraded.getValue("schemaVersion").jsonPrimitive.content)
		assertEquals(TreeSeason.WINTER, (repository.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot.treeSeason)
	}

	@Test
	fun `renaming a broadleaf preset preserves its stored tree style`() = runTest {
		val store = PreferenceDataStoreFactory.create(scope = backgroundScope) {
			temporaryFolder.newFile("tree-style-presets.preferences_pb")
		}

		val repository = AppearancePresetRepository(store)
		assertTrue(repository.create("Broadleaf", AppSettings(treesEnabled = true).toAppearancePresetSnapshot()).isSuccess)
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val bank = Json.parseToJsonElement(checkNotNull(store.data.first()[key])).jsonObject
		val preset = bank.getValue("presets").jsonArray.single().jsonObject
		val snapshot = JsonObject(preset.getValue("snapshot").jsonObject + ("treeStyle" to JsonPrimitive("BROADLEAF")))
		val broadleafBank = JsonObject(bank + ("presets" to JsonArray(listOf(JsonObject(preset + ("snapshot" to snapshot))))))
		store.edit { it[key] = broadleafBank.toString() }
		val id = (repository.state.first() as AppearancePresetStorageState.Ready).presets.single().id

		assertTrue(repository.rename(id, "Spreading trees").isSuccess)
		val rewritten = Json.parseToJsonElement(checkNotNull(store.data.first()[key])).jsonObject
		val restored = rewritten.getValue("presets").jsonArray.single().jsonObject.getValue("snapshot").jsonObject
		assertEquals("BROADLEAF", restored["treeStyle"]?.jsonPrimitive?.content)
	}
}
