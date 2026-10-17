package xyz.attacktive.weatherd

import java.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.data.provider.ObservedSky
import xyz.attacktive.weatherd.data.provider.WeatherComparisonCapture
import xyz.attacktive.weatherd.data.provider.WeatherComparisonSample
import xyz.attacktive.weatherd.data.provider.WeatherSampleStatus
import xyz.attacktive.weatherd.data.api.dto.ForecastResponseDto
import xyz.attacktive.weatherd.data.api.dto.toSnapshot
import xyz.attacktive.weatherd.domain.model.CloudCover
import xyz.attacktive.weatherd.domain.model.CloudLayers
import xyz.attacktive.weatherd.domain.model.WeatherCondition
import xyz.attacktive.weatherd.domain.model.WeatherLabel
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.model.PrecipitationWindow
import xyz.attacktive.weatherd.domain.model.PrecipitationWindowDirection

class WeatherComparisonReportTest {
	@Test
	fun reportOmitsCoordinatesEvenWhenSourceSnapshotHasThem() {
		val snapshot = WeatherSnapshot(
			observation = WeatherObservation(
				condition = WeatherCondition(label = WeatherLabel.MAINLY_CLEAR),
				isDay = true,
				temperatureCelsius = 23.5,
				precipitationMillimeters = 0.0,
				windSpeedKilometersPerHour = 5.0,
				cloudCover = CloudCover(18, CloudLayers(8, 11, 15))
			),
			observedAtEpochSeconds = 1_700_000_000L,
			sunriseEpochSeconds = null,
			sunsetEpochSeconds = null,
			locationLongitude = 12.4964
		)
		val sample = WeatherComparisonSample.fromSnapshot(WeatherProviderType.ITALIA_METEO, snapshot)
		val report = WeatherComparisonCapture(1_700_000_100L, listOf(sample)).toReport(ObservedSky.CLEAR, "3.8.28")

		assertEquals(18, sample.cloudCoverPercent)
		assertEquals(8, sample.lowCloudPercent)
		assertTrue(report.contains("ITALIA_METEO"))
		assertTrue(report.contains("Tester observed: CLEAR"))
		assertTrue(report.contains("MET Norway (CC BY 4.0)"))
		assertTrue(report.contains("Location: omitted for privacy"))
		assertFalse(report.contains("12.4964"))
		assertFalse(report.contains("latitude"))
		assertFalse(report.contains("longitude"))
	}

	@Test
	fun openMeteoJsonIntervalSurvivesMappingAndIdentifiesPastAccumulation() {
		val dto = Json { ignoreUnknownKeys = true }.decodeFromString<ForecastResponseDto>(
			"""{"latitude":41.9028,"longitude":12.4964,"current":{"time":1700000000,"interval":900,"weather_code":1,"is_day":1,"temperature_2m":23.5,"precipitation":1.5,"wind_speed_10m":5.0,"cloud_cover":18}}"""
		)
		val snapshot = dto.toSnapshot()
		val sample = WeatherComparisonSample.fromSnapshot(WeatherProviderType.ITALIA_METEO, snapshot)
		val report = WeatherComparisonCapture(1_700_000_100L, listOf(sample)).toReport(ObservedSky.RAIN, "test")

		assertEquals(PrecipitationWindow(900, PrecipitationWindowDirection.PRECEDING), snapshot.precipitationWindow)
		assertTrue(report.contains("Precipitation window: preceding 15 min"))
		assertTrue(report.contains(Instant.ofEpochSecond(1_700_000_000L - 900L).toString()))
		assertTrue(report.contains(Instant.ofEpochSecond(1_700_000_000L).toString()))
	}

	@Test
	fun metNorwayFutureHourIsNotPresentedAsCurrentPrecipitation() {
		val valid = 1_700_000_000L
		val sample = WeatherComparisonSample(
			provider = WeatherProviderType.MET_NORWAY,
			status = WeatherSampleStatus.SUCCESS,
			validAtEpochSeconds = valid,
			precipitationMillimeters = 3.0,
			precipitationWindow = PrecipitationWindow(3600, PrecipitationWindowDirection.FOLLOWING)
		)
		val report = WeatherComparisonCapture(valid, listOf(sample)).toReport(ObservedSky.RAIN, "test")

		assertTrue(report.contains("Precipitation window: following 60 min"))
		assertTrue(report.contains(Instant.ofEpochSecond(valid + 3600L).toString()))
	}

	@Test
	fun failedProviderDoesNotInventForecastValues() {
		val report = WeatherComparisonCapture(
			1_700_000_100L,
			listOf(WeatherComparisonSample(WeatherProviderType.DWD_ICON_EU, WeatherSampleStatus.TIMED_OUT))
		).toReport(ObservedSky.UNSURE, "test")

		assertTrue(report.contains("DWD_ICON_EU: TIMED_OUT"))
		assertFalse(report.contains("Cloud cover total"))
		assertFalse(report.contains("Temperature (C)"))
	}
}
