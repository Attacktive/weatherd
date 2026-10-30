package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.HolidayCelebration
import xyz.attacktive.weatherd.domain.model.defaultAppSettings
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class SeasonalCelebrationSettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore() = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile("seasonal-celebration-settings.preferences_pb")
	}

	@Test
	fun `seasonal celebrations default on, force defaults off, forced defaults to Halloween`() = runTest {
		val repository = SettingsRepository(dataStore())
		val settings = repository.settings.first()

		assertTrue(settings.seasonalCelebrationsEnabled)
		assertFalse(settings.forceHolidayCelebration)
		assertEquals(HolidayCelebration.HALLOWEEN, settings.forcedHolidayCelebration)
	}

	@Test
	fun `seasonal celebrations preference persists and round-trips via save and update mutation`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)

		repository.save(AppSettings(seasonalCelebrationsEnabled = false))
		assertFalse(SettingsRepository(dataStore).settings.first().seasonalCelebrationsEnabled)

		repository.update(listOf(SettingsMutation.SeasonalCelebrationsEnabled(true)))
		assertTrue(SettingsRepository(dataStore).settings.first().seasonalCelebrationsEnabled)
	}

	@Test
	fun `force holiday celebration and forced choice persist independently`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)

		repository.update(
			listOf(
				SettingsMutation.ForceHolidayCelebration(true),
				SettingsMutation.ForcedHolidayCelebration(HolidayCelebration.CHRISTMAS)
			)
		)

		val updated = SettingsRepository(dataStore).settings.first()
		assertTrue(updated.forceHolidayCelebration)
		assertEquals(HolidayCelebration.CHRISTMAS, updated.forcedHolidayCelebration)

		repository.update(listOf(SettingsMutation.ForcedHolidayCelebration(HolidayCelebration.NEW_YEAR)))
		val newYear = SettingsRepository(dataStore).settings.first()
		assertTrue(newYear.forceHolidayCelebration)
		assertEquals(HolidayCelebration.NEW_YEAR, newYear.forcedHolidayCelebration)
	}

	@Test
	fun `seasonal celebration mutation derived from stale snapshot preserves newer unrelated setting`() = runTest {
		val repository = SettingsRepository(dataStore())
		repository.save(AppSettings(cloudCountScale = 1f, seasonalCelebrationsEnabled = true))
		val stale = repository.settings.first()

		repository.update(listOf(SettingsMutation.CloudCount(1.7f)))
		val mutations = settingsMutationsBetween(stale, stale.copy(seasonalCelebrationsEnabled = false))
		repository.update(mutations)

		val persisted = repository.settings.first()
		assertEquals(1.7f, persisted.cloudCountScale, 0f)
		assertFalse(persisted.seasonalCelebrationsEnabled)
	}

	@Test
	fun `appearance preset applies seasonal celebration choice without replacing nonappearance or force settings`() = runTest {
		val repository = SettingsRepository(dataStore())
		repository.save(
			AppSettings(
				updateIntervalMinutes = 180,
				manualLatitude = 35.68,
				manualLongitude = 139.69,
				seasonalCelebrationsEnabled = true,
				forceHolidayCelebration = true,
				forcedHolidayCelebration = HolidayCelebration.CHRISTMAS
			)
		)
		val preset = AppSettings(seasonalCelebrationsEnabled = false).toAppearancePresetSnapshot()

		repository.applyAppearancePreset(preset)

		val persisted = repository.settings.first()
		assertFalse(persisted.seasonalCelebrationsEnabled)
		assertEquals(180, persisted.updateIntervalMinutes)
		assertEquals(35.68, persisted.manualLatitude!!, 0.0)
		assertEquals(139.69, persisted.manualLongitude!!, 0.0)
		assertTrue(persisted.forceHolidayCelebration)
		assertEquals(HolidayCelebration.CHRISTMAS, persisted.forcedHolidayCelebration)
	}

	@Test
	fun `default appearance preset resets seasonal celebrations to true while preserving force holiday celebration`() = runTest {
		val repository = SettingsRepository(dataStore())
		repository.save(
			AppSettings(
				seasonalCelebrationsEnabled = false,
				forceHolidayCelebration = true,
				forcedHolidayCelebration = HolidayCelebration.CHRISTMAS
			)
		)

		val resetPreset = defaultAppSettings().toAppearancePresetSnapshot()
		repository.applyAppearancePreset(resetPreset)

		val persisted = repository.settings.first()
		assertTrue(persisted.seasonalCelebrationsEnabled)
		assertTrue(persisted.forceHolidayCelebration)
		assertEquals(HolidayCelebration.CHRISTMAS, persisted.forcedHolidayCelebration)
	}
}
