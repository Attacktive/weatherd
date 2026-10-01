package xyz.attacktive.weatherd

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.data.api.OpenMeteoApiService
import xyz.attacktive.weatherd.data.api.dto.CurrentWeatherDto
import xyz.attacktive.weatherd.data.api.dto.ForecastResponseDto
import xyz.attacktive.weatherd.data.provider.ITALIA_METEO_ICON_2I_MODEL
import xyz.attacktive.weatherd.data.provider.ItaliaMeteoWeatherProvider

class ItaliaMeteoWeatherProviderTest {
	private val api = mockk<OpenMeteoApiService>()
	private val provider = ItaliaMeteoWeatherProvider(api)

	@Test
	fun `requests the ItaliaMeteo ICON-2I model`() = runTest {
		coEvery {
			api.forecast(
				any(),
				any(),
				any(),
				any(),
				any(),
				any(),
				any(),
				ITALIA_METEO_ICON_2I_MODEL
			)
		} returns ForecastResponseDto(
			latitude = 44.5,
			longitude = 11.34,
			current = CurrentWeatherDto(
				time = 1_759_318_200L,
				weatherCode = 2,
				isDay = 1,
				temperature = 20.0,
				precipitation = 0.0,
				windSpeed = 8.0,
				cloudCover = 38
			)
		)

		val snapshot = provider.current(44.5, 11.34)

		assertEquals(38, snapshot.observation.cloudCoverPercent)
		coVerify(exactly = 1) {
			api.forecast(
				44.5,
				11.34,
				any(),
				any(),
				any(),
				any(),
				any(),
				ITALIA_METEO_ICON_2I_MODEL
			)
		}
	}
}
