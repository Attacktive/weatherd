package xyz.attacktive.weatherd.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TemperatureUnitTest {
	@Test
	fun `celsius formats as a rounded bare degree`() {
		assertEquals("10°", TemperatureUnit.CELSIUS.format(10.0))
		assertEquals("22°", TemperatureUnit.CELSIUS.format(22.4))
		assertEquals("23°", TemperatureUnit.CELSIUS.format(22.5))
		assertEquals("-4°", TemperatureUnit.CELSIUS.format(-3.6))
	}

	@Test
	fun `fahrenheit converts before rounding`() {
		assertEquals("32°", TemperatureUnit.FAHRENHEIT.format(0.0))
		assertEquals("50°", TemperatureUnit.FAHRENHEIT.format(10.0))
		assertEquals("73°", TemperatureUnit.FAHRENHEIT.format(23.0))
		assertEquals("-40°", TemperatureUnit.FAHRENHEIT.format(-40.0))
	}

	@Test
	fun `an unrecognised or absent stored name falls back to celsius`() {
		assertEquals(TemperatureUnit.FAHRENHEIT, TemperatureUnit.fromName("FAHRENHEIT"))
		assertEquals(TemperatureUnit.CELSIUS, TemperatureUnit.fromName("KELVIN"))
		assertEquals(TemperatureUnit.CELSIUS, TemperatureUnit.fromName(null))
	}
}
