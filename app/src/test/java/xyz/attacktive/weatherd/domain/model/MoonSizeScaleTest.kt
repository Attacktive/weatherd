package xyz.attacktive.weatherd.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MoonSizeScaleTest {
	@Test
	fun `normalization preserves valid values within range`() {
		for (value in listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)) {
			assertEquals(value, normalizeMoonSizeScale(value), 0.0001f)
		}
	}

	@Test
	fun `normalization clamps values below range to minimum`() {
		for (value in listOf(-10.0f, -0.1f, 0.0f, 0.49f)) {
			assertEquals(0.5f, normalizeMoonSizeScale(value), 0.0001f)
		}
	}

	@Test
	fun `normalization clamps values above range to maximum`() {
		for (value in listOf(2.01f, 2.5f, 10.0f, 100.0f)) {
			assertEquals(2.0f, normalizeMoonSizeScale(value), 0.0001f)
		}
	}

	@Test
	fun `normalization resolves non-finite values to default one`() {
		for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
			assertEquals(1.0f, normalizeMoonSizeScale(value), 0.0001f)
		}
	}
}
