package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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
class ReadabilitySettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(name: String = "readability-settings.preferences_pb") = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile(name)
	}

	@Test
	fun `explicit readability strengths persist and survive reopening`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)

		repository.save(AppSettings(wallpaperDimmingStrength = 0.3f, statusBarShadeStrength = 0.45f))
		val reopened = SettingsRepository(dataStore)
		assertEquals(0.3f, reopened.settings.first().wallpaperDimmingStrength, 0.0001f)
		assertEquals(0.45f, reopened.settings.first().statusBarShadeStrength, 0.0001f)

		repository.update(listOf(SettingsMutation.WallpaperDimmingStrength(0.15f)))
		val afterDimming = SettingsRepository(dataStore)
		assertEquals(0.15f, afterDimming.settings.first().wallpaperDimmingStrength, 0.0001f)
		assertEquals(0.45f, afterDimming.settings.first().statusBarShadeStrength, 0.0001f)

		repository.update(listOf(SettingsMutation.StatusBarShadeStrength(0.6f)))
		val afterShade = SettingsRepository(dataStore)
		assertEquals(0.15f, afterShade.settings.first().wallpaperDimmingStrength, 0.0001f)
		assertEquals(0.6f, afterShade.settings.first().statusBarShadeStrength, 0.0001f)
	}

	@Test
	fun `readability strengths throughout valid range survive persistence without clipping`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		for (strength in listOf(0f, 0.1f, 0.35f, 0.6f)) {
			repository.save(AppSettings(wallpaperDimmingStrength = strength, statusBarShadeStrength = 0.6f - strength))
			val settings = SettingsRepository(store).settings.first()
			assertEquals(strength, settings.wallpaperDimmingStrength, 0.0001f)
			assertEquals(0.6f - strength, settings.statusBarShadeStrength, 0.0001f)
		}
	}

	@Test
	fun `out-of-range and non-finite values are clamped or resolved off on read and save`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)

		repository.save(AppSettings(wallpaperDimmingStrength = -0.1f, statusBarShadeStrength = 1.0f))
		val saved = SettingsRepository(dataStore).settings.first()
		assertEquals(0f, saved.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0.6f, saved.statusBarShadeStrength, 0.0001f)

		repository.update(listOf(SettingsMutation.WallpaperDimmingStrength(0.9f)))
		assertEquals(0.6f, repository.settings.first().wallpaperDimmingStrength, 0.0001f)

		repository.update(listOf(SettingsMutation.StatusBarShadeStrength(-0.5f)))
		assertEquals(0f, repository.settings.first().statusBarShadeStrength, 0.0001f)

		repository.save(AppSettings(wallpaperDimmingStrength = Float.NaN, statusBarShadeStrength = Float.POSITIVE_INFINITY))
		val nonFiniteSaved = SettingsRepository(dataStore).settings.first()
		assertEquals(0f, nonFiniteSaved.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0f, nonFiniteSaved.statusBarShadeStrength, 0.0001f)

		repository.update(listOf(SettingsMutation.WallpaperDimmingStrength(Float.NEGATIVE_INFINITY)))
		assertEquals(0f, repository.settings.first().wallpaperDimmingStrength, 0.0001f)

		dataStore.edit { preferences ->
			preferences[floatPreferencesKey("wallpaper_dimming_strength")] = 0.85f
			preferences[floatPreferencesKey("status_bar_shade_strength")] = -0.05f
		}

		val rawPreferenceSettings = SettingsRepository(dataStore).settings.first()
		assertEquals(0.6f, rawPreferenceSettings.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0f, rawPreferenceSettings.statusBarShadeStrength, 0.0001f)

		dataStore.edit { preferences ->
			preferences[floatPreferencesKey("wallpaper_dimming_strength")] = Float.NaN
		}

		val nanPreferenceSettings = SettingsRepository(dataStore).settings.first()
		assertEquals(0f, nanPreferenceSettings.wallpaperDimmingStrength, 0.0001f)
	}

	@Test
	fun `preset persistence and apply round-trip readability overlays while preserving unrelated settings`() = runTest {
		val dataStore = dataStore()
		val presets = AppearancePresetRepository(dataStore)
		val customSnapshot = AppSettings(
			backdropScene = BackdropScene.BEACH,
			wallpaperDimmingStrength = 0.4f,
			statusBarShadeStrength = 0.25f
		).toAppearancePresetSnapshot()

		assertTrue(presets.create("Dimmed Beach", customSnapshot).isSuccess)
		val restoredPresets = (AppearancePresetRepository(dataStore).state.first() as AppearancePresetStorageState.Ready).presets
		val presetSnapshot = restoredPresets.first { it.name == "Dimmed Beach" }.snapshot
		assertEquals(0.4f, presetSnapshot.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0.25f, presetSnapshot.statusBarShadeStrength, 0.0001f)

		val settings = SettingsRepository(dataStore)
		settings.save(
			AppSettings(
				updateIntervalMinutes = 180,
				manualLatitude = 41.9,
				manualLongitude = 12.5,
				wallpaperDimmingStrength = 0f,
				statusBarShadeStrength = 0f
			)
		)

		settings.applyAppearancePreset(presetSnapshot)
		val persisted = settings.settings.first()
		assertEquals(0.4f, persisted.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0.25f, persisted.statusBarShadeStrength, 0.0001f)
		assertEquals(BackdropScene.BEACH, persisted.backdropScene)
		assertEquals(180, persisted.updateIntervalMinutes)
		assertEquals(41.9, persisted.manualLatitude!!, 0.0001)
		assertEquals(12.5, persisted.manualLongitude!!, 0.0001)
	}

	@Test
	fun `version eight preset missing readability fields loads off and apply preserves unrelated settings`() = runTest {
		val key = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
		val store = dataStore("preset-settings-v8-readability.preferences_pb")
		val raw = """{"schemaVersion":8,"presets":[{"id":"legacy-v8","name":"Legacy V8","snapshot":{"backdropScene":"MOUNTAINS","showWeatherLabel":true,"showLocationLabel":false,"precipitationIntensityScale":1.2,"glassDropletsEnabled":true,"windIntensityScale":1.0,"cloudIntensityScale":1.0,"cloudSizeScale":1.0,"cloudCountScale":1.0,"cloudContrastScale":1.0,"skyBrightnessScale":1.0,"nightBrightnessScale":1.0,"skySaturationScale":1.0,"skyColorPreset":"NATURAL","sunVisible":true,"moonVisible":true,"sunSizeScale":1.0,"sunColorPreset":"NATURAL","sunStyle":"CLASSIC","lensFlareEnabled":true,"lensFlareMotionEnabled":false,"distantAircraftEnabled":true,"treesEnabled":false,"labelVerticalPosition":0.15}}]}"""
		store.edit { preferences ->
			preferences[key] = raw
		}

		val presetRepository = AppearancePresetRepository(store)
		val restoredSnapshot = (presetRepository.state.first() as AppearancePresetStorageState.Ready).presets.single().snapshot
		assertEquals(0f, restoredSnapshot.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0f, restoredSnapshot.statusBarShadeStrength, 0.0001f)
		assertEquals(BackdropScene.MOUNTAINS, restoredSnapshot.backdropScene)

		val settingsRepository = SettingsRepository(store)
		settingsRepository.save(
			AppSettings(
				updateIntervalMinutes = 120,
				wallpaperDimmingStrength = 0.5f,
				statusBarShadeStrength = 0.3f
			)
		)

		settingsRepository.applyAppearancePreset(restoredSnapshot)
		val updated = settingsRepository.settings.first()
		assertEquals(0f, updated.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0f, updated.statusBarShadeStrength, 0.0001f)
		assertEquals(BackdropScene.MOUNTAINS, updated.backdropScene)
		assertEquals(120, updated.updateIntervalMinutes)
	}

	@Test
	fun `scoped reset and single-control update preserve the other control and unrelated settings`() = runTest {
		val repository = SettingsRepository(dataStore())
		repository.save(
			AppSettings(
				updateIntervalMinutes = 60,
				backdropScene = BackdropScene.METROPOLIS,
				wallpaperDimmingStrength = 0.5f,
				statusBarShadeStrength = 0.4f
			)
		)

		repository.update(listOf(SettingsMutation.WallpaperDimmingStrength(0f)))
		val resetDimming = repository.settings.first()
		assertEquals(0f, resetDimming.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0.4f, resetDimming.statusBarShadeStrength, 0.0001f)
		assertEquals(BackdropScene.METROPOLIS, resetDimming.backdropScene)
		assertEquals(60, resetDimming.updateIntervalMinutes)

		repository.update(listOf(SettingsMutation.StatusBarShadeStrength(0f)))
		val resetShade = repository.settings.first()
		assertEquals(0f, resetShade.wallpaperDimmingStrength, 0.0001f)
		assertEquals(0f, resetShade.statusBarShadeStrength, 0.0001f)
		assertEquals(BackdropScene.METROPOLIS, resetShade.backdropScene)
		assertEquals(60, resetShade.updateIntervalMinutes)
	}

}
