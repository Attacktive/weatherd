package xyz.attacktive.weatherd.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MoonOpacityTest {
	@Test
	fun `valid values preserve opacity and out-of-range values clamp to the slider bounds`() {
		for (value in listOf(0.25f, 0.5f, 0.75f, 1f)) {
			assertEquals(value, normalizeMoonOpacity(value), 0.0001f)
		}

		assertEquals(0.25f, normalizeMoonOpacity(-1f), 0.0001f)
		assertEquals(0.25f, normalizeMoonOpacity(0f), 0.0001f)
		assertEquals(1f, normalizeMoonOpacity(2f), 0.0001f)
	}

	@Test
	fun `non-finite opacity restores the existing fully opaque appearance`() {
		for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
			assertEquals(1f, normalizeMoonOpacity(value), 0.0001f)
		}
	}
}
