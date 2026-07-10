package xyz.attacktive.weatherd

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.weather.dayPhaseFor
import xyz.attacktive.weatherd.domain.weather.dayPhaseProgressFor

class DayPhaseMappingTest {
	private val sunrise = 1_000_000L
	private val sunset = 1_050_000L

	@Test
	fun `midday between the twilight windows is day`() {
		assertEquals(DayPhase.DAY, dayPhaseFor(1_025_000L, sunrise, sunset, isDay = true))
	}

	@Test
	fun `the twilight window around sunrise is dawn`() {
		assertEquals(DayPhase.DAWN, dayPhaseFor(sunrise, sunrise, sunset, isDay = true))
		assertEquals(DayPhase.DAWN, dayPhaseFor(sunrise - 600L, sunrise, sunset, isDay = false))
	}

	@Test
	fun `the twilight window around sunset is dusk`() {
		assertEquals(DayPhase.DUSK, dayPhaseFor(sunset, sunrise, sunset, isDay = true))
	}

	@Test
	fun `well after sunset is night`() {
		assertEquals(DayPhase.NIGHT, dayPhaseFor(sunset + 20_000L, sunrise, sunset, isDay = false))
	}

	@Test
	fun `falls back to is_day when sun times are missing`() {
		assertEquals(DayPhase.DAY, dayPhaseFor(1L, null, null, isDay = true))
		assertEquals(DayPhase.NIGHT, dayPhaseFor(1L, null, null, isDay = false))
	}

	@Test
	fun `progress runs from the start of dawn to its end`() {
		assertEquals(0f, dayPhaseProgressFor(sunrise - 2_700L, sunrise, sunset, DayPhase.DAWN), 0.0001f)
		assertEquals(0.5f, dayPhaseProgressFor(sunrise, sunrise, sunset, DayPhase.DAWN), 0.0001f)
		assertEquals(1f, dayPhaseProgressFor(sunrise + 2_700L, sunrise, sunset, DayPhase.DAWN), 0.0001f)
	}

	@Test
	fun `progress at midday sits midway through the day window`() {
		assertEquals(0.5f, dayPhaseProgressFor(1_025_000L, sunrise, sunset, DayPhase.DAY), 0.0001f)
	}

	@Test
	fun `progress steps hold stable between nearby moments`() {
		val early = dayPhaseProgressFor(sunrise - 2_699L, sunrise, sunset, DayPhase.DAWN)
		val stillEarly = dayPhaseProgressFor(sunrise - 2_600L, sunrise, sunset, DayPhase.DAWN)

		assertEquals(early, stillEarly, 0.0001f)
	}

	@Test
	fun `night and missing sun times pin progress to the midpoint`() {
		assertEquals(0.5f, dayPhaseProgressFor(sunset + 20_000L, sunrise, sunset, DayPhase.NIGHT), 0.0001f)
		assertEquals(0.5f, dayPhaseProgressFor(1L, null, null, DayPhase.DAY), 0.0001f)
	}
}
