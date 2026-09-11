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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.FrameRateCap
import xyz.attacktive.weatherd.domain.model.TemperatureUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private fun TestScope.dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) {
		temporaryFolder.newFile("settings.preferences_pb")
	}

	@Test
	fun `defaults are returned before anything is saved`() = runTest {
		val repository = SettingsRepository(dataStore())

		assertEquals(AppSettings(), repository.settings.first())
	}

	@Test
	fun `both labels stay hidden until the user asks for them`() = runTest {
		val repository = SettingsRepository(dataStore())

		val settings = repository.settings.first()
		assertFalse(settings.showWeatherLabel)
		assertFalse(settings.showLocationLabel)
	}

	@Test
	fun `saved settings round-trip`() = runTest {
		val repository = SettingsRepository(dataStore())
		val updated = AppSettings(
			updateIntervalMinutes = 120,
			useDeviceLocation = false,
			manualLatitude = 35.68,
			manualLongitude = 139.69,
			manualLocationLabel = "Tokyo, Japan",
			backdropScene = BackdropScene.MOUNTAINS,
			showWeatherLabel = false,
			showLocationLabel = true,
			temperatureUnit = TemperatureUnit.FAHRENHEIT,
			frameRateCap = FrameRateCap.FPS_30
		)

		repository.save(updated)

		assertEquals(updated, repository.settings.first())
	}

	@Test
	fun `an unrecognized stored backdrop scene falls back to NONE`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)
		dataStore.edit { it[stringPreferencesKey("backdrop_scene")] = "DISCOTHEQUE" }

		assertEquals(BackdropScene.NONE, repository.settings.first().backdropScene)
	}

	@Test
	fun `an unrecognized stored temperature unit falls back to celsius`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)
		dataStore.edit { it[stringPreferencesKey("temperature_unit")] = "KELVIN" }

		assertEquals(TemperatureUnit.CELSIUS, repository.settings.first().temperatureUnit)
	}

	@Test
	fun `an unrecognized stored frame rate cap falls back to uncapped`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)
		dataStore.edit { it[stringPreferencesKey("frame_rate_cap")] = "FPS_240" }

		assertEquals(FrameRateCap.UNCAPPED, repository.settings.first().frameRateCap)
	}

	@Test
	fun `clearing manual location removes the coordinates`() = runTest {
		val repository = SettingsRepository(dataStore())
		repository.save(AppSettings(useDeviceLocation = false, manualLatitude = 1.0, manualLongitude = 2.0, manualLocationLabel = "Somewhere"))

		repository.save(AppSettings(useDeviceLocation = false, manualLatitude = null, manualLongitude = null, manualLocationLabel = null))

		val settings = repository.settings.first()
		assertNull(settings.manualLatitude)
		assertNull(settings.manualLongitude)
		assertNull(settings.manualLocationLabel)
	}

	@Test
	fun `round-trips the intensity scales`() = runTest {
		val repository = SettingsRepository(dataStore())

		repository.save(AppSettings(precipitationIntensityScale = 0.4f, windIntensityScale = 1.8f))

		val settings = repository.settings.first()
		assertEquals(0.4f, settings.precipitationIntensityScale, 0.0001f)
		assertEquals(1.8f, settings.windIntensityScale, 0.0001f)
	}

	@Test
	fun `absent intensity scales read as unscaled`() = runTest {
		val repository = SettingsRepository(dataStore())

		val settings = repository.settings.first()

		assertEquals(1f, settings.precipitationIntensityScale, 0.0001f)
		assertEquals(1f, settings.windIntensityScale, 0.0001f)
	}
}
