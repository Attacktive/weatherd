package xyz.attacktive.weatherd.domain.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class CloudSizeSettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private val cloudSizeKey = floatPreferencesKey("cloud_size_scale")

	@Test
	fun existingTwoHundredPercentPreferenceIsClampedOnRead() = runTest {
		val store = dataStore()
		store.edit { it[cloudSizeKey] = 2f }
		val repository = SettingsRepository(store)
		assertEquals(1.5f, repository.settings.first().cloudSizeScale, 0f)
	}

	@Test
	fun savesAndMutationsEnforceTheCapWithoutChangingCloudCount() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		repository.save(AppSettings(cloudSizeScale = 2f, cloudCountScale = 2f))
		assertEquals(1.5f, store.data.first()[cloudSizeKey]!!, 0f)
		assertEquals(2f, repository.settings.first().cloudCountScale, 0f)

		repository.update(listOf(SettingsMutation.CloudSize(1f)))
		repository.update(listOf(SettingsMutation.CloudSize(2f)))
		assertEquals(1.5f, store.data.first()[cloudSizeKey]!!, 0f)
		assertEquals(2f, repository.settings.first().cloudCountScale, 0f)
	}

	@Test
	fun valuesThroughoutTheNewRangeRoundTripAndReset() = runTest {
		val repository = SettingsRepository(dataStore())
		for (size in listOf(0.5f, 1f, 1.25f, 1.26f, 1.5f, 1f)) {
			repository.update(listOf(SettingsMutation.CloudSize(size)))
			assertEquals(size, repository.settings.first().cloudSizeScale, 0f)
		}
	}

	@Test
	fun applyingALegacyPresetClampsItsSizeAndPreservesOtherControls() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		val legacy = AppSettings(cloudSizeScale = 2f, cloudCountScale = 2f, moonSizeScale = 1.8f).toAppearancePresetSnapshot().copy(cloudSizeScale = 2f)
		repository.applyAppearancePreset(legacy)
		val restored = repository.settings.first()
		assertEquals(1.5f, restored.cloudSizeScale, 0f)
		assertEquals(1.5f, store.data.first()[cloudSizeKey]!!, 0f)
		assertEquals(2f, restored.cloudCountScale, 0f)
		assertEquals(1.8f, restored.moonSizeScale, 0f)
	}

	@Test
	fun nonFiniteValuesRestoreDefaultAcrossReadsWritesAndPresets() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		store.edit { it[cloudSizeKey] = Float.NaN }
		assertEquals(1f, repository.settings.first().cloudSizeScale, 0f)

		repository.save(AppSettings(cloudSizeScale = Float.POSITIVE_INFINITY))
		assertEquals(1f, store.data.first()[cloudSizeKey]!!, 0f)

		repository.update(listOf(SettingsMutation.CloudSize(Float.NEGATIVE_INFINITY)))
		assertEquals(1f, store.data.first()[cloudSizeKey]!!, 0f)

		val legacy = AppSettings().toAppearancePresetSnapshot().copy(cloudSizeScale = Float.NaN)
		repository.applyAppearancePreset(legacy)
		assertEquals(1f, repository.settings.first().cloudSizeScale, 0f)
	}

	@Test
	fun missingCloudSizeKeepsTheExistingDefault() = runTest {
		assertEquals(1f, SettingsRepository(dataStore()).settings.first().cloudSizeScale, 0f)
	}

	@Test
	fun inMemoryMutationDiffNormalizesLegacyAndNonFiniteCloudSizes() {
		val defaults = AppSettings()
		assertEquals(
			listOf(SettingsMutation.CloudSize(1.5f)),
			settingsMutationsBetween(defaults, defaults.copy(cloudSizeScale = 2f))
		)

		assertEquals(emptyList<SettingsMutation>(), settingsMutationsBetween(defaults, defaults.copy(cloudSizeScale = Float.NaN)))

		assertEquals(
			listOf(SettingsMutation.CloudSize(1.5f)),
			settingsMutationsBetween(defaults.copy(cloudSizeScale = 2f), defaults.copy(cloudSizeScale = 2f))
		)
	}

	private fun TestScope.dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile("cloud-size.preferences_pb")
	}
}
