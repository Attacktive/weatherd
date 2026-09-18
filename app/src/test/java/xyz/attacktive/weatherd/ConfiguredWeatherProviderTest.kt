package xyz.attacktive.weatherd

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test
import xyz.attacktive.weatherd.data.provider.ConfiguredWeatherProvider
import xyz.attacktive.weatherd.data.provider.MetNoWeatherProvider
import xyz.attacktive.weatherd.data.provider.OpenMeteoWeatherProvider
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.repository.SettingsRepository

class ConfiguredWeatherProviderTest {
	private val settingsRepository = mockk<SettingsRepository>()
	private val openMeteo = mockk<OpenMeteoWeatherProvider>()
	private val metNo = mockk<MetNoWeatherProvider>()
	private val provider = ConfiguredWeatherProvider(settingsRepository, openMeteo, metNo)

	@Test
	fun `uses Open-Meteo by default`() = runTest {
		val expected = mockk<WeatherSnapshot>()
		every { settingsRepository.settings } returns flowOf(AppSettings())
		coEvery { openMeteo.current(37.5, 127.0) } returns expected

		val actual = provider.current(37.5, 127.0)

		assertSame(expected, actual)
		coVerify(exactly = 1) { openMeteo.current(37.5, 127.0) }
		coVerify(exactly = 0) { metNo.current(any(), any()) }
	}

	@Test
	fun `uses MET Norway when selected`() = runTest {
		val expected = mockk<WeatherSnapshot>()
		every { settingsRepository.settings } returns flowOf(AppSettings(weatherProvider = WeatherProviderType.MET_NORWAY))
		coEvery { metNo.current(37.5, 127.0) } returns expected

		val actual = provider.current(37.5, 127.0)

		assertSame(expected, actual)
		coVerify(exactly = 0) { openMeteo.current(any(), any()) }
		coVerify(exactly = 1) { metNo.current(37.5, 127.0) }
	}
}
