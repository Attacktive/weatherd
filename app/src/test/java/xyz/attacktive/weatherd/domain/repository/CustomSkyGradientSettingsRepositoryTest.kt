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
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.CustomSkyGradient
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SkyGradientDirection
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class CustomSkyGradientSettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(name: String = "custom-sky-gradient-settings.preferences_pb") = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile(name)
	}

	@Test
	fun `reopening store retains all applied stops colors direction and mode`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		val customGradient = CustomSkyGradient(
			startColor = 0xFF123456.toInt(),
			middleColor = 0xFF654321.toInt(),
			endColor = 0xFFABCDEF.toInt(),
			threeColors = true,
			startPosition = 0.15f,
			middlePosition = 0.45f,
			endPosition = 0.85f,
			direction = SkyGradientDirection.DIAGONAL
		)

		repository.update(
			listOf(
				SettingsMutation.SkyColor(SkyColorPreset.CUSTOM),
				SettingsMutation.CustomSky(customGradient)
			)
		)

		val reopenedRepository = SettingsRepository(store)
		val restored = reopenedRepository.settings.first()

		assertEquals(SkyColorPreset.CUSTOM, restored.skyColorPreset)
		assertEquals(customGradient, restored.customSkyGradient)
	}

	@Test
	fun `changing unrelated settings cannot lose gradient`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		val customGradient = CustomSkyGradient(
			startColor = 0xFF112233.toInt(),
			middleColor = 0xFF445566.toInt(),
			endColor = 0xFF778899.toInt(),
			threeColors = true,
			startPosition = 0.2f,
			middlePosition = 0.5f,
			endPosition = 0.8f,
			direction = SkyGradientDirection.HORIZONTAL
		)

		repository.update(listOf(SettingsMutation.CustomSky(customGradient)))

		repository.update(
			listOf(
				SettingsMutation.PrecipitationIntensity(1.5f),
				SettingsMutation.SkyBrightness(1.2f),
				SettingsMutation.TreesEnabled(true),
				SettingsMutation.Backdrop(BackdropScene.MOUNTAINS)
			)
		)

		val settings = SettingsRepository(store).settings.first()

		assertEquals(customGradient, settings.customSkyGradient)
		assertEquals(1.5f, settings.precipitationIntensityScale, 0.0001f)
		assertEquals(1.2f, settings.skyBrightnessScale, 0.0001f)
		assertTrue(settings.treesEnabled)
		assertEquals(BackdropScene.MOUNTAINS, settings.backdropScene)
	}

	@Test
	fun `corrupt preferences storage falls back safely to default custom sky gradient`() = runTest {
		val store = dataStore()
		val key = stringPreferencesKey("custom_sky_gradient")

		store.edit { preferences ->
			preferences[key] = "not-valid-json"
		}

		val repository = SettingsRepository(store)

		assertEquals(CustomSkyGradient(), repository.settings.first().customSkyGradient)
	}

	@Test
	fun `preset roundtrip includes custom gradient even when built-in preset is selected`() = runTest {
		val store = dataStore()
		val presetRepository = AppearancePresetRepository(store)
		val customGradient = CustomSkyGradient(
			startColor = 0xFF00FF00.toInt(),
			middleColor = 0xFF0000FF.toInt(),
			endColor = 0xFFFF0000.toInt(),
			threeColors = true,
			startPosition = 0.1f,
			middlePosition = 0.6f,
			endPosition = 0.9f,
			direction = SkyGradientDirection.DIAGONAL
		)

		val snapshot = AppSettings(
			skyColorPreset = SkyColorPreset.NATURAL,
			customSkyGradient = customGradient
		).toAppearancePresetSnapshot()

		assertTrue(presetRepository.create("Natural with hidden custom", snapshot).isSuccess)

		val reloadedPresets = AppearancePresetRepository(store)
		val reloadedPreset = (reloadedPresets.state.first() as AppearancePresetStorageState.Ready).presets.single()

		assertEquals(SkyColorPreset.NATURAL, reloadedPreset.snapshot.skyColorPreset)
		assertEquals(customGradient, reloadedPreset.snapshot.customSkyGradient)

		val settingsRepository = SettingsRepository(store)
		settingsRepository.applyAppearancePreset(reloadedPreset.snapshot)

		val finalSettings = settingsRepository.settings.first()

		assertEquals(SkyColorPreset.NATURAL, finalSettings.skyColorPreset)
		assertEquals(customGradient, finalSettings.customSkyGradient)
	}

	@Test
	fun `version eighteen presets missing custom sky gradient default to safe defaults`() = runTest {
		val store = dataStore()
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val raw = """{"schemaVersion":18,"presets":[{"id":"legacy-18","name":"Legacy 18","snapshot":{"backdropScene":"NONE","showWeatherLabel":true,"showLocationLabel":true,"precipitationIntensityScale":1.0,"glassDropletsEnabled":true,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunHorizontalPosition":0.72,"moonSizeScale":1.0,"moonOpacity":1.0,"sunColorPreset":"NATURAL","sunStyle":"CLASSIC","sunbeamMode":"THROUGH_CLOUDS","lensFlareEnabled":true,"rainbowSizeScale":1.0,"lensFlareMotionEnabled":false,"distantAircraftEnabled":true,"birdFlockSize":"SMALL","birdAppearanceInterval":"NATURAL","treesEnabled":false,"treeStyle":"EVERGREEN","treeSeason":"AUTO","labelVerticalPosition":0.09,"wallpaperDimmingStrength":0.0,"statusBarShadeStrength":0.0,"seasonalCelebrationsEnabled":true}}]}"""

		store.edit { preferences ->
			preferences[key] = raw
		}

		val repository = AppearancePresetRepository(store)
		val state = repository.state.first() as AppearancePresetStorageState.Ready
		val snapshot = state.presets.single().snapshot

		assertEquals(CustomSkyGradient(), snapshot.customSkyGradient)
		assertEquals(raw, store.data.first()[key])

		assertTrue(repository.rename("legacy-18", "Migrated 18").isSuccess)

		val rewritten = store.data.first()[key]!!

		assertEquals(APPEARANCE_PRESET_SCHEMA_VERSION, Json.parseToJsonElement(rewritten).jsonObject.getValue("schemaVersion").jsonPrimitive.int)

		val migrated = (AppearancePresetRepository(store).state.first() as AppearancePresetStorageState.Ready).presets.single()

		assertEquals(CustomSkyGradient(), migrated.snapshot.customSkyGradient)
	}

	@Test
	fun `presets containing invalid gradient direction enum values make storage unreadable`() = runTest {
		val store = dataStore()
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val raw = """{"schemaVersion":$APPEARANCE_PRESET_SCHEMA_VERSION,"presets":[{"id":"bad-direction","name":"Bad Direction","snapshot":{"backdropScene":"NONE","showWeatherLabel":true,"showLocationLabel":true,"precipitationIntensityScale":1.0,"glassDropletsEnabled":true,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","customSkyGradient":{"startColor":-13449484,"middleColor":-5510150,"endColor":-1,"threeColors":false,"startPosition":0.0,"middlePosition":0.5,"endPosition":1.0,"direction":"SIDEWAYS"},"sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunHorizontalPosition":0.72,"moonSizeScale":1.0,"moonOpacity":1.0,"sunColorPreset":"NATURAL","sunStyle":"CLASSIC","sunbeamMode":"THROUGH_CLOUDS","lensFlareEnabled":true,"rainbowSizeScale":1.0,"lensFlareMotionEnabled":false,"distantAircraftEnabled":true,"birdFlockSize":"SMALL","birdAppearanceInterval":"NATURAL","treesEnabled":false,"treeStyle":"EVERGREEN","treeSeason":"AUTO","labelVerticalPosition":0.09,"wallpaperDimmingStrength":0.0,"statusBarShadeStrength":0.0,"seasonalCelebrationsEnabled":true}}]}"""

		store.edit { preferences ->
			preferences[key] = raw
		}

		val repository = AppearancePresetRepository(store)

		assertEquals(
			AppearancePresetStorageState.Unreadable(AppearancePresetUnreadableReason.Malformed),
			repository.state.first()
		)
		assertTrue(repository.create("Ignored", AppSettings().toAppearancePresetSnapshot()).isFailure)
		assertEquals(raw, store.data.first()[key])
	}
}
