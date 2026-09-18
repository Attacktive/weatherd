package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STEADY

class RainMotionFactorTest {
	@Test
	fun `drizzle and downpour anchor the motion range`() {
		assertEquals(0f, rainMotionFactor(SEVERITY_DRIZZLE), 0.0001f)
		assertEquals(1f, rainMotionFactor(1f), 0.0001f)
	}

	@Test
	fun `motion increases smoothly between drizzle and downpour`() {
		val drizzle = rainMotionFactor(SEVERITY_DRIZZLE)
		val steady = rainMotionFactor(SEVERITY_STEADY)
		val heavy = rainMotionFactor(0.85f)
		val downpour = rainMotionFactor(1f)

		assertTrue(steady > drizzle)
		assertTrue(heavy > steady)
		assertTrue(downpour > heavy)
	}

	@Test
	fun `out of range severity is clamped`() {
		assertEquals(0f, rainMotionFactor(-1f), 0.0001f)
		assertEquals(1f, rainMotionFactor(2f), 0.0001f)
	}
}
