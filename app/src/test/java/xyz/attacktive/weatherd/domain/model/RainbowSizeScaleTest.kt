package xyz.attacktive.weatherd.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RainbowSizeScaleTest {
	@Test
	fun `rainbow size preserves supported values and clamps finite outliers`() {
		for ((input, expected) in listOf(-1f to 0.6f, 0.59f to 0.6f, 0.6f to 0.6f, 0.85f to 0.85f, 1f to 1f, 1.4f to 1.4f, 1.41f to 1.4f, 10f to 1.4f)) {
			assertEquals(expected, normalizeRainbowSizeScale(input), 0.0001f)
		}
	}

	@Test
	fun `non-finite rainbow size restores the current appearance`() {
		for (input in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
			assertEquals(1f, normalizeRainbowSizeScale(input), 0.0001f)
		}
	}
}
