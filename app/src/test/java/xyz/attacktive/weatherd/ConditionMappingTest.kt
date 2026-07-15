package xyz.attacktive.weatherd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_HEAVY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STEADY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM
import xyz.attacktive.weatherd.domain.weather.conditionFor
import xyz.attacktive.weatherd.domain.weather.precipitationIntensity
import xyz.attacktive.weatherd.domain.weather.weatherLabelFor

class ConditionMappingTest {
	@Test
	fun `clear-sky codes carry no features`() {
		for (code in 0..3) {
			val condition = conditionFor(code)
			assertNull(condition.precipitationKind)
			assertFalse(condition.fog)
			assertFalse(condition.thunder)
		}
	}

	@Test
	fun `fog codes set fog without precipitation`() {
		for (code in listOf(45, 48)) {
			val condition = conditionFor(code)
			assertTrue(condition.fog)
			assertNull(condition.precipitationKind)
		}
	}

	@Test
	fun `rain codes map kind and severity bands`() {
		assertEquals(PrecipitationKind.RAIN, conditionFor(51).precipitationKind)
		assertEquals(SEVERITY_DRIZZLE, conditionFor(51).severity)
		assertEquals(PrecipitationKind.RAIN, conditionFor(61).precipitationKind)
		assertEquals(SEVERITY_STEADY, conditionFor(61).severity)
		assertEquals(SEVERITY_STEADY, conditionFor(80).severity)
		assertEquals(SEVERITY_HEAVY, conditionFor(65).severity)
		assertEquals(SEVERITY_HEAVY, conditionFor(82).severity)
	}

	@Test
	fun `snow and sleet codes map kind and severity bands`() {
		assertEquals(PrecipitationKind.SNOW, conditionFor(71).precipitationKind)
		assertEquals(SEVERITY_STEADY, conditionFor(71).severity)
		assertEquals(SEVERITY_HEAVY, conditionFor(75).severity)
		assertEquals(PrecipitationKind.SLEET, conditionFor(66).precipitationKind)
		assertEquals(PrecipitationKind.SLEET, conditionFor(56).precipitationKind)
	}

	@Test
	fun `thunderstorm codes rain with thunder`() {
		for (code in listOf(95, 96, 99)) {
			val condition = conditionFor(code)
			assertEquals(PrecipitationKind.RAIN, condition.precipitationKind)
			assertEquals(SEVERITY_STORM, condition.severity)
			assertTrue(condition.thunder)
		}
	}

	@Test
	fun `unknown codes carry no features`() {
		for (code in listOf(-1, 1234)) {
			val condition = conditionFor(code)
			assertNull(condition.precipitationKind)
			assertFalse(condition.fog)
			assertFalse(condition.thunder)
		}
	}

	@Test
	fun `weather labels cover the wmo table`() {
		assertEquals("Clear sky", weatherLabelFor(0))
		assertEquals("Mainly clear", weatherLabelFor(1))
		assertEquals("Partly cloudy", weatherLabelFor(2))
		assertEquals("Overcast", weatherLabelFor(3))
		assertEquals("Fog", weatherLabelFor(45))
		assertEquals("Icy fog", weatherLabelFor(48))
		assertEquals("Light drizzle", weatherLabelFor(51))
		assertEquals("Drizzle", weatherLabelFor(53))
		assertEquals("Dense drizzle", weatherLabelFor(55))
		assertEquals("Light freezing drizzle", weatherLabelFor(56))
		assertEquals("Freezing drizzle", weatherLabelFor(57))
		assertEquals("Light rain", weatherLabelFor(61))
		assertEquals("Rain", weatherLabelFor(63))
		assertEquals("Heavy rain", weatherLabelFor(65))
		assertEquals("Light freezing rain", weatherLabelFor(66))
		assertEquals("Freezing rain", weatherLabelFor(67))
		assertEquals("Light snow", weatherLabelFor(71))
		assertEquals("Snow", weatherLabelFor(73))
		assertEquals("Heavy snow", weatherLabelFor(75))
		assertEquals("Snow grains", weatherLabelFor(77))
		assertEquals("Light showers", weatherLabelFor(80))
		assertEquals("Showers", weatherLabelFor(81))
		assertEquals("Violent showers", weatherLabelFor(82))
		assertEquals("Snow showers", weatherLabelFor(85))
		assertEquals("Heavy snow showers", weatherLabelFor(86))
		assertEquals("Thunderstorm", weatherLabelFor(95))
		assertEquals("Thunderstorm with hail", weatherLabelFor(96))
		assertEquals("Thunderstorm with hail", weatherLabelFor(99))
	}

	@Test
	fun `unknown codes have no label`() {
		assertNull(weatherLabelFor(-1))
		assertNull(weatherLabelFor(1234))
	}

	@Test
	fun `precipitation intensity normalises and clamps`() {
		assertEquals(0f, precipitationIntensity(0.0), 0.0001f)
		assertEquals(0.5f, precipitationIntensity(5.0), 0.0001f)
		assertEquals(1f, precipitationIntensity(10.0), 0.0001f)
		assertEquals(1f, precipitationIntensity(25.0), 0.0001f)
	}
}
