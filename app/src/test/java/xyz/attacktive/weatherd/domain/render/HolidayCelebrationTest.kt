package xyz.attacktive.weatherd.domain.render

import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.HolidayCelebration

class HolidayCelebrationTest {
	@Test
	fun `settled scenes wake at annual UTC boundaries instead of retaining an expired visitor`() {
		val boundaries = listOf(
			"2026-10-29T10:00:00Z",
			"2026-11-01T12:00:00Z",
			"2026-12-23T10:00:00Z",
			"2026-12-26T12:00:00Z",
			"2026-12-30T10:00:00Z",
			"2027-01-02T12:00:00Z"
		)

		for (instant in boundaries) {
			val boundary = Instant.parse(instant).toEpochMilli()
			assertEquals(20_000L, holidayCalendarRefreshDelayMillis(boundary - 20_000L, 60_000L))
			assertEquals(1L, holidayCalendarRefreshDelayMillis(boundary - 1L, 60_000L))
			assertEquals(60_000L, holidayCalendarRefreshDelayMillis(boundary, 60_000L))
		}

		assertEquals(60_000L, holidayCalendarRefreshDelayMillis(Instant.parse("2026-07-04T22:00:00Z").toEpochMilli(), 60_000L))
	}

	@Test
	fun `all annual windows have inclusive starts and exclusive ends`() {
		val windows = listOf(
			Triple(HolidayCelebration.HALLOWEEN, "2026-10-29T10:00:00Z", "2026-11-01T12:00:00Z"),
			Triple(HolidayCelebration.CHRISTMAS, "2026-12-23T10:00:00Z", "2026-12-26T12:00:00Z"),
			Triple(HolidayCelebration.NEW_YEAR, "2026-12-30T10:00:00Z", "2027-01-02T12:00:00Z"),
			Triple(HolidayCelebration.HALLOWEEN, "2028-10-29T10:00:00Z", "2028-11-01T12:00:00Z"),
			Triple(HolidayCelebration.CHRISTMAS, "2028-12-23T10:00:00Z", "2028-12-26T12:00:00Z"),
			Triple(HolidayCelebration.NEW_YEAR, "2027-12-30T10:00:00Z", "2028-01-02T12:00:00Z")
		)

		for ((holiday, first, end) in windows) {
			val start = Instant.parse(first)
			val stop = Instant.parse(end)
			assertEquals(holiday, holidayCelebrationAt(start.plusSeconds(37 * 3600)))
			assertEquals(HolidayCelebration.NONE, holidayCelebrationAt(start.minusNanos(1)))
			assertEquals(holiday, holidayCelebrationAt(start))
			assertEquals(holiday, holidayCelebrationAt(stop.minusNanos(1)))
			assertEquals(HolidayCelebration.NONE, holidayCelebrationAt(stop))
		}
	}

	@Test
	fun `every other day remains inactive across leap and ordinary years`() {
		for (year in listOf(2026, 2028)) {
			var instant = Instant.parse("$year-01-01T00:00:00Z")
			val end = Instant.parse("${year + 1}-01-01T00:00:00Z")
			while (instant < end) {
				val date = instant.atZone(ZoneId.of("UTC"))
				val expected = when (date.monthValue * 100 + date.dayOfMonth) {
					101, 102, 1231 -> HolidayCelebration.NEW_YEAR
					1030, 1031, 1101 -> HolidayCelebration.HALLOWEEN
					1224, 1225, 1226 -> HolidayCelebration.CHRISTMAS
					else -> HolidayCelebration.NONE
				}

				assertEquals(instant.toString(), expected, holidayCelebrationAt(instant))
				instant = instant.plusSeconds(86_400)
			}
		}
	}

	@Test
	fun `device time zone changes cannot move the global window`() {
		val original = TimeZone.getDefault()
		try {
			for (zone in listOf("Pacific/Kiritimati", "Etc/GMT+12", "America/New_York", "Asia/Seoul")) {
				TimeZone.setDefault(TimeZone.getTimeZone(zone))
				assertEquals(HolidayCelebration.HALLOWEEN, holidayCelebrationAt(Instant.parse("2026-10-29T10:00:00Z")))
				assertEquals(HolidayCelebration.NEW_YEAR, holidayCelebrationAt(Instant.parse("2027-01-02T11:59:59Z")))
				assertEquals(HolidayCelebration.NONE, holidayCelebrationAt(Instant.parse("2027-01-02T12:00:00Z")))
			}
		} finally {
			TimeZone.setDefault(original)
		}
	}

	@Test
	fun `debug force overrides calendar and opt out but never overrides night`() {
		val outsideWindow = Instant.parse("2026-07-04T22:00:00Z")
		for (holiday in HolidayCelebration.entries.filter { it != HolidayCelebration.NONE }) {
			val forced = resolvedHolidayCelebration(outsideWindow, false, true, holiday, true)
			assertEquals(HolidayCelebration.NONE, resolvedHolidayCelebration(outsideWindow, true, true, holiday, false))
			for (phase in DayPhase.entries) {
				val expected = if (phase == DayPhase.NIGHT) {
					holiday
				} else {
					HolidayCelebration.NONE
				}

				val params = debugSceneParams(SCENE_PRESETS.first(), phase, holidayCelebration = forced)
				assertEquals(expected, params.holidayCelebration)
			}
		}
	}

	@Test
	fun `release ignores persisted force and resumes actual calendar and preference`() {
		val christmas = Instant.parse("2026-12-24T22:00:00Z")
		assertEquals(HolidayCelebration.CHRISTMAS, resolvedHolidayCelebration(christmas, true, true, HolidayCelebration.HALLOWEEN, false))
		assertEquals(HolidayCelebration.NONE, resolvedHolidayCelebration(christmas, false, true, HolidayCelebration.HALLOWEEN, false))
		assertEquals(HolidayCelebration.CHRISTMAS, resolvedHolidayCelebration(christmas, true, false, HolidayCelebration.HALLOWEEN, true))
	}
}
