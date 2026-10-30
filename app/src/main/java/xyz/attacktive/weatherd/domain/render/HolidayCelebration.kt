package xyz.attacktive.weatherd.domain.render

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.HolidayCelebration

private val FIRST_TIME_ZONE = ZoneOffset.ofHours(14)
private val LAST_TIME_ZONE = ZoneOffset.ofHours(-12)
private const val SECONDS_PER_DAY = 86_400L

/** Absolute worldwide two-date windows, independent of the device's time zone and selected weather location. */
internal fun holidayCelebrationAt(now: Instant): HolidayCelebration {
	val date = LocalDate.ofEpochDay(Math.floorDiv(now.epochSecond, SECONDS_PER_DAY))
	val year = date.year

	return when (date.monthValue) {
		10, 11 -> if (withinHolidayWindow(now, LocalDate.of(year, 10, 30))) {
			HolidayCelebration.HALLOWEEN
		} else {
			HolidayCelebration.NONE
		}

		12 -> when {
			withinHolidayWindow(now, LocalDate.of(year, 12, 24)) -> HolidayCelebration.CHRISTMAS
			withinHolidayWindow(now, LocalDate.of(year, 12, 31)) -> HolidayCelebration.NEW_YEAR
			else -> HolidayCelebration.NONE
		}
		1 -> if (withinHolidayWindow(now, LocalDate.of(year - 1, 12, 31))) {
			HolidayCelebration.NEW_YEAR
		} else {
			HolidayCelebration.NONE
		}

		else -> HolidayCelebration.NONE
	}
}

private fun withinHolidayWindow(now: Instant, firstDate: LocalDate): Boolean {
	val start = holidayWindowStartEpochSeconds(firstDate)
	val end = holidayWindowEndEpochSeconds(firstDate)

	return now.epochSecond >= start && now.epochSecond < end
}

private fun holidayWindowStartEpochSeconds(firstDate: LocalDate) = firstDate.toEpochDay() * SECONDS_PER_DAY - FIRST_TIME_ZONE.totalSeconds

private fun holidayWindowEndEpochSeconds(firstDate: LocalDate) = (firstDate.toEpochDay() + 2L) * SECONDS_PER_DAY - LAST_TIME_ZONE.totalSeconds

/** Shortens the existing minute-scale settled refresh only when an absolute eligibility boundary is closer. */
internal fun holidayCalendarRefreshDelayMillis(nowEpochMillis: Long, maximumDelayMillis: Long): Long {
	val year = LocalDate.ofEpochDay(Math.floorDiv(nowEpochMillis, SECONDS_PER_DAY * 1000L)).year
	var delay = holidayWindowRefreshDelayMillis(nowEpochMillis, maximumDelayMillis, LocalDate.of(year, 10, 30))
	delay = holidayWindowRefreshDelayMillis(nowEpochMillis, delay, LocalDate.of(year, 12, 24))
	delay = holidayWindowRefreshDelayMillis(nowEpochMillis, delay, LocalDate.of(year, 12, 31))

	return holidayWindowRefreshDelayMillis(nowEpochMillis, delay, LocalDate.of(year - 1, 12, 31))
}

private fun holidayWindowRefreshDelayMillis(nowEpochMillis: Long, maximumDelayMillis: Long, firstDate: LocalDate): Long {
	val untilStart = holidayWindowStartEpochSeconds(firstDate) * 1000L - nowEpochMillis
	val untilEnd = holidayWindowEndEpochSeconds(firstDate) * 1000L - nowEpochMillis
	var delay = maximumDelayMillis
	if (untilStart > 0L) {
		delay = minOf(delay, untilStart)
	}

	if (untilEnd > 0L) {
		delay = minOf(delay, untilEnd)
	}

	return delay
}

/** Resolves calendar eligibility and debug precedence; scene builders and the renderer independently enforce NIGHT. */
internal fun resolvedHolidayCelebration(
	now: Instant,
	enabled: Boolean,
	force: Boolean,
	forced: HolidayCelebration,
	debugBuild: Boolean
) = when {
	debugBuild && force -> forced
	!enabled -> HolidayCelebration.NONE
	else -> holidayCelebrationAt(now)
}

/** A calendar or debug selection never changes the rendered scene's effective lighting phase. */
internal fun visibleHolidayCelebration(dayPhase: DayPhase, holiday: HolidayCelebration) = if (dayPhase == DayPhase.NIGHT) {
	holiday
} else {
	HolidayCelebration.NONE
}
