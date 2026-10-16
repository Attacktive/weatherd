package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BirdAppearanceInterval
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class BirdBehaviorSettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(name: String = "bird-settings.preferences_pb") = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile(name)
	}

	@Test
	fun `bird preferences survive save and update through repository reopening`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)

		repository.save(
			AppSettings(
				birdFlockSize = BirdFlockSize.LARGE,
				birdAppearanceInterval = BirdAppearanceInterval.MINUTES_5
			)
		)

		val reopened = SettingsRepository(store)
		val savedSettings = reopened.settings.first()
		assertEquals(BirdFlockSize.LARGE, savedSettings.birdFlockSize)
		assertEquals(BirdAppearanceInterval.MINUTES_5, savedSettings.birdAppearanceInterval)

		reopened.update(
			listOf(
				SettingsMutation.BirdFlockSize(BirdFlockSize.OFF),
				SettingsMutation.BirdAppearanceInterval(BirdAppearanceInterval.SECONDS_30)
			)
		)

		val finalSettings = SettingsRepository(store).settings.first()
		assertEquals(BirdFlockSize.OFF, finalSettings.birdFlockSize)
		assertEquals(BirdAppearanceInterval.SECONDS_30, finalSettings.birdAppearanceInterval)
	}

	@Test
	fun `saved preset stores bird preferences and reapplication preserves nonappearance settings`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		repository.save(
			AppSettings(
				updateIntervalMinutes = 180,
				manualLatitude = 37.5,
				manualLongitude = 127.0,
				manualLocationLabel = "Seoul",
				birdFlockSize = BirdFlockSize.SMALL,
				birdAppearanceInterval = BirdAppearanceInterval.NATURAL
			)
		)

		val presetRepository = AppearancePresetRepository(store)
		val customSnapshot = AppSettings(
			birdFlockSize = BirdFlockSize.MEDIUM,
			birdAppearanceInterval = BirdAppearanceInterval.MINUTES_2
		).toAppearancePresetSnapshot()

		assertTrue(presetRepository.create("Frequent Medium Birds", customSnapshot).isSuccess)

		val restoredPresets = (AppearancePresetRepository(store).state.first() as AppearancePresetStorageState.Ready).presets
		val savedSnapshot = restoredPresets.single().snapshot
		assertEquals(BirdFlockSize.MEDIUM, savedSnapshot.birdFlockSize)
		assertEquals(BirdAppearanceInterval.MINUTES_2, savedSnapshot.birdAppearanceInterval)

		repository.applyAppearancePreset(savedSnapshot)

		val persisted = repository.settings.first()
		assertEquals(BirdFlockSize.MEDIUM, persisted.birdFlockSize)
		assertEquals(BirdAppearanceInterval.MINUTES_2, persisted.birdAppearanceInterval)
		assertEquals(180, persisted.updateIntervalMinutes)
		assertEquals(37.5, persisted.manualLatitude!!, 0.0001)
		assertEquals(127.0, persisted.manualLongitude!!, 0.0001)
		assertEquals("Seoul", persisted.manualLocationLabel)
	}

	@Test
	fun `stale mutations do not clobber concurrent changes to unrelated settings`() = runTest {
		val repository = SettingsRepository(dataStore())
		repository.save(
			AppSettings(
				showLocationLabel = false,
				cloudCountScale = 1.0f,
				birdFlockSize = BirdFlockSize.SMALL,
				birdAppearanceInterval = BirdAppearanceInterval.NATURAL
			)
		)
		val stale = repository.settings.first()

		repository.update(
			listOf(
				SettingsMutation.ShowLocationLabel(true),
				SettingsMutation.CloudCount(1.7f)
			)
		)

		val birdMutations = settingsMutationsBetween(
			stale,
			stale.copy(
				birdFlockSize = BirdFlockSize.LARGE,
				birdAppearanceInterval = BirdAppearanceInterval.MINUTES_10
			)
		)

		repository.update(birdMutations)

		val persisted = repository.settings.first()
		assertTrue(persisted.showLocationLabel)
		assertEquals(1.7f, persisted.cloudCountScale, 0.0001f)
		assertEquals(BirdFlockSize.LARGE, persisted.birdFlockSize)
		assertEquals(BirdAppearanceInterval.MINUTES_10, persisted.birdAppearanceInterval)
	}

	@Test
	fun `unknown stored enum strings fall back conservatively`() = runTest {
		val store = dataStore()
		store.edit { preferences ->
			preferences[stringPreferencesKey("bird_flock_size")] = "GIGANTIC"
			preferences[stringPreferencesKey("bird_appearance_interval")] = "HOURS_2"
		}

		val repository = SettingsRepository(store)
		val settings = repository.settings.first()

		assertEquals(BirdFlockSize.SMALL, settings.birdFlockSize)
		assertEquals(BirdAppearanceInterval.NATURAL, settings.birdAppearanceInterval)
	}
}
