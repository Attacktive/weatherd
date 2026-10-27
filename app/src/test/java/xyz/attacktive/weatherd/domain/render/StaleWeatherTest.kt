package xyz.attacktive.weatherd.domain.render

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.CloudCover
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.repository.LocationRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundState
import xyz.attacktive.weatherd.domain.repository.ReverseGeocodingRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.repository.WeatherCacheRepository
import xyz.attacktive.weatherd.domain.repository.WeatherRepository
import xyz.attacktive.weatherd.domain.weather.conditionForWmoCode
import xyz.attacktive.weatherd.util.AppLogger

@OptIn(ExperimentalCoroutinesApi::class)
class StaleWeatherTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Test
	fun `failure warns independently of weather labels and hiding only changes the scene notice`() = runTest {
		val fixture = Fixture(this)
		val provider = fixture.provider()
		runCurrent()
		provider.refresh(1_000_000L, force = true)
		assertNull(provider.paramsFor(1_000_030L).weatherNotice)
		fixture.offline()
		provider.refresh(1_000_060L, force = true)
		assertNull(provider.paramsFor(1_000_060L).overlayLabels)
		assertEquals(1_000_000L, provider.paramsFor(1_000_060L).weatherNotice?.refreshedAtEpochSeconds)
		fixture.settings.value = fixture.settings.value.copy(hideStaleWeatherWarning = true)
		runCurrent()
		assertNull(provider.paramsFor(1_000_060L).weatherNotice)
		assertNotNull(provider.status.value.weatherNotice)
		assertEquals(PrecipitationKind.RAIN, provider.paramsFor(1_000_060L).precipitation?.kind)
		fixture.settings.value = fixture.settings.value.copy(hideStaleWeatherWarning = false)
		runCurrent()
		assertNotNull(provider.paramsFor(1_000_060L).weatherNotice)
	}

	@Test
	fun `offline restart restores rain and provenance without pretending it was just refreshed`() = runTest {
		val fixture = Fixture(this)
		val first = fixture.provider()
		runCurrent()
		first.refresh(1_000_000L, force = true)
		runCurrent()
		fixture.offline()
		val restarted = fixture.provider()
		runCurrent()
		restarted.refresh(1_000_120L, force = true)
		assertEquals(PrecipitationKind.RAIN, restarted.paramsFor(1_000_120L).precipitation?.kind)
		assertEquals(1_000_000L, restarted.status.value.lastRefreshEpochSeconds)
		assertEquals(1_000_000L, restarted.paramsFor(1_000_120L).weatherNotice?.refreshedAtEpochSeconds)
	}

	@Test
	fun `travel retains original location label until current target succeeds`() = runTest {
		val fixture = Fixture(this)
		fixture.settings.value = fixture.settings.value.copy(useDeviceLocation = false, manualLatitude = 52.52, manualLongitude = 13.40, manualLocationLabel = "Berlin", showLocationLabel = true)
		val provider = fixture.provider()
		runCurrent()
		provider.refresh(1_000_000L, force = true)
		fixture.settings.value = fixture.settings.value.copy(manualLatitude = 48.14, manualLongitude = 11.58, manualLocationLabel = "Munich")
		runCurrent()
		fixture.offline()
		provider.refresh(1_000_060L, force = true)
		assertEquals("Berlin", provider.paramsFor(1_000_060L).overlayLabels?.location)
		assertEquals("Berlin", provider.paramsFor(1_000_060L).weatherNotice?.locationLabel)
		fixture.succeed(71)
		provider.refresh(1_000_120L, force = true)
		assertEquals("Munich", provider.paramsFor(1_000_120L).overlayLabels?.location)
		assertNull(provider.paramsFor(1_000_120L).weatherNotice)
		assertEquals(PrecipitationKind.SNOW, provider.paramsFor(1_000_120L).precipitation?.kind)
	}

	@Test
	fun `no history is unavailable while simulator never shows live weather warning`() = runTest {
		val fixture = Fixture(this)
		fixture.offline()
		val provider = fixture.provider()
		runCurrent()
		provider.refresh(1_000_000L, force = true)
		assertEquals(WeatherNotice.UNAVAILABLE, provider.paramsFor(1_000_000L).weatherNotice)
		fixture.settings.value = fixture.settings.value.copy(sceneSimulatorEnabled = true, sceneSimulatorActive = true)
		runCurrent()
		assertNull(provider.paramsFor(1_000_000L).weatherNotice)
	}

	@Test
	fun `restored offline weather respects failure retry backoff`() = runTest {
		val fixture = Fixture(this)
		val first = fixture.provider()
		runCurrent()
		first.refresh(1_000_000L, force = true)
		runCurrent()
		fixture.offline()
		val restarted = fixture.provider()
		runCurrent()
		restarted.refresh(1_000_120L)
		assertEquals(1_000_120L, restarted.status.value.lastFailureEpochSeconds)
		restarted.refresh(1_000_125L)
		assertEquals(1_000_120L, restarted.status.value.lastFailureEpochSeconds)
	}

	private inner class Fixture(private val scope: TestScope) {
		val settings = MutableStateFlow(AppSettings())
		val weather = mockk<WeatherRepository>()
		private val locations = mockk<LocationRepository> {
			coEvery { currentLocation() } returns GeoLocation(52.52, 13.40)
		}
		private val preferences = mockk<SettingsRepository> {
			every { defaults } returns AppSettings()
			every { this@mockk.settings } returns this@Fixture.settings
		}
		private val photos = mockk<PhotoBackgroundRepository> {
			every { state } returns MutableStateFlow(PhotoBackgroundState(emptySet()))
		}
		private val store = PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { temporaryFolder.newFile("weather.preferences_pb") }
		private val logger = mockk<AppLogger>(relaxed = true)
		private val cache = WeatherCacheRepository(store, Json, logger)

		init {
			assertFalse(settings.value.hideStaleWeatherWarning)
			succeed(63)
		}

		fun provider() = WeatherSceneProvider(mockk<Context>(relaxed = true), locations, weather, mockk(relaxed = true), preferences, photos, logger, scope.backgroundScope, cache)

		fun offline() {
			coEvery { weather.current(any(), any()) } returns Result.failure(IllegalStateException("offline"))
		}

		fun succeed(code: Int) {
			coEvery { weather.current(any(), any()) } returns Result.success(
				WeatherSnapshot(
					observation = WeatherObservation(conditionForWmoCode(code), true, 10.0, 2.0, 5.0, CloudCover(95)),
					observedAtEpochSeconds = 1_000_000L,
					sunriseEpochSeconds = null,
					sunsetEpochSeconds = null
				)
			)
		}
	}
}
