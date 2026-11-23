package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

class FrostOnGlassSettingsTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore() = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile("frost-on-glass.preferences_pb")
	}

	@Test
	fun `frost defaults off and survives an appearance preset round trip`() = runTest {
		val store = dataStore()
		val settings = SettingsRepository(store)
		val defaults = settings.settings.first()
		assertFalse(defaults.frostOnGlassEnabled)

		settings.update(settingsMutationsBetween(defaults, defaults.copy(frostOnGlassEnabled = true)))
		val enabled = settings.settings.first()
		assertTrue(enabled.frostOnGlassEnabled)
		assertTrue(AppearancePresetRepository(store).create("Snowy glass", enabled.toAppearancePresetSnapshot()).isSuccess)

		settings.save(defaults)
		assertFalse(settings.settings.first().frostOnGlassEnabled)

		val bank = AppearancePresetRepository(store).state.first() as AppearancePresetStorageState.Ready
		settings.applyAppearancePreset(bank.presets.single().snapshot)
		assertTrue(settings.settings.first().frostOnGlassEnabled)
	}

	@Test
	fun `version nineteen presets without frost disable it when applied`() = runTest {
		val store = dataStore()
		val settings = SettingsRepository(store)
		val bank = AppearancePresetRepository(store)
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		assertTrue(bank.create("Legacy snow", AppSettings().toAppearancePresetSnapshot()).isSuccess)

		val saved = Json.parseToJsonElement(store.data.first()[key]!!).jsonObject
		val preset = saved.getValue("presets").jsonArray.single().jsonObject
		val legacySnapshot = JsonObject(preset.getValue("snapshot").jsonObject - "frostOnGlassEnabled")
		val legacy = buildJsonObject {
			put("schemaVersion", JsonPrimitive(19))
			put("presets", JsonArray(listOf(JsonObject(preset + ("snapshot" to legacySnapshot)))))
		}.toString()

		store.edit { it[key] = legacy }
		val restored = (bank.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertEquals(legacy, store.data.first()[key])

		settings.save(AppSettings(frostOnGlassEnabled = true))
		settings.applyAppearancePreset(restored)
		assertFalse(settings.settings.first().frostOnGlassEnabled)
	}
}
