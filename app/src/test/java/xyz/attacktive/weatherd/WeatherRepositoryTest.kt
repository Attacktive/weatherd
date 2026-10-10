package xyz.attacktive.weatherd

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.CloudCover
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.provider.WeatherProvider
import xyz.attacktive.weatherd.domain.repository.WeatherRepository
import xyz.attacktive.weatherd.domain.weather.conditionForWmoCode

class WeatherRepositoryTest {
	private val provider = mockk<WeatherProvider>()
	private val repository = WeatherRepository(provider, FakeAppLogger())

	@Test
	fun `returns snapshot from provider`() = runTest {
		val expected = WeatherSnapshot(
			observation = WeatherObservation(
				condition = conditionForWmoCode(61),
				isDay = true,
				temperatureCelsius = 24.3,
				precipitationMillimeters = 2.5,
				windSpeedKilometersPerHour = 12.0,
				cloudCover = CloudCover(90)
			),
			observedAtEpochSeconds = 1_751_889_600L,
			sunriseEpochSeconds = 1_751_866_500L,
			sunsetEpochSeconds = 1_751_918_700L
		)

		coEvery { provider.current(37.5, 127.0) } returns expected

		val result = repository.current(37.5, 127.0)

		assertTrue(result.isSuccess)
		assertEquals(expected, result.getOrThrow())
	}

	@Test
	fun `propagates provider failure as a failed result`() = runTest {
		coEvery { provider.current(1.0, 2.0) } throws RuntimeException("boom")

		val result = repository.current(1.0, 2.0)

		assertTrue(result.isFailure)
	}

	@Test
	fun `rethrows coroutine cancellation`() = runTest {
		coEvery { provider.current(1.0, 2.0) } throws CancellationException("canceled")
		var canceled = false

		try {
			repository.current(1.0, 2.0)
		} catch (_: CancellationException) {
			canceled = true
		}

		assertTrue(canceled)
	}
}
