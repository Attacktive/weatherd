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
import xyz.attacktive.weatherd.data.provider.MetNoWeatherProvider
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.WeatherLabel
import xyz.attacktive.weatherd.domain.weather.SEVERITY_HEAVY

class MetNoWeatherProviderTest {
	private val api = mockk<MetNoApiService>()
	private val provider = MetNoWeatherProvider(api)

	@Test
	fun `maps MET forecast and solar data into canonical snapshot`() = runTest {
		coEvery { api.forecast("37.5000", "127.0000") } returns metNoForecastResponse("heavyrainshowersandthunder_night")
		coEvery { api.sunrise("37.5000", "127.0000", "2025-09-14") } returns metNoSunResponse(
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
		coEvery { api.forecast("0.0006", "-0.0001") } returns metNoForecastResponse("clearsky_day")
		coEvery { api.sunrise("0.0006", "-0.0001", "2025-09-14") } returns metNoSunResponse()

		provider.current(0.00069, -0.00019)

		coVerify(exactly = 1) { api.forecast("0.0006", "-0.0001") }
		coVerify(exactly = 1) { api.sunrise("0.0006", "-0.0001", "2025-09-14") }
	}

	@Test
	fun `reuses sunrise for the same location and solar date`() = runTest {
		coEvery { api.forecast("37.5000", "127.0000") } returns metNoForecastResponse("cloudy")
		coEvery { api.sunrise("37.5000", "127.0000", "2025-09-14") } returns metNoSunResponse()

		provider.current(37.500012, 127.000049)
		provider.current(37.500012, 127.000049)

		coVerify(exactly = 2) { api.forecast("37.5000", "127.0000") }
		coVerify(exactly = 1) { api.sunrise("37.5000", "127.0000", "2025-09-14") }
	}

	@Test
	fun `polar twilight uses solar visibility instead of the symbol suffix`() = runTest {
		coEvery { api.forecast("78.0000", "15.0000") } returns metNoForecastResponse("clearsky_polartwilight")
		coEvery { api.sunrise("78.0000", "15.0000", "2025-09-14") } returns metNoSunResponse(
			sunrise = null,
			sunset = null,
			solarNoonVisible = false
		)

		val snapshot = provider.current(78.0, 15.0)

		assertFalse(snapshot.observation.isDay)
		assertNull(snapshot.sunriseEpochSeconds)
		assertNull(snapshot.sunsetEpochSeconds)
	}
}
