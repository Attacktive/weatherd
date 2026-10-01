package xyz.attacktive.weatherd.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherProviderDefinitionTest {
	@Test
	fun `only globe-covering providers can be configured as fallback`() {
		assertEquals(
			listOf(
				WeatherProviderType.OPEN_METEO,
				WeatherProviderType.MET_NORWAY,
				WeatherProviderType.DWD_ICON_GLOBAL
			),
			globalWeatherProviders
		)
	}

	@Test
	fun `regional ICON definitions use the Open-Meteo model identifiers`() {
		assertEquals("dwd_icon_global", WeatherProviderType.DWD_ICON_GLOBAL.definition.model)
		assertEquals("dwd_icon_eu", WeatherProviderType.DWD_ICON_EU.definition.model)
		assertEquals("dwd_icon_d2", WeatherProviderType.DWD_ICON_D2.definition.model)
		assertEquals("italia_meteo_arpae_icon_2i", WeatherProviderType.ITALIA_METEO.definition.model)
		assertEquals("meteoswiss_icon_ch1", WeatherProviderType.METEOSWISS_ICON_CH1.definition.model)
		assertEquals("meteoswiss_icon_ch2", WeatherProviderType.METEOSWISS_ICON_CH2.definition.model)
	}

	@Test
	fun `ItaliaMeteo covers Bologna but not Moscow or Seoul`() {
		assertTrue(WeatherProviderType.ITALIA_METEO.definition.supports(44.5, 11.34))
		assertFalse(WeatherProviderType.ITALIA_METEO.definition.supports(55.75, 37.61))
		assertFalse(WeatherProviderType.ITALIA_METEO.definition.supports(37.57, 126.98))
	}

	@Test
	fun `DWD regional domains match their published grids`() {
		assertTrue(WeatherProviderType.DWD_ICON_EU.definition.supports(41.90, 12.50))
		assertTrue(WeatherProviderType.DWD_ICON_D2.definition.supports(52.52, 13.40))
		assertFalse(WeatherProviderType.DWD_ICON_D2.definition.supports(43.26, -2.93))
		assertFalse(WeatherProviderType.DWD_ICON_D2.definition.supports(41.90, 12.50))
	}

	@Test
	fun `MeteoSwiss rotated grid covers Zurich but not Rome`() {
		assertTrue(WeatherProviderType.METEOSWISS_ICON_CH1.definition.supports(47.3769, 8.5417))
		assertTrue(WeatherProviderType.METEOSWISS_ICON_CH2.definition.supports(47.3769, 8.5417))
		assertFalse(WeatherProviderType.METEOSWISS_ICON_CH1.definition.supports(41.9028, 12.4964))
	}
}
