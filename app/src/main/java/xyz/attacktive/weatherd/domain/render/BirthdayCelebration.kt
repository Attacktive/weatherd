package xyz.attacktive.weatherd.domain.render

import java.time.Instant
import java.time.MonthDay
import java.time.ZoneId

/** Personal celebrations follow the device's civil date, not the selected weather location. */
internal fun birthdayCelebrationAt(now: Instant, enabled: Boolean, date: MonthDay?, everyDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): Boolean {
	if (!enabled) {
		return false
	}

	if (everyDay) {
		return true
	}

	val today = now.atZone(zone).toLocalDate()
	return date?.atYear(today.year) == today
}

internal fun birthdayCalendarRefreshDelayMillis(nowEpochMillis: Long, maximumDelayMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
	val now = Instant.ofEpochMilli(nowEpochMillis)
	val nextMidnight = now.atZone(zone)
		.toLocalDate()
		.plusDays(1)
		.atStartOfDay(zone)
		.toInstant()

	return minOf(maximumDelayMillis, nextMidnight.toEpochMilli() - nowEpochMillis)
}
