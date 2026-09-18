package xyz.attacktive.weatherd

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.data.api.OpenMeteoApiService
import xyz.attacktive.weatherd.data.api.dto.CurrentWeatherDto
import xyz.attacktive.weatherd.data.api.dto.DailyDto
import xyz.attacktive.weatherd.data.api.dto.ForecastResponseDto
import xyz.attacktive.weatherd.data.provider.OpenMeteoWeatherProvider
import xyz.attacktive.weatherd.domain.model.WeatherLabel

class OpenMeteoWeatherProviderTest {
	private val api = mockk<OpenMeteoApiService>()
	private val provider = OpenMeteoWeatherProvider(api)

	@Test
	fun `maps a successful forecast into a snapshot`() = runTest {
		coEvery { api.forecast(any(), any(), any(), any(), any(), any(), any()) } returns ForecastResponseDto(
			latitude = 37.5,
			longitude = 127.0,
			current = CurrentWeatherDto(
				time = 1_751_889_600L,
				weatherCode = 61,
				isDay = 1,
				temperature = 24.3,
				precipitation = 2.5,
				windSpeed = 12.0,
				cloudCover = 90
			),
			daily = DailyDto(sunrise = listOf(1_751_866_500L), sunset = listOf(1_751_918_700L))
		)

		val snapshot = provider.current(37.5, 127.0)

		assertEquals(WeatherLabel.LIGHT_RAIN, snapshot.observation.condition.label)
		assertTrue(snapshot.observation.isDay)
		assertEquals(2.5, snapshot.observation.precipitationMillimeters, 0.0001)
		assertEquals(90, snapshot.observation.cloudCoverPercent)
		assertEquals(1_751_866_500L, snapshot.sunriseEpochSeconds)
		assertEquals(1_751_918_700L, snapshot.sunsetEpochSeconds)
	}

	@Test
	fun `is day zero maps to night and missing daily leaves sun times null`() = runTest {
		coEvery { api.forecast(any(), any(), any(), any(), any(), any(), any()) } returns ForecastResponseDto(
			latitude = 0.0,
			longitude = 0.0,
			current = CurrentWeatherDto(0L, 0, 0, 10.0, 0.0, 3.0, 5),
			daily = null
		)

		val snapshot = provider.current(0.0, 0.0)

		assertFalse(snapshot.observation.isDay)
		assertNull(snapshot.sunriseEpochSeconds)
		assertNull(snapshot.sunsetEpochSeconds)
	}
}
