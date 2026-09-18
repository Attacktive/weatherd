package xyz.attacktive.weatherd

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.attacktive.weatherd.data.api.MetNoApiService
import xyz.attacktive.weatherd.data.api.dto.MetNoDataDto
import xyz.attacktive.weatherd.data.api.dto.MetNoForecastResponseDto
import xyz.attacktive.weatherd.data.api.dto.MetNoInstantDetailsDto
import xyz.attacktive.weatherd.data.api.dto.MetNoInstantDto
import xyz.attacktive.weatherd.data.api.dto.MetNoPeriodDetailsDto
import xyz.attacktive.weatherd.data.api.dto.MetNoPeriodDto
import xyz.attacktive.weatherd.data.api.dto.MetNoPropertiesDto
import xyz.attacktive.weatherd.data.api.dto.MetNoSummaryDto
import xyz.attacktive.weatherd.data.api.dto.MetNoTimeSeriesDto
import xyz.attacktive.weatherd.data.provider.MetNoWeatherProvider

class MetNoWeatherProviderTest {
	private val api = mockk<MetNoApiService>()
	private val provider = MetNoWeatherProvider(api)

	@Test
	fun `maps MET forecast into canonical snapshot`() = runTest {
		coEvery { api.forecast(any(), any()) } returns response(symbolCode = "heavyrainshowersandthunder_night")

		val snapshot = provider.current(37.500012, 127.000049)

		assertEquals(95, snapshot.observation.weatherCode)
		assertFalse(snapshot.observation.isDay)
		assertEquals(18.4, snapshot.observation.temperatureCelsius, 0.0001)
		assertEquals(3.2, snapshot.observation.precipitationMillimeters, 0.0001)
		assertEquals(18.0, snapshot.observation.windSpeedKilometersPerHour, 0.0001)
		assertEquals(77, snapshot.observation.cloudCoverPercent)
		assertEquals(1_757_818_800L, snapshot.observedAtEpochSeconds)
		assertNull(snapshot.sunriseEpochSeconds)
		assertNull(snapshot.sunsetEpochSeconds)
	}

	@Test
	fun `rounds coordinates to four decimals for MET cache friendliness`() = runTest {
		coEvery { api.forecast(any(), any()) } returns response(symbolCode = "clearsky_day")

		provider.current(37.50006, 127.00004)

		coVerify(exactly = 1) { api.forecast(37.5001, 127.0) }
	}

	private fun response(symbolCode: String) = MetNoForecastResponseDto(
		properties = MetNoPropertiesDto(
			timeseries = listOf(
				MetNoTimeSeriesDto(
					time = "2025-09-14T03:00:00Z",
					data = MetNoDataDto(
						instant = MetNoInstantDto(
							details = MetNoInstantDetailsDto(
								airTemperature = 18.4,
								cloudAreaFraction = 77.8,
								windSpeed = 5.0
							)
						),
						nextOneHour = MetNoPeriodDto(
							summary = MetNoSummaryDto(symbolCode),
							details = MetNoPeriodDetailsDto(3.2)
						)
					)
				)
			)
		)
	)
}
