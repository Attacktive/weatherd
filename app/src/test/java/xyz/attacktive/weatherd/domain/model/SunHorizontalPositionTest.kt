package xyz.attacktive.weatherd.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SunHorizontalPositionTest {
	@Test
	fun `valid values preserve sun horizontal position and out-of-range values clamp to the slider bounds`() {
		for (value in listOf(0f, 0.25f, 0.5f, 0.72f, 1f)) {
			assertEquals(value, normalizeSunHorizontalPosition(value), 0.0001f)
		}

		assertEquals(0f, normalizeSunHorizontalPosition(-0.5f), 0.0001f)
		assertEquals(1f, normalizeSunHorizontalPosition(1.5f), 0.0001f)
	}

	@Test
	fun `non-finite sun horizontal position restores default position`() {
		for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
			assertEquals(DEFAULT_SUN_HORIZONTAL_POSITION, normalizeSunHorizontalPosition(value), 0.0001f)
		}
	}
}
