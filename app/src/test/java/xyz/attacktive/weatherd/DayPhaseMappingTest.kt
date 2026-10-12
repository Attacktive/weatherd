package xyz.attacktive.weatherd

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.weather.dayPhaseFor
import xyz.attacktive.weatherd.domain.weather.dayPhaseProgressFor

class DayPhaseMappingTest {
	private val sunrise = 1_000_000L
	private val sunset = 1_050_000L
	private val secondsPerDay = 86_400L

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
	fun `cached sun times repeat on the next day while offline`() {
		val nextDaySunrise = sunrise + secondsPerDay
		val nextDayNoon = (sunrise + sunset) / 2L + secondsPerDay

		assertEquals(DayPhase.DAWN, dayPhaseFor(nextDaySunrise, sunrise, sunset, isDay = false))
		assertEquals(DayPhase.DAY, dayPhaseFor(nextDayNoon, sunrise, sunset, isDay = false))
	}

	@Test
	fun `falls back to is_day when sun times are missing`() {
		assertEquals(DayPhase.DAY, dayPhaseFor(1L, null, null, isDay = true))
		assertEquals(DayPhase.NIGHT, dayPhaseFor(1L, null, null, isDay = false))
	}

	@Test
	fun `fresh is_day remains authoritative when solar times are missing`() {
		assertEquals(
			DayPhase.DAY,
			dayPhaseFor(
				nowEpochSeconds = EVENING_EPOCH_SECONDS,
				sunriseEpochSeconds = null,
				sunsetEpochSeconds = null,
				isDay = true,
				observedAtEpochSeconds = EVENING_EPOCH_SECONDS - 45L * 60L,
				fallbackLongitude = 0.0
			)
		)
	}

	@Test
	fun `stale missing solar times follow the selected location clock across the night boundary`() {
		assertEquals(
			DayPhase.DUSK,
			dayPhaseFor(
				nowEpochSeconds = NIGHT_BOUNDARY_EPOCH_SECONDS - 1L,
				sunriseEpochSeconds = null,
				sunsetEpochSeconds = null,
				isDay = true,
				observedAtEpochSeconds = NOON_EPOCH_SECONDS,
				fallbackLongitude = 0.0
			)
		)
		assertEquals(
			DayPhase.NIGHT,
			dayPhaseFor(
				nowEpochSeconds = NIGHT_BOUNDARY_EPOCH_SECONDS,
				sunriseEpochSeconds = null,
				sunsetEpochSeconds = null,
				isDay = true,
				observedAtEpochSeconds = NOON_EPOCH_SECONDS,
				fallbackLongitude = 0.0
			)
		)
	}

	@Test
	fun `stale missing solar times shift with the selected location longitude`() {
		assertEquals(
			DayPhase.NIGHT,
			dayPhaseFor(
				nowEpochSeconds = EVENING_EPOCH_SECONDS,
				sunriseEpochSeconds = null,
				sunsetEpochSeconds = null,
				isDay = true,
				observedAtEpochSeconds = NOON_EPOCH_SECONDS,
				fallbackLongitude = 0.0
			)
		)
		assertEquals(
			DayPhase.DAY,
			dayPhaseFor(
				nowEpochSeconds = EVENING_EPOCH_SECONDS,
				sunriseEpochSeconds = null,
				sunsetEpochSeconds = null,
				isDay = false,
				observedAtEpochSeconds = NOON_EPOCH_SECONDS,
				fallbackLongitude = -120.0
			)
		)
	}

	@Test
	fun `provider-reported no-event lighting remains authoritative after the freshness window`() {
		assertEquals(
			DayPhase.NIGHT,
			dayPhaseFor(
				nowEpochSeconds = EVENING_EPOCH_SECONDS,
				sunriseEpochSeconds = null,
				sunsetEpochSeconds = null,
				isDay = false,
				observedAtEpochSeconds = NOON_EPOCH_SECONDS,
				fallbackLongitude = -120.0,
				isDayAuthoritativeWithoutCompleteSolarTimes = true
			)
		)
	}

	@Test
	fun `stale partial solar times use the same clock fallback`() {
		assertEquals(
			DayPhase.NIGHT,
			dayPhaseFor(
				nowEpochSeconds = EVENING_EPOCH_SECONDS,
				sunriseEpochSeconds = NOON_EPOCH_SECONDS - 6L * 60L * 60L,
				sunsetEpochSeconds = null,
				isDay = true,
				observedAtEpochSeconds = NOON_EPOCH_SECONDS,
				fallbackLongitude = 0.0
			)
		)
		assertEquals(
			DayPhase.NIGHT,
			dayPhaseFor(
				nowEpochSeconds = EVENING_EPOCH_SECONDS,
				sunriseEpochSeconds = null,
				sunsetEpochSeconds = EVENING_EPOCH_SECONDS + 60L * 60L,
				isDay = true,
				observedAtEpochSeconds = NOON_EPOCH_SECONDS,
				fallbackLongitude = 0.0
			)
		)
	}

	@Test
	fun `valid solar times remain authoritative for a stale snapshot`() {
		assertEquals(
			DayPhase.DAY,
			dayPhaseFor(
				nowEpochSeconds = EVENING_EPOCH_SECONDS,
				sunriseEpochSeconds = NOON_EPOCH_SECONDS - 6L * 60L * 60L,
				sunsetEpochSeconds = EVENING_EPOCH_SECONDS + 60L * 60L,
				isDay = false,
				observedAtEpochSeconds = NOON_EPOCH_SECONDS,
				fallbackLongitude = 0.0
			)
		)
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
	fun `cached sun times keep progress aligned on the next day`() {
		val nextDaySunrise = sunrise + secondsPerDay
		val nextDayNoon = (sunrise + sunset) / 2L + secondsPerDay

		assertEquals(0.5f, dayPhaseProgressFor(nextDaySunrise, sunrise, sunset, DayPhase.DAWN), 0.0001f)
		assertEquals(0.5f, dayPhaseProgressFor(nextDayNoon, sunrise, sunset, DayPhase.DAY), 0.0001f)
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

	private companion object {
		const val NOON_EPOCH_SECONDS = 1_791_806_400L // 2026-10-12T12:00:00Z
		const val NIGHT_BOUNDARY_EPOCH_SECONDS = 1_791_831_600L // 2026-10-12T19:00:00Z
		const val EVENING_EPOCH_SECONDS = 1_791_835_200L // 2026-10-12T20:00:00Z
	}
}
