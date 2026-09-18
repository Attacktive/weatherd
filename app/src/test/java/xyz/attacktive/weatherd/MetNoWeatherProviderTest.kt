package xyz.attacktive.weatherd

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.data.api.MetNoApiService
import xyz.attacktive.weatherd.data.api.dto.MetNoDataDto
import xyz.attacktive.weatherd.data.api.dto.MetNoForecastResponseDto
import xyz.attacktive.weatherd.data.api.dto.MetNoInstantDetailsDto
import xyz.attacktive.weatherd.data.api.dto.MetNoInstantDto
import xyz.attacktive.weatherd.data.api.dto.MetNoPeriodDetailsDto
import xyz.attacktive.weatherd.data.api.dto.MetNoPeriodDto
import xyz.attacktive.weatherd.data.api.dto.MetNoPropertiesDto
import xyz.attacktive.weatherd.data.api.dto.MetNoSolarPositionDto
import xyz.attacktive.weatherd.data.api.dto.MetNoSummaryDto
import xyz.attacktive.weatherd.data.api.dto.MetNoSunEventDto
import xyz.attacktive.weatherd.data.api.dto.MetNoSunPropertiesDto
import xyz.attacktive.weatherd.data.api.dto.MetNoSunriseResponseDto
import xyz.attacktive.weatherd.data.api.dto.MetNoTimeSeriesDto
import xyz.attacktive.weatherd.data.api.dto.toMetNoCondition
import xyz.attacktive.weatherd.data.api.dto.toSnapshot
import xyz.attacktive.weatherd.data.provider.MetNoWeatherProvider
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.WeatherLabel
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_HEAVY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

class MetNoWeatherProviderTest {
	private val api = mockk<MetNoApiService>()
	private val provider = MetNoWeatherProvider(api)

	@Test
	fun `maps MET forecast and solar data into canonical snapshot`() = runTest {
		coEvery { api.forecast("37.5000", "127.0000") } returns response("heavyrainshowersandthunder_night")
		coEvery { api.sunrise("37.5000", "127.0000", "2025-09-14") } returns sunResponse(
			sunrise = "2025-09-14T06:00:00Z",
			sunset = "2025-09-14T18:00:00Z"
		)

		val snapshot = provider.current(37.500012, 127.000049)

		assertEquals(WeatherLabel.THUNDERSTORM, snapshot.observation.condition.label)
		assertEquals(PrecipitationKind.RAIN, snapshot.observation.condition.precipitationKind)
		assertEquals(SEVERITY_HEAVY, snapshot.observation.condition.severity, 0.0001f)
		assertTrue(snapshot.observation.condition.thunder)
		assertFalse(snapshot.observation.isDay)
		assertEquals(18.4, snapshot.observation.temperatureCelsius, 0.0001)
		assertEquals(3.2, snapshot.observation.precipitationMillimeters, 0.0001)
		assertEquals(18.0, snapshot.observation.windSpeedKilometersPerHour, 0.0001)
		assertEquals(77, snapshot.observation.cloudCoverPercent)
		assertEquals(1_757_818_800L, snapshot.observedAtEpochSeconds)
	}

	@Test
	fun `truncates and formats coordinates without scientific notation`() = runTest {
		coEvery { api.forecast("0.0006", "-0.0001") } returns response("clearsky_day")
		coEvery { api.sunrise("0.0006", "-0.0001", "2025-09-14") } returns sunResponse()

		provider.current(0.00069, -0.00019)

		coVerify(exactly = 1) { api.forecast("0.0006", "-0.0001") }
		coVerify(exactly = 1) { api.sunrise("0.0006", "-0.0001", "2025-09-14") }
	}

	@Test
	fun `all documented MET symbols map without falling through`() {
		MET_SYMBOL_CODES.forEach { symbolCode ->
			symbolCode.toMetNoCondition()
		}
	}

	@Test
	fun `sleet keeps its kind and intensity instead of masquerading as freezing rain`() {
		val light = "lightsleet".toMetNoCondition()
		val heavy = "heavysleet".toMetNoCondition()

		assertEquals(PrecipitationKind.SLEET, light.precipitationKind)
		assertEquals(SEVERITY_DRIZZLE, light.severity, 0.0001f)
		assertNull(light.label)
		assertEquals(PrecipitationKind.SLEET, heavy.precipitationKind)
		assertEquals(SEVERITY_HEAVY, heavy.severity, 0.0001f)
		assertNull(heavy.label)
	}

	@Test
	fun `thundersnow preserves snow precipitation`() {
		val condition = "snowandthunder".toMetNoCondition()

		assertEquals(PrecipitationKind.SNOW, condition.precipitationKind)
		assertEquals(SEVERITY_STORM, condition.severity, 0.0001f)
		assertTrue(condition.thunder)
	}

	@Test
	fun `polar twilight uses solar visibility instead of the symbol suffix`() = runTest {
		coEvery { api.forecast("78.0000", "15.0000") } returns response("clearsky_polartwilight")
		coEvery { api.sunrise("78.0000", "15.0000", "2025-09-14") } returns sunResponse(
			sunrise = null,
			sunset = null,
			solarNoonVisible = false
		)

		val snapshot = provider.current(78.0, 15.0)

		assertFalse(snapshot.observation.isDay)
		assertNull(snapshot.sunriseEpochSeconds)
		assertNull(snapshot.sunsetEpochSeconds)
	}

	@Test
	fun `missing next hour data fails instead of inventing cloudy weather`() {
		val response = MetNoForecastResponseDto(
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
							)
						)
					)
				)
			)
		)
		var failed = false

		try {
			response.toSnapshot(sunResponse())
		} catch (_: IllegalStateException) {
			failed = true
		}

		assertTrue(failed)
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

	private fun sunResponse(
		sunrise: String? = "2025-09-14T00:00:00Z",
		sunset: String? = "2025-09-14T12:00:00Z",
		solarNoonVisible: Boolean = true
	) = MetNoSunriseResponseDto(
		properties = MetNoSunPropertiesDto(
			sunrise = sunrise?.let(::MetNoSunEventDto),
			sunset = sunset?.let(::MetNoSunEventDto),
			solarnoon = MetNoSolarPositionDto(solarNoonVisible),
			solarMidnight = MetNoSolarPositionDto(!solarNoonVisible)
		)
	)

	companion object {
		private val MET_SYMBOL_CODES = listOf(
			"clearsky",
			"fair",
			"partlycloudy",
			"cloudy",
			"fog",
			"lightrainshowers",
			"rainshowers",
			"heavyrainshowers",
			"lightsleetshowers",
			"sleetshowers",
			"heavysleetshowers",
			"lightsnowshowers",
			"snowshowers",
			"heavysnowshowers",
			"lightrain",
			"rain",
			"heavyrain",
			"lightsleet",
			"sleet",
			"heavysleet",
			"lightsnow",
			"snow",
			"heavysnow",
			"lightrainshowersandthunder",
			"rainshowersandthunder",
			"heavyrainshowersandthunder",
			"lightssleetshowersandthunder",
			"sleetshowersandthunder",
			"heavysleetshowersandthunder",
			"lightssnowshowersandthunder",
			"snowshowersandthunder",
			"heavysnowshowersandthunder",
			"lightrainandthunder",
			"rainandthunder",
			"heavyrainandthunder",
			"lightsleetandthunder",
			"sleetandthunder",
			"heavysleetandthunder",
			"lightsnowandthunder",
			"snowandthunder",
			"heavysnowandthunder"
		)
	}
}
