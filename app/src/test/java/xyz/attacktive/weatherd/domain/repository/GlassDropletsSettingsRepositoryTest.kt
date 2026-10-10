package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings

@OptIn(ExperimentalCoroutinesApi::class)
class GlassDropletsSettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile("glass-droplets-settings.preferences_pb")
	}

	@Test
	fun `rain-on-glass defaults off and persists opt-in`() = runTest {
		val repository = SettingsRepository(dataStore())

		assertFalse(repository.settings.first().glassDropletsEnabled)

		repository.save(AppSettings(glassDropletsEnabled = true))

		assertTrue(repository.settings.first().glassDropletsEnabled)
	}

	@Test
	fun `rain-on-glass change maps to one settings mutation`() {
		val mutations = settingsMutationsBetween(
			AppSettings(),
			AppSettings(glassDropletsEnabled = true)
		)

		assertTrue(mutations.single() == SettingsMutation.GlassDropletsEnabled(true))
	}
}
