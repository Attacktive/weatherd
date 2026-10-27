package xyz.attacktive.weatherd.domain.render

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.AnimationIdleTimeout
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.CloudCover
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.repository.LocationRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundState
import xyz.attacktive.weatherd.domain.repository.ReverseGeocodingRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.repository.WeatherRepository
import xyz.attacktive.weatherd.domain.weather.conditionForWmoCode
import xyz.attacktive.weatherd.util.AppLogger

@OptIn(ExperimentalCoroutinesApi::class)
class VisibleWeatherRefreshTest {
	@Test
	fun `visible scene keeps acquiring weather after animations settle and stops on cancellation`() = runTest {
		val fixture = Fixture(backgroundScope)
		val visible = backgroundScope.launch {
			fixture.provider.refreshWhileVisible { START_SECONDS + testScheduler.currentTime / 1000 }
		}
		runCurrent()
		coVerify(exactly = 1) { fixture.weather.current(any(), any()) }
		advanceTimeBy(60_000)
		runCurrent()
		coVerify(exactly = 2) { fixture.weather.current(any(), any()) }
		visible.cancel()
		runCurrent()
		advanceTimeBy(120_000)
		runCurrent()
		coVerify(exactly = 2) { fixture.weather.current(any(), any()) }
	}

	@Test
	fun `two visible surfaces share acquisition and one can leave without stopping the other`() = runTest {
		val fixture = Fixture(backgroundScope)
		val response = CompletableDeferred<Result<WeatherSnapshot>>()
		coEvery { fixture.weather.current(any(), any()) } coAnswers { response.await() }
		val preview = backgroundScope.launch { fixture.provider.refreshWhileVisible { START_SECONDS + testScheduler.currentTime / 1000 } }
		val wallpaper = backgroundScope.launch { fixture.provider.refreshWhileVisible { START_SECONDS + testScheduler.currentTime / 1000 } }
		runCurrent()
		coVerify(exactly = 1) { fixture.weather.current(any(), any()) }
		preview.cancel()
		response.complete(Result.success(fixture.snapshot))
		runCurrent()
		advanceTimeBy(60_000)
		runCurrent()
		coVerify(exactly = 2) { fixture.weather.current(any(), any()) }
		wallpaper.cancel()
	}

	@Test
	fun `visible failures back off and recover without a visibility change`() = runTest {
		val fixture = Fixture(backgroundScope)
		coEvery { fixture.weather.current(any(), any()) } returns Result.failure(IllegalStateException("Offline"))
		backgroundScope.launch { fixture.provider.refreshWhileVisible { START_SECONDS + testScheduler.currentTime / 1000 } }
		runCurrent()
		assertEquals(START_SECONDS, fixture.provider.status.value.lastFailureEpochSeconds)
		assertNull(fixture.provider.status.value.lastRefreshEpochSeconds)
		advanceTimeBy(60_000)
		runCurrent()
		coVerify(exactly = 2) { fixture.weather.current(any(), any()) }
		advanceTimeBy(90_000)
		runCurrent()
		coVerify(exactly = 2) { fixture.weather.current(any(), any()) }
		coEvery { fixture.weather.current(any(), any()) } returns Result.success(fixture.snapshot)
		advanceTimeBy(30_000)
		runCurrent()
		coVerify(exactly = 3) { fixture.weather.current(any(), any()) }
		assertNull(fixture.provider.status.value.lastFailureEpochSeconds)
		assertEquals(START_SECONDS + 180, fixture.provider.status.value.lastRefreshEpochSeconds)
	}

	@Test
	fun `failure keeps the last success time and manual refresh bypasses backoff`() = runTest {
		val fixture = Fixture(backgroundScope)
		fixture.provider.refresh(START_SECONDS)
		coEvery { fixture.weather.current(any(), any()) } returns Result.failure(IllegalStateException("Offline"))
		fixture.provider.refresh(START_SECONDS + 60)
		fixture.provider.refresh(START_SECONDS + 90)
		coVerify(exactly = 2) { fixture.weather.current(any(), any()) }
		assertEquals(START_SECONDS, fixture.provider.status.value.lastRefreshEpochSeconds)
		assertNotNull(fixture.provider.status.value.lastFailureEpochSeconds)
		coEvery { fixture.weather.current(any(), any()) } returns Result.success(fixture.snapshot)
		fixture.provider.refresh(START_SECONDS + 91, force = true)
		coVerify(exactly = 3) { fixture.weather.current(any(), any()) }
		assertEquals(START_SECONDS + 91, fixture.provider.status.value.lastRefreshEpochSeconds)
		assertNull(fixture.provider.status.value.lastFailureEpochSeconds)
	}

	@Test
	fun `late failure cannot replace a newer successful forced refresh`() = runTest {
		val fixture = Fixture(backgroundScope)
		val response = CompletableDeferred<Result<WeatherSnapshot>>()
		coEvery { fixture.weather.current(any(), any()) } coAnswers { response.await() }
		val ordinary = async { fixture.provider.refresh(START_SECONDS) }
		runCurrent()
		coEvery { fixture.weather.current(any(), any()) } returns Result.success(fixture.snapshot)
		fixture.provider.refresh(START_SECONDS + 1, force = true)
		response.complete(Result.failure(IllegalStateException("Old failure")))
		ordinary.await()
		assertEquals(START_SECONDS + 1, fixture.provider.status.value.lastRefreshEpochSeconds)
		assertNull(fixture.provider.status.value.lastFailureEpochSeconds)
	}

	@Test
	fun `location change bypasses the previous targets retry delay`() = runTest {
		val fixture = Fixture(backgroundScope)
		coEvery { fixture.weather.current(any(), any()) } returns Result.failure(IllegalStateException("Offline"))
		fixture.provider.refresh(START_SECONDS)
		fixture.settings.value = fixture.settings.value.copy(useDeviceLocation = false, manualLatitude = 37.57, manualLongitude = 126.98)
		coEvery { fixture.weather.current(any(), any()) } returns Result.success(fixture.snapshot)
		fixture.provider.refresh(START_SECONDS + 1)
		coVerify(exactly = 1) { fixture.weather.current(37.57, 126.98) }
		assertNull(fixture.provider.status.value.lastFailureEpochSeconds)
	}

	@Test
	fun `simulator suppresses acquisition until live mode returns`() = runTest {
		val fixture = Fixture(backgroundScope)
		fixture.settings.value = fixture.settings.value.copy(sceneSimulatorActive = true, sceneSimulatorEnabled = true)
		backgroundScope.launch { fixture.provider.refreshWhileVisible { START_SECONDS + testScheduler.currentTime / 1000 } }
		runCurrent()
		advanceTimeBy(60_000)
		runCurrent()
		coVerify(exactly = 0) { fixture.weather.current(any(), any()) }
		fixture.settings.value = fixture.settings.value.copy(sceneSimulatorActive = false)
		advanceTimeBy(30_000)
		runCurrent()
		coVerify(exactly = 1) { fixture.weather.current(any(), any()) }
	}

	private class Fixture(scope: CoroutineScope) {
		val settings = MutableStateFlow(AppSettings(useDeviceLocation = true, updateIntervalMinutes = 1, animationIdleTimeout = AnimationIdleTimeout.SECONDS_15))
		val weather = mockk<WeatherRepository>()
		val snapshot = WeatherSnapshot(
			observation = WeatherObservation(conditionForWmoCode(3), true, 10.0, 0.0, 5.0, CloudCover(50)),
			observedAtEpochSeconds = START_SECONDS,
			sunriseEpochSeconds = null,
			sunsetEpochSeconds = null
		)
		private val locations = mockk<LocationRepository> {
			coEvery { currentLocation() } returns GeoLocation(52.52, 13.40)
		}
		private val repository = mockk<SettingsRepository> {
			every { defaults } returns AppSettings()
			every { settings } returns this@Fixture.settings
		}
		private val photos = mockk<PhotoBackgroundRepository> {
			every { state } returns MutableStateFlow(PhotoBackgroundState(emptySet()))
		}
		val provider = WeatherSceneProvider(mockk<Context>(relaxed = true), locations, weather, mockk<ReverseGeocodingRepository>(), repository, photos, mockk<AppLogger>(relaxed = true), scope, weatherCacheFixture())

		init {
			coEvery { weather.current(any(), any()) } returns Result.success(snapshot)
		}
	}

	companion object {
		private const val START_SECONDS = 1_000_000L
	}
}
