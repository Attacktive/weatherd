package xyz.attacktive.weatherd.domain.render

import java.time.Instant
import java.time.MonthDay
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.HolidayCelebration

class BirthdayCelebrationTest {
	@Test
	fun `disabled and missing birthdays never celebrate while every day needs the master toggle`() {
		val now = Instant.parse("2026-11-07T12:00:00Z")
		val date = MonthDay.of(11, 7)
		assertFalse(birthdayCelebrationAt(now, false, date, false))
		assertFalse(birthdayCelebrationAt(now, false, date, true))
		assertFalse(birthdayCelebrationAt(now, true, null, false))
		assertTrue(birthdayCelebrationAt(now, true, null, true))
	}

	@Test
	fun `birthday begins at local midnight and ends at the following local midnight`() {
		for (zoneName in listOf("Asia/Seoul", "Europe/Rome", "America/New_York", "Pacific/Kiritimati", "Etc/GMT+12")) {
			val zone = ZoneId.of(zoneName)
			val start = java.time.LocalDate.of(2026, 11, 7).atStartOfDay(zone).toInstant()
			val end = java.time.LocalDate.of(2026, 11, 8).atStartOfDay(zone).toInstant()
			val date = MonthDay.of(11, 7)
			assertFalse(zoneName, birthdayCelebrationAt(start.minusNanos(1), true, date, false, zone))
			assertTrue(zoneName, birthdayCelebrationAt(start, true, date, false, zone))
			assertTrue(zoneName, birthdayCelebrationAt(end.minusNanos(1), true, date, false, zone))
			assertFalse(zoneName, birthdayCelebrationAt(end, true, date, false, zone))
			assertEquals(1L, birthdayCalendarRefreshDelayMillis(end.toEpochMilli() - 1, 60_000L, zone))
		}
	}

	@Test
	fun `February 29 uses February 28 only in non leap years`() {
		val zone = ZoneId.of("UTC")
		val date = MonthDay.of(2, 29)
		for ((instant, expected) in listOf(
			"2026-02-28T12:00:00Z" to true,
			"2026-03-01T12:00:00Z" to false,
			"2028-02-28T12:00:00Z" to false,
			"2028-02-29T12:00:00Z" to true,
			"2028-03-01T12:00:00Z" to false
		)) {
			assertEquals(expected, birthdayCelebrationAt(Instant.parse(instant), true, date, false, zone))
		}
	}

	@Test
	fun `midnight refresh respects short and long daylight saving days`() {
		val zone = ZoneId.of("America/New_York")
		assertEquals(23 * 3_600_000L, birthdayCalendarRefreshDelayMillis(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(), 90_000_000L, zone))
		assertEquals(25 * 3_600_000L, birthdayCalendarRefreshDelayMillis(Instant.parse("2026-11-01T04:00:00Z").toEpochMilli(), 90_000_000L, zone))
		assertEquals(60_000L, birthdayCalendarRefreshDelayMillis(Instant.parse("2026-11-07T12:00:00Z").toEpochMilli(), 60_000L, zone))
	}

	@Test
	fun `birthday is independent of seasonal opt out but debug force retains precedence`() {
		val christmas = Instant.parse("2026-12-25T22:00:00Z")
		for (seasonal in listOf(false, true)) {
			assertEquals(HolidayCelebration.BIRTHDAY, resolvedHolidayCelebration(christmas, seasonal, false, HolidayCelebration.HALLOWEEN, false, birthday = true))
			assertEquals(HolidayCelebration.BIRTHDAY, resolvedHolidayCelebration(christmas, seasonal, true, HolidayCelebration.HALLOWEEN, false, birthday = true))
			assertEquals(HolidayCelebration.HALLOWEEN, resolvedHolidayCelebration(christmas, seasonal, true, HolidayCelebration.HALLOWEEN, true, birthday = true))
		}

		for (phase in DayPhase.entries) {
			val expected = if (phase == DayPhase.NIGHT) {
				HolidayCelebration.BIRTHDAY
			} else {
				HolidayCelebration.NONE
			}
			assertEquals(expected, visibleHolidayCelebration(phase, HolidayCelebration.BIRTHDAY))
		}
	}

	@Test
	fun `birthday frames are brief deterministic and fade completely at zero brightness`() {
		val params = debugSceneParams(SCENE_PRESETS.first(), DayPhase.NIGHT, holidayCelebration = HolidayCelebration.BIRTHDAY)
		val first = SeasonalCelebrationFrame()
		val second = SeasonalCelebrationFrame()
		for (slot in 100L..110L) {
			val start = SeasonalCelebrationFrame.passStartMillis(slot, SeasonalCelebrationFrame.BIRTHDAY_SLOT_MILLIS, SeasonalCelebrationFrame.BIRTHDAY_DURATION_MILLIS, SeasonalCelebrationFrame.BIRTHDAY_SALT)
			assertFalse(first.sample(1080f, 2340f, params, start - 1))
			assertTrue(first.sample(1080f, 2340f, params, start + 1000))
			assertTrue(second.sample(1080f, 2340f, params, start + 1000))
			assertEquals(first.birthdayGreetingY, second.birthdayGreetingY, 0f)
			assertEquals(first.birthdayGreetingOpacity, second.birthdayGreetingOpacity, 0f)
			assertEquals(1f, first.birthdayGreetingOpacity, 0f)
			assertFalse(first.sample(1080f, 2340f, params, start + SeasonalCelebrationFrame.BIRTHDAY_DURATION_MILLIS))
			assertEquals(0f, first.birthdayGreetingOpacity, 0f)
			assertFalse(first.sample(1080f, 2340f, params.copy(dayPhase = DayPhase.DAY), start + 1000))
			assertTrue(first.sample(1080f, 2340f, params.copy(nightBrightnessScale = 0f), start + 1000))
			assertEquals(0f, first.birthdayGreetingOpacity, 0f)
		}
	}

	@Test
	fun `birthday banner and bursts stay in the sky in both orientations with large moon and labels`() {
		val params = debugSceneParams(SCENE_PRESETS.first(), DayPhase.NIGHT, holidayCelebration = HolidayCelebration.BIRTHDAY, moonSizeScale = 2f).copy(
			overlayLabels = OverlayLabels("Clear", "Rome")
		)
		val frame = SeasonalCelebrationFrame()
		val start = SeasonalCelebrationFrame.passStartMillis(200L, SeasonalCelebrationFrame.BIRTHDAY_SLOT_MILLIS, SeasonalCelebrationFrame.BIRTHDAY_DURATION_MILLIS, SeasonalCelebrationFrame.BIRTHDAY_SALT)
		for ((width, height) in listOf(1080f to 2340f, 2340f to 1080f)) {
			for (position in listOf(0f, 0.09f, 0.35f, 0.75f, 1f)) {
				for (elapsed in 0L until SeasonalCelebrationFrame.BIRTHDAY_DURATION_MILLIS step 100L) {
					assertTrue(frame.sample(width, height, params.copy(labelVerticalPosition = position), start + elapsed))
					val top = frame.birthdayGreetingY - frame.birthdayGreetingHeight * 0.5f
					val bottom = frame.birthdayGreetingY + frame.birthdayGreetingHeight * 0.5f
					assertTrue(top >= height * 0.10f)
					assertTrue(bottom <= height * 0.56f)
					for (burst in frame.activeBursts()) {
						assertTrue(burst.apexY > bottom)
					}
				}
			}
		}
	}
}
