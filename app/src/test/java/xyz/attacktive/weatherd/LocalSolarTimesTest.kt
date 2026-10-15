package xyz.attacktive.weatherd

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.weather.LocalSolarTimesCache
import xyz.attacktive.weatherd.domain.weather.dayPhaseFor

class LocalSolarTimesTest {
	private val cache = LocalSolarTimesCache()

	@Test
	fun `Rome autumn solar events agree with independent MET Norway reference`() {
		// https://api.met.no/weatherapi/sunrise/3.0/sun?lat=41.9028&lon=12.4964&date=2026-10-15
		val times = checkNotNull(cache.forLocation(epoch("2026-10-15T04:03:14Z"), 41.9028, 12.4964))

		assertEquals(epoch("2026-10-15T05:22:00Z").toDouble(), checkNotNull(times.sunriseEpochSeconds).toDouble(), 300.0)
		assertEquals(epoch("2026-10-15T16:28:00Z").toDouble(), checkNotNull(times.sunsetEpochSeconds).toDouble(), 300.0)
	}

	@Test
	fun `moving within the same solar date cannot reuse the previous locations events`() {
		val now = epoch("2026-10-15T12:00:00Z")
		val rome = checkNotNull(cache.forLocation(now, 41.9028, 12.4964))
		val sanFrancisco = checkNotNull(cache.forLocation(now, 37.7749, -122.4194))

		assertEquals(DayPhase.DAY, dayPhaseFor(now, rome.sunriseEpochSeconds, rome.sunsetEpochSeconds, rome.isDay))
		assertEquals(DayPhase.NIGHT, dayPhaseFor(now, sanFrancisco.sunriseEpochSeconds, sanFrancisco.sunsetEpochSeconds, sanFrancisco.isDay))
	}

	@Test
	fun `sunrise stays on the eastern locations solar date when UTC is still yesterday`() {
		val beforeMidnight = checkNotNull(cache.forLocation(epoch("2026-01-01T23:30:00Z"), -36.8485, 174.7633))
		val afterMidnight = checkNotNull(cache.forLocation(epoch("2026-01-02T00:30:00Z"), -36.8485, 174.7633))

		assertEquals(beforeMidnight, afterMidnight)
		assertTrue(checkNotNull(beforeMidnight.sunriseEpochSeconds) in epoch("2026-01-01T16:00:00Z")..epoch("2026-01-01T18:00:00Z"))
		assertTrue(checkNotNull(beforeMidnight.sunsetEpochSeconds) in epoch("2026-01-02T06:00:00Z")..epoch("2026-01-02T09:00:00Z"))
	}

	@Test
	fun `solar date rollover recalculates seasonal events on either side of the date line`() {
		for (longitude in listOf(-179.9, 179.9)) {
			val winter = checkNotNull(cache.forLocation(epoch("2026-01-15T12:00:00Z"), 55.0, longitude))
			val summer = checkNotNull(cache.forLocation(epoch("2026-07-15T12:00:00Z"), 55.0, longitude))
			val winterDaylight = checkNotNull(winter.sunsetEpochSeconds) - checkNotNull(winter.sunriseEpochSeconds)
			val summerDaylight = checkNotNull(summer.sunsetEpochSeconds) - checkNotNull(summer.sunriseEpochSeconds)

			assertTrue(winterDaylight < 9L * 3600L)
			assertTrue(summerDaylight > 16L * 3600L)
		}
	}

	@Test
	fun `polar day and night do not fabricate solar events or invert between hemispheres`() {
		for (latitude in listOf(-90.0, -78.0, 78.0, 90.0)) {
			val june = checkNotNull(cache.forLocation(epoch("2026-06-21T12:00:00Z"), latitude, 0.0))
			val december = checkNotNull(cache.forLocation(epoch("2026-12-21T12:00:00Z"), latitude, 0.0))

			assertNull(june.sunriseEpochSeconds)
			assertNull(june.sunsetEpochSeconds)
			assertEquals(latitude > 0.0, june.isDay)
			assertNull(december.sunriseEpochSeconds)
			assertNull(december.sunsetEpochSeconds)
			assertEquals(latitude < 0.0, december.isDay)
		}
	}

	@Test
	fun `invalid coordinates cannot poison valid cached solar events`() {
		val now = epoch("2026-10-15T04:03:14Z")
		val valid = cache.forLocation(now, 41.9028, 12.4964)

		assertNotNull(valid)

		for ((latitude, longitude) in listOf(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY, 91.0 to 0.0, 0.0 to -181.0)) {
			assertNull(cache.forLocation(now, latitude, longitude))
		}

		assertEquals(valid, cache.forLocation(now, 41.9028, 12.4964))
	}

	@Test
	fun `crossing local solar midnight advances events even while UTC date is unchanged`() {
		val before = checkNotNull(cache.forLocation(epoch("2026-10-15T22:00:00Z"), 41.9028, 12.4964))
		val after = checkNotNull(cache.forLocation(epoch("2026-10-15T23:30:00Z"), 41.9028, 12.4964))
		val sunriseAdvance = checkNotNull(after.sunriseEpochSeconds) - checkNotNull(before.sunriseEpochSeconds)

		assertTrue(sunriseAdvance in 23L * 3600L..25L * 3600L)
	}

	private fun epoch(value: String) = Instant.parse(value).epochSecond
}
