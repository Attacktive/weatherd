package xyz.attacktive.weatherd

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.attacktive.weatherd.data.provider.MetNoWeatherProvider
import xyz.attacktive.weatherd.data.provider.OpenMeteoWeatherProvider
import xyz.attacktive.weatherd.data.provider.WeatherComparisonService
import xyz.attacktive.weatherd.data.provider.WeatherSampleStatus
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.CloudCover
import xyz.attacktive.weatherd.domain.model.WeatherCondition
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.repository.LocationRepository

class WeatherComparisonServiceTest {
	private val locations = mockk<LocationRepository>()
	private val openMeteo = mockk<OpenMeteoWeatherProvider>()
	private val metNorway = mockk<MetNoWeatherProvider>()
	private val comparison = WeatherComparisonService(locations, openMeteo, metNorway)
	private val snapshot = WeatherSnapshot(
		observation = WeatherObservation(
			condition = WeatherCondition(),
			isDay = true,
			temperatureCelsius = 20.0,
			precipitationMillimeters = 0.0,
			windSpeedKilometersPerHour = 5.0,
			cloudCover = CloudCover(10)
		),
		observedAtEpochSeconds = 1_700_000_000L,
		sunriseEpochSeconds = null,
		sunsetEpochSeconds = null
	)

	@Test
	fun comparesAllFourProvidersAtTheExactSameSelectedWeatherPoint() = runTest {
		val latitude = 41.9028
		val longitude = 12.4964
		for (provider in listOf(WeatherProviderType.OPEN_METEO, WeatherProviderType.ITALIA_METEO, WeatherProviderType.DWD_ICON_EU)) {
			coEvery { openMeteo.current(latitude, longitude, provider) } returns snapshot
		}

		coEvery { metNorway.current(latitude, longitude) } returns snapshot

		val captured = comparison.capture(
			AppSettings(
				useDeviceLocation = false,
				manualLatitude = latitude,
				manualLongitude = longitude
			)
		)

		assertEquals(
			listOf(WeatherProviderType.OPEN_METEO, WeatherProviderType.ITALIA_METEO, WeatherProviderType.DWD_ICON_EU, WeatherProviderType.MET_NORWAY),
			captured?.samples?.map { it.provider }
		)
		assertEquals(List(4) { WeatherSampleStatus.SUCCESS }, captured?.samples?.map { it.status })
		coVerify(exactly = 1) { openMeteo.current(latitude, longitude, WeatherProviderType.OPEN_METEO) }
		coVerify(exactly = 1) { openMeteo.current(latitude, longitude, WeatherProviderType.ITALIA_METEO) }
		coVerify(exactly = 1) { openMeteo.current(latitude, longitude, WeatherProviderType.DWD_ICON_EU) }
		coVerify(exactly = 1) { metNorway.current(latitude, longitude) }
	}

	@Test
	fun skipsRegionalModelsOutsideTheirCoverageWithoutMakingRequests() = runTest {
		val latitude = 37.57
		val longitude = 126.98
		coEvery { openMeteo.current(latitude, longitude, WeatherProviderType.OPEN_METEO) } returns snapshot
		coEvery { metNorway.current(latitude, longitude) } returns snapshot

		val captured = comparison.capture(
			AppSettings(
				useDeviceLocation = false,
				manualLatitude = latitude,
				manualLongitude = longitude
			)
		)

		assertEquals(
			listOf(WeatherSampleStatus.SUCCESS, WeatherSampleStatus.OUTSIDE_COVERAGE, WeatherSampleStatus.OUTSIDE_COVERAGE, WeatherSampleStatus.SUCCESS),
			captured?.samples?.map { it.status }
		)
		coVerify(exactly = 0) { openMeteo.current(latitude, longitude, WeatherProviderType.ITALIA_METEO) }
		coVerify(exactly = 0) { openMeteo.current(latitude, longitude, WeatherProviderType.DWD_ICON_EU) }
	}

	@Test
	fun unavailableManualLocationNeverStartsRequests() = runTest {
		assertNull(comparison.capture(AppSettings(useDeviceLocation = false)))
		coVerify(exactly = 0) { openMeteo.current(any(), any(), any()) }
		coVerify(exactly = 0) { metNorway.current(any(), any()) }
	}
}
