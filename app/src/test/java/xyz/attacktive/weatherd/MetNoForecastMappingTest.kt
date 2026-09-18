package xyz.attacktive.weatherd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.data.api.dto.MetNoDataDto
import xyz.attacktive.weatherd.data.api.dto.MetNoForecastResponseDto
import xyz.attacktive.weatherd.data.api.dto.MetNoInstantDetailsDto
import xyz.attacktive.weatherd.data.api.dto.MetNoInstantDto
import xyz.attacktive.weatherd.data.api.dto.MetNoPropertiesDto
import xyz.attacktive.weatherd.data.api.dto.MetNoTimeSeriesDto
import xyz.attacktive.weatherd.data.api.dto.supportedMetNoSymbolCodes
import xyz.attacktive.weatherd.data.api.dto.toMetNoCondition
import xyz.attacktive.weatherd.data.api.dto.toSnapshot
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.WeatherLabel
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_HEAVY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

class MetNoForecastMappingTest {
	@Test
	fun `current MET legend snapshot matches the supported symbol table`() {
		assertEquals(MET_SYMBOL_CODES.toSet(), supportedMetNoSymbolCodes)
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
	fun `missing precipitation amount defaults to zero without discarding the forecast`() {
		val snapshot = metNoForecastResponse("rain", precipitationAmount = null).toSnapshot(metNoSunResponse())

		assertEquals(0.0, snapshot.observation.precipitationMillimeters, 0.0001)
		assertEquals(WeatherLabel.RAIN, snapshot.observation.condition.label)
	}

	@Test
	fun `empty timeseries fails instead of inventing a snapshot`() {
		var failed = false

		try {
			MetNoForecastResponseDto().toSnapshot(metNoSunResponse())
		} catch (_: IllegalStateException) {
			failed = true
		}

		assertTrue(failed)
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
			response.toSnapshot(metNoSunResponse())
		} catch (_: IllegalStateException) {
			failed = true
		}

		assertTrue(failed)
	}

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
