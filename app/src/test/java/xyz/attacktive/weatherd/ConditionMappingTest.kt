package xyz.attacktive.weatherd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.WeatherLabel
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_HEAVY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STEADY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM
import xyz.attacktive.weatherd.domain.weather.conditionForWmoCode
import xyz.attacktive.weatherd.domain.weather.precipitationIntensity
import xyz.attacktive.weatherd.domain.weather.weatherLabelFor

class ConditionMappingTest {
	@Test
	fun `clear-sky codes carry no features`() {
		for (code in 0..3) {
			val condition = conditionForWmoCode(code)
			assertNull(condition.precipitationKind)
			assertFalse(condition.fog)
			assertFalse(condition.thunder)
		}
	}

	@Test
	fun `fog codes set fog without precipitation`() {
		for (code in listOf(45, 48)) {
			val condition = conditionForWmoCode(code)
			assertTrue(condition.fog)
			assertNull(condition.precipitationKind)
		}
	}

	@Test
	fun `rain codes map kind and severity bands`() {
		assertEquals(PrecipitationKind.RAIN, conditionForWmoCode(51).precipitationKind)
		assertEquals(SEVERITY_DRIZZLE, conditionForWmoCode(51).severity)
		assertEquals(PrecipitationKind.RAIN, conditionForWmoCode(61).precipitationKind)
		assertEquals(SEVERITY_STEADY, conditionForWmoCode(61).severity)
		assertEquals(SEVERITY_STEADY, conditionForWmoCode(80).severity)
		assertEquals(SEVERITY_HEAVY, conditionForWmoCode(65).severity)
		assertEquals(SEVERITY_HEAVY, conditionForWmoCode(82).severity)
	}

	@Test
	fun `snow and sleet codes map kind and severity bands`() {
		assertEquals(PrecipitationKind.SNOW, conditionForWmoCode(71).precipitationKind)
		assertEquals(SEVERITY_STEADY, conditionForWmoCode(71).severity)
		assertEquals(SEVERITY_HEAVY, conditionForWmoCode(75).severity)
		assertEquals(PrecipitationKind.SLEET, conditionForWmoCode(66).precipitationKind)
		assertEquals(PrecipitationKind.SLEET, conditionForWmoCode(56).precipitationKind)
	}

	@Test
	fun `thunderstorm codes rain with thunder`() {
		for (code in listOf(95, 96, 99)) {
			val condition = conditionForWmoCode(code)
			assertEquals(PrecipitationKind.RAIN, condition.precipitationKind)
			assertEquals(SEVERITY_STORM, condition.severity)
			assertTrue(condition.thunder)
		}
	}

	@Test
	fun `unknown codes carry no features`() {
		for (code in listOf(-1, 1234)) {
			val condition = conditionForWmoCode(code)
			assertNull(condition.precipitationKind)
			assertFalse(condition.fog)
			assertFalse(condition.thunder)
		}
	}

	@Test
	fun `weather labels cover the wmo table`() {
		assertEquals(R.string.weather_clear_sky, weatherLabelFor(conditionForWmoCode(0).label))
		assertEquals(R.string.weather_mainly_clear, weatherLabelFor(conditionForWmoCode(1).label))
		assertEquals(R.string.weather_partly_cloudy, weatherLabelFor(conditionForWmoCode(2).label))
		assertEquals(R.string.weather_overcast, weatherLabelFor(conditionForWmoCode(3).label))
		assertEquals(R.string.weather_fog, weatherLabelFor(conditionForWmoCode(45).label))
		assertEquals(R.string.weather_icy_fog, weatherLabelFor(conditionForWmoCode(48).label))
		assertEquals(R.string.weather_light_drizzle, weatherLabelFor(conditionForWmoCode(51).label))
		assertEquals(R.string.weather_drizzle, weatherLabelFor(conditionForWmoCode(53).label))
		assertEquals(R.string.weather_dense_drizzle, weatherLabelFor(conditionForWmoCode(55).label))
		assertEquals(R.string.weather_light_freezing_drizzle, weatherLabelFor(conditionForWmoCode(56).label))
		assertEquals(R.string.weather_freezing_drizzle, weatherLabelFor(conditionForWmoCode(57).label))
		assertEquals(R.string.weather_light_rain, weatherLabelFor(conditionForWmoCode(61).label))
		assertEquals(R.string.weather_rain, weatherLabelFor(conditionForWmoCode(63).label))
		assertEquals(R.string.weather_heavy_rain, weatherLabelFor(conditionForWmoCode(65).label))
		assertEquals(R.string.weather_light_freezing_rain, weatherLabelFor(conditionForWmoCode(66).label))
		assertEquals(R.string.weather_freezing_rain, weatherLabelFor(conditionForWmoCode(67).label))
		assertEquals(R.string.weather_light_snow, weatherLabelFor(conditionForWmoCode(71).label))
		assertEquals(R.string.weather_snow, weatherLabelFor(conditionForWmoCode(73).label))
		assertEquals(R.string.weather_heavy_snow, weatherLabelFor(conditionForWmoCode(75).label))
		assertEquals(R.string.weather_snow_grains, weatherLabelFor(conditionForWmoCode(77).label))
		assertEquals(R.string.weather_light_showers, weatherLabelFor(conditionForWmoCode(80).label))
		assertEquals(R.string.weather_showers, weatherLabelFor(conditionForWmoCode(81).label))
		assertEquals(R.string.weather_violent_showers, weatherLabelFor(conditionForWmoCode(82).label))
		assertEquals(R.string.weather_snow_showers, weatherLabelFor(conditionForWmoCode(85).label))
		assertEquals(R.string.weather_heavy_snow_showers, weatherLabelFor(conditionForWmoCode(86).label))
		assertEquals(R.string.weather_thunderstorm, weatherLabelFor(conditionForWmoCode(95).label))
		assertEquals(R.string.weather_thunderstorm_with_hail, weatherLabelFor(conditionForWmoCode(96).label))
		assertEquals(R.string.weather_thunderstorm_with_hail, weatherLabelFor(conditionForWmoCode(99).label))
	}

	@Test
	fun `every weather label has a string resource`() {
		WeatherLabel.entries.forEach { label ->
			assertNotNull(weatherLabelFor(label))
		}
	}

	@Test
	fun `unknown codes have no label`() {
		assertNull(weatherLabelFor(conditionForWmoCode(-1).label))
		assertNull(weatherLabelFor(conditionForWmoCode(1234).label))
	}

	@Test
	fun `precipitation intensity normalizes and clamps`() {
		assertEquals(0f, precipitationIntensity(0.0), 0.0001f)
		assertEquals(0.5f, precipitationIntensity(5.0), 0.0001f)
		assertEquals(1f, precipitationIntensity(10.0), 0.0001f)
		assertEquals(1f, precipitationIntensity(25.0), 0.0001f)
	}
}
