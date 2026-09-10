package xyz.attacktive.weatherd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.R
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
		assertEquals(R.string.weather_clear_sky, weatherLabelFor(0))
		assertEquals(R.string.weather_mainly_clear, weatherLabelFor(1))
		assertEquals(R.string.weather_partly_cloudy, weatherLabelFor(2))
		assertEquals(R.string.weather_overcast, weatherLabelFor(3))
		assertEquals(R.string.weather_fog, weatherLabelFor(45))
		assertEquals(R.string.weather_icy_fog, weatherLabelFor(48))
		assertEquals(R.string.weather_light_drizzle, weatherLabelFor(51))
		assertEquals(R.string.weather_drizzle, weatherLabelFor(53))
		assertEquals(R.string.weather_dense_drizzle, weatherLabelFor(55))
		assertEquals(R.string.weather_light_freezing_drizzle, weatherLabelFor(56))
		assertEquals(R.string.weather_freezing_drizzle, weatherLabelFor(57))
		assertEquals(R.string.weather_light_rain, weatherLabelFor(61))
		assertEquals(R.string.weather_rain, weatherLabelFor(63))
		assertEquals(R.string.weather_heavy_rain, weatherLabelFor(65))
		assertEquals(R.string.weather_light_freezing_rain, weatherLabelFor(66))
		assertEquals(R.string.weather_freezing_rain, weatherLabelFor(67))
		assertEquals(R.string.weather_light_snow, weatherLabelFor(71))
		assertEquals(R.string.weather_snow, weatherLabelFor(73))
		assertEquals(R.string.weather_heavy_snow, weatherLabelFor(75))
		assertEquals(R.string.weather_snow_grains, weatherLabelFor(77))
		assertEquals(R.string.weather_light_showers, weatherLabelFor(80))
		assertEquals(R.string.weather_showers, weatherLabelFor(81))
		assertEquals(R.string.weather_violent_showers, weatherLabelFor(82))
		assertEquals(R.string.weather_snow_showers, weatherLabelFor(85))
		assertEquals(R.string.weather_heavy_snow_showers, weatherLabelFor(86))
		assertEquals(R.string.weather_thunderstorm, weatherLabelFor(95))
		assertEquals(R.string.weather_thunderstorm_with_hail, weatherLabelFor(96))
		assertEquals(R.string.weather_thunderstorm_with_hail, weatherLabelFor(99))
	}

	@Test
	fun `unknown codes have no label`() {
		assertNull(weatherLabelFor(-1))
		assertNull(weatherLabelFor(1234))
	}

	@Test
	fun `precipitation intensity normalizes and clamps`() {
		assertEquals(0f, precipitationIntensity(0.0), 0.0001f)
		assertEquals(0.5f, precipitationIntensity(5.0), 0.0001f)
		assertEquals(1f, precipitationIntensity(10.0), 0.0001f)
		assertEquals(1f, precipitationIntensity(25.0), 0.0001f)
	}
}
