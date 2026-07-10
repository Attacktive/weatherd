package xyz.attacktive.weatherd

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.weather.moonPhaseFor

class MoonPhaseTest {
	@Test
	fun `reference new moon is phase zero`() {
		// 2000-01-06 18:14 UTC, the reference new moon itself.
		assertEquals(0f, moonPhaseFor(947_182_440L), 0.001f)
	}

	@Test
	fun `full moon lands half a synodic month later`() {
		// 2000-01-21 04:40 UTC, the full moon following the reference; day quantisation allows a couple of percent.
		assertEquals(0.5f, moonPhaseFor(948_429_600L), 0.04f)
	}

	@Test
	fun `phase wraps after a whole synodic month`() {
		// 2000-02-05 13:03 UTC, the next new moon (29.53 days after the reference).
		assertEquals(0f, moonPhaseFor(949_755_780L), 0.03f)
	}

	@Test
	fun `phase is stable within a day`() {
		val dayStart = 1_775_001_600L / 86_400L * 86_400L

		assertEquals(moonPhaseFor(dayStart), moonPhaseFor(dayStart + 86_399L), 0.0001f)
	}
}
