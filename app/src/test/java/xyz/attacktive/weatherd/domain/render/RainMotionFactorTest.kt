package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_HEAVY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STEADY
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

class RainMotionFactorTest {
	@Test
	fun `drizzle and heavy rain anchor the motion range`() {
		assertEquals(0f, rainMotionFactor(SEVERITY_DRIZZLE), 0.0001f)
		assertEquals(1f, rainMotionFactor(SEVERITY_HEAVY), 0.0001f)
	}

	@Test
	fun `motion increases across produced severity bands`() {
		val drizzle = rainMotionFactor(SEVERITY_DRIZZLE)
		val steady = rainMotionFactor(SEVERITY_STEADY)
		val storm = rainMotionFactor(SEVERITY_STORM)
		val heavy = rainMotionFactor(SEVERITY_HEAVY)

		assertTrue(steady > drizzle)
		assertTrue(storm > steady)
		assertTrue(heavy > storm)
	}

	@Test
	fun `out of range severity is clamped`() {
		assertEquals(0f, rainMotionFactor(-1f), 0.0001f)
		assertEquals(1f, rainMotionFactor(2f), 0.0001f)
	}
}
