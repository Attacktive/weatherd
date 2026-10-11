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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AnimationIdleTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class AnimationIdleTimeoutSettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile("settings.preferences_pb")
	}

	@Test
	fun `animation idle timeout persists through settings mutations`() = runTest {
		val repository = SettingsRepository(dataStore())
		val previous = repository.settings.first()
		val updated = previous.copy(animationIdleTimeout = AnimationIdleTimeout.MINUTE_1)

		repository.update(settingsMutationsBetween(previous, updated))

		assertEquals(AnimationIdleTimeout.MINUTE_1, repository.settings.first().animationIdleTimeout)
	}

	@Test
	fun `unknown animation idle timeout falls back to default`() = runTest {
		val dataStore = dataStore()
		dataStore.edit { preferences ->
			preferences[stringPreferencesKey("animation_idle_timeout")] = "MINUTES_9000"
		}

		val repository = SettingsRepository(dataStore)

		assertEquals(repository.defaults.animationIdleTimeout, repository.settings.first().animationIdleTimeout)
	}
}
