package xyz.attacktive.weatherd.domain.render

import android.content.res.Resources
import android.text.format.DateUtils
import xyz.attacktive.weatherd.R

internal fun weatherNoticeTitle(resources: Resources, notice: WeatherNotice) = when {
	notice.refreshedAtEpochSeconds == null -> resources.getString(R.string.weather_unavailable)
	notice.locationLabel != null -> resources.getString(R.string.stale_weather_location, notice.locationLabel)
	else -> resources.getString(R.string.stale_weather_title)
}

internal fun weatherNoticeUpdated(resources: Resources, notice: WeatherNotice, nowMillis: Long): String? {
	val refreshedAt = notice.refreshedAtEpochSeconds ?: return null
	val age = DateUtils.getRelativeTimeSpanString(refreshedAt * 1000L, nowMillis, DateUtils.MINUTE_IN_MILLIS)
	return resources.getString(R.string.stale_weather_updated, age)
}
