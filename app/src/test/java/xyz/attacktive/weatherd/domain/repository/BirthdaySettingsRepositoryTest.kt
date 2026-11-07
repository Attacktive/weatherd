package xyz.attacktive.weatherd.domain.repository

import java.time.MonthDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class BirthdaySettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore() = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile("birthday-settings.preferences_pb")
	}

	@Test
	fun `birthday settings default off without a date`() = runTest {
		val settings = SettingsRepository(dataStore()).settings.first()
		assertFalse(settings.birthdayCelebrationEnabled)
		assertFalse(settings.birthdayEveryDay)
		assertNull(settings.birthdayDate)
	}

	@Test
	fun `month and day survive save and repository recreation without a birth year`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		val birthday = MonthDay.of(2, 29)
		repository.save(AppSettings(birthdayCelebrationEnabled = true, birthdayDate = birthday, birthdayEveryDay = true))
		val restored = SettingsRepository(store).settings.first()
		assertTrue(restored.birthdayCelebrationEnabled)
		assertTrue(restored.birthdayEveryDay)
		assertEquals(birthday, restored.birthdayDate)
		assertEquals("--02-29", store.data.first()[stringPreferencesKey("birthday_date")])
	}

	@Test
	fun `birthday mutations preserve unrelated edits and toggles preserve the selected date`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		val stale = repository.settings.first()
		repository.update(listOf(SettingsMutation.CloudCount(1.7f)))
		repository.update(settingsMutationsBetween(stale, stale.copy(birthdayCelebrationEnabled = true, birthdayDate = MonthDay.of(11, 7), birthdayEveryDay = true)))
		repository.update(listOf(SettingsMutation.BirthdayCelebrationEnabled(false), SettingsMutation.BirthdayEveryDay(false)))
		val restored = SettingsRepository(store).settings.first()
		assertEquals(1.7f, restored.cloudCountScale, 0f)
		assertEquals(MonthDay.of(11, 7), restored.birthdayDate)
		assertFalse(restored.birthdayCelebrationEnabled)
		assertFalse(restored.birthdayEveryDay)
		repository.update(listOf(SettingsMutation.BirthdayDate(null)))
		assertNull(repository.settings.first().birthdayDate)
		assertNull(store.data.first()[stringPreferencesKey("birthday_date")])
	}

	@Test
	fun `malformed stored dates are unavailable rather than crashing or picking another birthday`() = runTest {
		val store = dataStore()
		val repository = SettingsRepository(store)
		for (value in listOf("", "--02-30", "--13-01", "2026-11-07", "not-a-date")) {
			store.edit { it[stringPreferencesKey("birthday_date")] = value }
			assertNull(repository.settings.first().birthdayDate)
		}
	}

	@Test
	fun `appearance presets and appearance reset never change personal birthday settings`() = runTest {
		val repository = SettingsRepository(dataStore())
		val original = AppSettings(birthdayCelebrationEnabled = true, birthdayDate = MonthDay.of(11, 7), birthdayEveryDay = true)
		repository.save(original)
		repository.applyAppearancePreset(AppSettings(seasonalCelebrationsEnabled = false).toAppearancePresetSnapshot())
		repository.applyAppearancePreset(AppSettings().toAppearancePresetSnapshot())
		val restored = repository.settings.first()
		assertEquals(original.birthdayDate, restored.birthdayDate)
		assertEquals(original.birthdayEveryDay, restored.birthdayEveryDay)
		assertEquals(original.birthdayCelebrationEnabled, restored.birthdayCelebrationEnabled)
		assertEquals(original.toAppearancePresetSnapshot(), original.copy(birthdayDate = null, birthdayEveryDay = false, birthdayCelebrationEnabled = false).toAppearancePresetSnapshot())
	}
}
