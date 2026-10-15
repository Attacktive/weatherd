package xyz.attacktive.weatherd.domain.weather

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/** [isDay] is authoritative only when polar conditions leave both solar events absent. */
internal data class LocalSolarTimes(val sunriseEpochSeconds: Long?, val sunsetEpochSeconds: Long?, val isDay: Boolean)

/** Caches one location's solar date so clock updates do not allocate dates or repeat trigonometry. */
internal class LocalSolarTimesCache {
	private var cachedSolarDay = Long.MIN_VALUE
	private var cachedLatitude = Double.NaN
	private var cachedLongitude = Double.NaN
	private var cachedTimes: LocalSolarTimes? = null

	@Synchronized
	fun forLocation(nowEpochSeconds: Long, latitude: Double, longitude: Double): LocalSolarTimes? {
		if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
			return null
		}

		val longitudeOffsetSeconds = (longitude / 360.0 * SECONDS_PER_DAY).roundToLong()
		val solarDay = Math.floorDiv(nowEpochSeconds + longitudeOffsetSeconds, SECONDS_PER_DAY)
		if (solarDay != cachedSolarDay || latitude != cachedLatitude || longitude != cachedLongitude) {
			cachedTimes = calculateSolarTimes(solarDay, latitude, longitude)
			cachedSolarDay = solarDay
			cachedLatitude = latitude
			cachedLongitude = longitude
		}

		return cachedTimes
	}
}

/** NOAA's solar-noon approximation: https://gml.noaa.gov/grad/solcalc/solareqns.PDF */
private fun calculateSolarTimes(solarDay: Long, latitude: Double, longitude: Double): LocalSolarTimes {
	val date = LocalDate.ofEpochDay(solarDay)
	val fractionalYear = 2.0 * PI / date.lengthOfYear() * (date.dayOfYear - 1)
	val equationOfTimeMinutes = 229.18 * (
		0.000075 +
		0.001868 * cos(fractionalYear) -
		0.032077 * sin(fractionalYear) -
		0.014615 * cos(2.0 * fractionalYear) -
		0.040849 * sin(2.0 * fractionalYear)
	)

	val declination = 0.006918 -
		0.399912 * cos(fractionalYear) +
		0.070257 * sin(fractionalYear) -
		0.006758 * cos(2.0 * fractionalYear)+
		0.000907 * sin(2.0 * fractionalYear) -
		0.002697 * cos(3.0 * fractionalYear) +
		0.00148 * sin(3.0 * fractionalYear)

	val latitudeRadians = latitude * PI / 180.0
	val cosineHourAngle = (cos(SUNRISE_ZENITH_RADIANS) - sin(latitudeRadians) * sin(declination)) / (cos(latitudeRadians) * cos(declination))

	if (cosineHourAngle >= 1.0) {
		return LocalSolarTimes(null, null, isDay = false)
	}

	if (cosineHourAngle <= -1.0) {
		return LocalSolarTimes(null, null, isDay = true)
	}

	val hourAngleDegrees = acos(cosineHourAngle) * 180.0 / PI
	val solarNoonMinutes = 720.0 - 4.0 * longitude - equationOfTimeMinutes
	val midnightEpochSeconds = solarDay * SECONDS_PER_DAY

	return LocalSolarTimes(
		sunriseEpochSeconds = midnightEpochSeconds + ((solarNoonMinutes - 4.0 * hourAngleDegrees) * 60.0).roundToLong(),
		sunsetEpochSeconds = midnightEpochSeconds + ((solarNoonMinutes + 4.0 * hourAngleDegrees) * 60.0).roundToLong(),
		isDay = false
	)
}

private const val SECONDS_PER_DAY = 86_400L
private const val SUNRISE_ZENITH_RADIANS = 90.833 * PI / 180.0
