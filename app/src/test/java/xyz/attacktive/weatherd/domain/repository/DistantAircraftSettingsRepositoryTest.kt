package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class DistantAircraftSettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile("distant-aircraft-settings.preferences_pb")
	}

	@Test
	fun `distant aircraft defaults on and persists through save and update`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)

		assertTrue(repository.settings.first().distantAircraftEnabled)

		repository.save(AppSettings(distantAircraftEnabled = false))
		assertFalse(SettingsRepository(dataStore).settings.first().distantAircraftEnabled)

		repository.update(listOf(SettingsMutation.DistantAircraftEnabled(true)))
		assertTrue(SettingsRepository(dataStore).settings.first().distantAircraftEnabled)
	}

	@Test
	fun `aircraft mutation derived from stale snapshot preserves newer unrelated setting`() = runTest {
		val repository = SettingsRepository(dataStore())
		repository.save(AppSettings(cloudCountScale = 1f, distantAircraftEnabled = true))
		val stale = repository.settings.first()

		repository.update(listOf(SettingsMutation.CloudCount(1.6f)))
		val aircraftMutations = settingsMutationsBetween(stale, stale.copy(distantAircraftEnabled = false))
		repository.update(aircraftMutations)

		val persisted = repository.settings.first()
		assertEquals(1.6f, persisted.cloudCountScale, 0f)
		assertFalse(persisted.distantAircraftEnabled)
		assertEquals(listOf(SettingsMutation.DistantAircraftEnabled(false)), aircraftMutations)
	}

	@Test
	fun `appearance preset applies aircraft choice without replacing nonappearance settings`() = runTest {
		val repository = SettingsRepository(dataStore())
		repository.save(
			AppSettings(
				updateIntervalMinutes = 180,
				manualLatitude = 41.9,
				manualLongitude = 12.5,
				distantAircraftEnabled = true
			)
		)
		val preset = AppSettings(distantAircraftEnabled = false).toAppearancePresetSnapshot()

		repository.applyAppearancePreset(preset)

		val persisted = repository.settings.first()
		assertFalse(persisted.distantAircraftEnabled)
		assertEquals(180, persisted.updateIntervalMinutes)
		assertEquals(41.9, persisted.manualLatitude!!, 0.0)
		assertEquals(12.5, persisted.manualLongitude!!, 0.0)
	}
}
