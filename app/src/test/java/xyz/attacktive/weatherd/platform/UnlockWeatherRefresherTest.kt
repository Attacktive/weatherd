package xyz.attacktive.weatherd.platform

import java.io.IOException
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.render.WeatherRefreshOutcome
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.weather.UnlockRefreshResult
import xyz.attacktive.weatherd.util.AppLogger

class UnlockWeatherRefresherTest {
	@Test
	fun `disabled work does not even construct the weather provider`() = runTest {
		val fixture = Fixture(enabled = false)

		assertEquals(UnlockRefreshResult.SKIPPED, fixture.refresher.refresh())
		verify(exactly = 0) { fixture.provider.get() }
	}

	@Test
	fun `queued work rechecks a subsequently disabled preference`() = runTest {
		val fixture = Fixture()
		fixture.settings.value = fixture.settings.value.copy(refreshOnUnlockEnabled = false)

		assertEquals(UnlockRefreshResult.SKIPPED, fixture.refresher.refresh())
		verify(exactly = 0) { fixture.provider.get() }
	}

	@Test
	fun `unlock refresh bypasses the normal interval and duplicates are throttled`() = runTest {
		val fixture = Fixture()
		coEvery { fixture.scene.refreshWithResult(any(), true, false, any()) } returns Result.success(WeatherRefreshOutcome.REFRESHED)

		assertEquals(UnlockRefreshResult.COMPLETED, fixture.refresher.refresh())
		assertEquals(UnlockRefreshResult.SKIPPED, fixture.refresher.refresh())
		coVerify(exactly = 1) { fixture.scene.refreshWithResult(any(), true, false, any()) }
	}

	@Test
	fun `simulator and missing location skips do not become retries`() = runTest {
		val fixture = Fixture()
		coEvery { fixture.scene.refreshWithResult(any(), true, false, any()) } returns Result.success(WeatherRefreshOutcome.SKIPPED)

		assertEquals(UnlockRefreshResult.COMPLETED, fixture.refresher.refresh())
		verify(exactly = 0) { fixture.logger.error(any(), any(), any()) }
	}

	@Test
	fun `provider failures are contained logged and throttled`() = runTest {
		val fixture = Fixture()
		val failure = IOException("offline")
		coEvery { fixture.scene.refreshWithResult(any(), true, false, any()) } returns Result.failure(failure)

		assertEquals(UnlockRefreshResult.FAILED, fixture.refresher.refresh())
		assertEquals(UnlockRefreshResult.SKIPPED, fixture.refresher.refresh())
		verify(exactly = 1) { fixture.logger.error(any(), any(), failure) }
		coVerify(exactly = 1) { fixture.scene.refreshWithResult(any(), true, false, any()) }
	}

	@Test
	fun `unexpected provider exceptions do not escape the worker`() = runTest {
		val fixture = Fixture()
		coEvery { fixture.scene.refreshWithResult(any(), true, false, any()) } throws IOException("unavailable")

		assertEquals(UnlockRefreshResult.FAILED, fixture.refresher.refresh())
		verify(exactly = 1) { fixture.logger.error(any(), any(), any()) }
	}

	@Test
	fun `work cancellation is propagated rather than logged as weather failure`() = runTest {
		val fixture = Fixture()
		coEvery { fixture.scene.refreshWithResult(any(), true, false, any()) } throws CancellationException("disabled")
		var canceled = false

		try {
			fixture.refresher.refresh()
		} catch (_: CancellationException) {
			canceled = true
		}

		assertTrue(canceled)
		verify(exactly = 0) { fixture.logger.error(any(), any(), any()) }
	}

	private class Fixture(enabled: Boolean = true) {
		val settings = MutableStateFlow(AppSettings(refreshOnUnlockEnabled = enabled, updateIntervalMinutes = 360))
		val repository = mockk<SettingsRepository> { every { settings } returns this@Fixture.settings }
		val scene = mockk<WeatherSceneProvider>()
		val provider = mockk<Provider<WeatherSceneProvider>> { every { get() } returns scene }
		val logger = mockk<AppLogger>(relaxed = true)
		val refresher = UnlockWeatherRefresher(repository, provider, logger)
	}
}
