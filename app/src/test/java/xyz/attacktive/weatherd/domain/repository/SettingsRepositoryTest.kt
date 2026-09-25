package xyz.attacktive.weatherd.domain.repository

import java.util.Locale
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.FrameRateCap
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SunColorPreset
import xyz.attacktive.weatherd.domain.model.TemperatureUnit
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.defaultAppSettings

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

		assertEquals(defaultAppSettings(), repository.settings.first())
	}

	@Test
	fun `temperature defaults follow the regional weather convention`() {
		assertEquals(TemperatureUnit.FAHRENHEIT, defaultAppSettings(Locale.US).temperatureUnit)
		assertEquals(TemperatureUnit.FAHRENHEIT, defaultAppSettings(Locale("es", "PR")).temperatureUnit)
		assertEquals(TemperatureUnit.CELSIUS, defaultAppSettings(Locale.KOREA).temperatureUnit)
		assertEquals(TemperatureUnit.CELSIUS, defaultAppSettings(Locale.UK).temperatureUnit)
	}

	@Test
	fun `scene simulator starts hidden`() = runTest {
		val repository = SettingsRepository(dataStore())

		assertFalse(repository.settings.first().sceneSimulatorEnabled)
	}

	@Test
	fun `scene simulator override starts inactive`() = runTest {
		val repository = SettingsRepository(dataStore())

		assertFalse(repository.settings.first().sceneSimulatorActive)
	}

	@Test
	fun `wallpaper scrolling starts disabled`() = runTest {
		val repository = SettingsRepository(dataStore())

		assertFalse(repository.settings.first().wallpaperScrollingEnabled)
	}

	@Test
	fun `celestial appearance starts at the current rendering defaults`() = runTest {
		val repository = SettingsRepository(dataStore())

		val settings = repository.settings.first()
		assertTrue(settings.sunVisible)
		assertTrue(settings.moonVisible)
		assertEquals(1f, settings.sunSizeScale, 0.0001f)
		assertEquals(SunColorPreset.NATURAL, settings.sunColorPreset)
		assertTrue(settings.lensFlareEnabled)
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
			weatherProvider = WeatherProviderType.MET_NORWAY,
			updateIntervalMinutes = 120,
			useDeviceLocation = false,
			manualLatitude = 35.68,
			manualLongitude = 139.69,
			manualLocationLabel = "Tokyo, Japan",
			backdropScene = BackdropScene.MOUNTAINS,
			showWeatherLabel = false,
			showLocationLabel = true,
			temperatureUnit = TemperatureUnit.FAHRENHEIT,
			frameRateCap = FrameRateCap.FPS_30,
			wallpaperScrollingEnabled = true,
			cloudContrastScale = 1.35f,
			skyBrightnessScale = 0.8f,
			skySaturationScale = 1.25f,
			skyColorPreset = SkyColorPreset.PASTEL,
			sunVisible = false,
			moonVisible = false,
			sunSizeScale = 1.65f,
			sunColorPreset = SunColorPreset.GOLDEN,
			lensFlareEnabled = false,
			sceneSimulatorEnabled = true,
			sceneSimulatorActive = true,
			sceneSimulatorPresetIndex = 7,
			sceneSimulatorDayPhase = DayPhase.DUSK,
			sceneSimulatorCelestialProgress = 0.73f
		)

		repository.save(updated)

		assertEquals(updated, repository.settings.first())
	}

	@Test
	fun `an unrecognized stored weather provider falls back to Open-Meteo`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)
		dataStore.edit { it[stringPreferencesKey("weather_provider")] = "WEATHER_9000" }

		assertEquals(defaultAppSettings().weatherProvider, repository.settings.first().weatherProvider)
	}

	@Test
	fun `an unrecognized stored backdrop scene falls back to NONE`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)
		dataStore.edit { it[stringPreferencesKey("backdrop_scene")] = "DISCOTHEQUE" }

		assertEquals(defaultAppSettings().backdropScene, repository.settings.first().backdropScene)
	}

	@Test
	fun `an unrecognized stored temperature unit falls back to default`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)
		dataStore.edit { it[stringPreferencesKey("temperature_unit")] = "KELVIN" }

		assertEquals(defaultAppSettings().temperatureUnit, repository.settings.first().temperatureUnit)
	}

	@Test
	fun `an unrecognized stored frame rate cap falls back to uncapped`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)
		dataStore.edit { it[stringPreferencesKey("frame_rate_cap")] = "FPS_240" }

		assertEquals(defaultAppSettings().frameRateCap, repository.settings.first().frameRateCap)
	}

	@Test
	fun `an unrecognized stored simulator day phase falls back to day`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)
		dataStore.edit { it[stringPreferencesKey("scene_simulator_day_phase")] = "MIDNIGHT_BLUE" }

		assertEquals(DayPhase.DAY, repository.settings.first().sceneSimulatorDayPhase)
	}

	@Test
	fun `an unrecognized stored sky color preset falls back to natural`() = runTest {
		val dataStore = dataStore()
		val repository = SettingsRepository(dataStore)
		dataStore.edit { it[stringPreferencesKey("sky_color_preset")] = "RADIOACTIVE" }

		assertEquals(defaultAppSettings().skyColorPreset, repository.settings.first().skyColorPreset)
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

		repository.save(AppSettings(precipitationIntensityScale = 0.4f, windIntensityScale = 1.8f, cloudIntensityScale = 0.6f, cloudSizeScale = 1.7f, cloudCountScale = 0.7f, cloudContrastScale = 1.3f, skyBrightnessScale = 0.8f, skySaturationScale = 1.2f, skyColorPreset = SkyColorPreset.WARM))

		val settings = repository.settings.first()
		assertEquals(0.4f, settings.precipitationIntensityScale, 0.0001f)
		assertEquals(1.8f, settings.windIntensityScale, 0.0001f)
		assertEquals(0.6f, settings.cloudIntensityScale, 0.0001f)
		assertEquals(1.7f, settings.cloudSizeScale, 0.0001f)
		assertEquals(0.7f, settings.cloudCountScale, 0.0001f)
		assertEquals(1.3f, settings.cloudContrastScale, 0.0001f)
		assertEquals(0.8f, settings.skyBrightnessScale, 0.0001f)
		assertEquals(1.2f, settings.skySaturationScale, 0.0001f)
		assertEquals(SkyColorPreset.WARM, settings.skyColorPreset)
	}

	@Test
	fun `absent intensity scales read as unscaled`() = runTest {
		val repository = SettingsRepository(dataStore())

		val settings = repository.settings.first()

		assertEquals(1f, settings.precipitationIntensityScale, 0.0001f)
		assertEquals(1f, settings.windIntensityScale, 0.0001f)
		assertEquals(1f, settings.cloudIntensityScale, 0.0001f)
		assertEquals(1f, settings.cloudSizeScale, 0.0001f)
		assertEquals(1f, settings.cloudCountScale, 0.0001f)
		assertEquals(1f, settings.cloudContrastScale, 0.0001f)
		assertEquals(1f, settings.skyBrightnessScale, 0.0001f)
		assertEquals(1f, settings.skySaturationScale, 0.0001f)
		assertEquals(SkyColorPreset.NATURAL, settings.skyColorPreset)
	}
}
