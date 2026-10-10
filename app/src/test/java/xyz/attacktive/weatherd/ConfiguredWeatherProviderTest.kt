package xyz.attacktive.weatherd

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.data.provider.ConfiguredWeatherProvider
import xyz.attacktive.weatherd.data.provider.MetNoWeatherProvider
import xyz.attacktive.weatherd.data.provider.OpenMeteoWeatherProvider
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.WeatherFallbackReason
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.model.WeatherSource
import xyz.attacktive.weatherd.domain.repository.SettingsRepository

class ConfiguredWeatherProviderTest {
	private val settingsRepository = mockk<SettingsRepository>()
	private val openMeteo = mockk<OpenMeteoWeatherProvider>()
	private val metNo = mockk<MetNoWeatherProvider>()
	private val provider = ConfiguredWeatherProvider(settingsRepository, openMeteo, metNo)

	@Test
	fun `regional preference survives travel and resumes when coverage returns`() = runTest {
		val settings = AppSettings(
			weatherProvider = WeatherProviderType.ITALIA_METEO,
			weatherFallbackProvider = WeatherProviderType.OPEN_METEO
		)

		every { settingsRepository.settings } returns flowOf(settings)
		coEvery { openMeteo.current(44.5, 11.34, WeatherProviderType.ITALIA_METEO) } returns snapshot(WeatherProviderType.ITALIA_METEO)
		coEvery { openMeteo.current(55.75, 37.61, WeatherProviderType.OPEN_METEO) } returns snapshot(WeatherProviderType.OPEN_METEO)

		val home = provider.current(44.5, 11.34)
		val abroad = provider.current(55.75, 37.61)
		val homeAgain = provider.current(44.5, 11.34)

		assertEquals(WeatherProviderType.ITALIA_METEO, home.source.provider)
		assertEquals(null, home.source.fallbackReason)
		assertEquals(WeatherProviderType.OPEN_METEO, abroad.source.provider)
		assertEquals(WeatherFallbackReason.OUTSIDE_COVERAGE, abroad.source.fallbackReason)
		assertEquals(WeatherProviderType.ITALIA_METEO, homeAgain.source.provider)
		assertEquals(null, homeAgain.source.fallbackReason)
		coVerify(exactly = 2) { openMeteo.current(44.5, 11.34, WeatherProviderType.ITALIA_METEO) }
		coVerify(exactly = 1) { openMeteo.current(55.75, 37.61, WeatherProviderType.OPEN_METEO) }
		coVerify(exactly = 0) { settingsRepository.save(any()) }
	}

	@Test
	fun `outside regional coverage goes straight to the configured global fallback`() = runTest {
		every { settingsRepository.settings } returns flowOf(
			AppSettings(
				weatherProvider = WeatherProviderType.ITALIA_METEO,
				weatherFallbackProvider = WeatherProviderType.MET_NORWAY
			)
		)

		coEvery { metNo.current(55.75, 37.61) } returns snapshot(WeatherProviderType.MET_NORWAY)

		val actual = provider.current(55.75, 37.61)

		assertEquals(WeatherProviderType.MET_NORWAY, actual.source.provider)
		assertEquals(WeatherFallbackReason.OUTSIDE_COVERAGE, actual.source.fallbackReason)
		coVerify(exactly = 0) { openMeteo.current(any(), any(), WeatherProviderType.ITALIA_METEO) }
		coVerify(exactly = 1) { metNo.current(55.75, 37.61) }
	}

	@Test
	fun `primary failure falls back once and preserves actual source`() = runTest {
		every { settingsRepository.settings } returns flowOf(
			AppSettings(
				weatherProvider = WeatherProviderType.DWD_ICON_GLOBAL,
				weatherFallbackProvider = WeatherProviderType.MET_NORWAY
			)
		)

		coEvery { openMeteo.current(37.5, 127.0, WeatherProviderType.DWD_ICON_GLOBAL) } throws IllegalStateException("primary unavailable")
		coEvery { metNo.current(37.5, 127.0) } returns snapshot(WeatherProviderType.MET_NORWAY)

		val actual = provider.current(37.5, 127.0)

		assertEquals(WeatherProviderType.MET_NORWAY, actual.source.provider)
		assertEquals(WeatherFallbackReason.PRIMARY_FAILED, actual.source.fallbackReason)
		coVerify(exactly = 1) { openMeteo.current(37.5, 127.0, WeatherProviderType.DWD_ICON_GLOBAL) }
		coVerify(exactly = 1) { metNo.current(37.5, 127.0) }
	}

	@Test
	fun `cancellation never triggers fallback`() = runTest {
		every { settingsRepository.settings } returns flowOf(
			AppSettings(
				weatherProvider = WeatherProviderType.DWD_ICON_GLOBAL,
				weatherFallbackProvider = WeatherProviderType.MET_NORWAY
			)
		)

		coEvery { openMeteo.current(37.5, 127.0, WeatherProviderType.DWD_ICON_GLOBAL) } throws CancellationException("obsolete request")

		val failure = runCatching { provider.current(37.5, 127.0) }.exceptionOrNull()

		assertTrue(failure is CancellationException)
		coVerify(exactly = 0) { metNo.current(any(), any()) }
	}

	@Test
	fun `identical primary and fallback are never requested twice`() = runTest {
		val failure = IllegalStateException("Open-Meteo unavailable")
		every { settingsRepository.settings } returns flowOf(AppSettings())
		coEvery { openMeteo.current(37.5, 127.0, WeatherProviderType.OPEN_METEO) } throws failure

		val actual = runCatching { provider.current(37.5, 127.0) }.exceptionOrNull()

		assertSame(failure, actual)
		coVerify(exactly = 1) { openMeteo.current(37.5, 127.0, WeatherProviderType.OPEN_METEO) }
	}

	@Test
	fun `when both providers fail the fallback failure keeps the primary failure suppressed`() = runTest {
		val primaryFailure = IllegalStateException("primary unavailable")
		val fallbackFailure = IllegalArgumentException("fallback unavailable")
		every { settingsRepository.settings } returns flowOf(
			AppSettings(
				weatherProvider = WeatherProviderType.DWD_ICON_GLOBAL,
				weatherFallbackProvider = WeatherProviderType.MET_NORWAY
			)
		)

		coEvery { openMeteo.current(37.5, 127.0, WeatherProviderType.DWD_ICON_GLOBAL) } throws primaryFailure
		coEvery { metNo.current(37.5, 127.0) } throws fallbackFailure

		val actual = runCatching { provider.current(37.5, 127.0) }.exceptionOrNull()

		assertSame(fallbackFailure, actual)
		assertTrue(actual?.suppressed?.contains(primaryFailure) == true)
		coVerify(exactly = 1) { metNo.current(37.5, 127.0) }
	}

	private fun snapshot(provider: WeatherProviderType) = WeatherSnapshot(
		observation = mockk<WeatherObservation>(),
		observedAtEpochSeconds = 1_000_000L,
		sunriseEpochSeconds = null,
		sunsetEpochSeconds = null,
		source = WeatherSource(provider)
	)
}
