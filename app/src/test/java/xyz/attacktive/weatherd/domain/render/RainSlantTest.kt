package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RainSlantTest {
	@Test
	fun `the scale spans its full range on screen`() {
		// The slider promises 0.1x..2.0x.
		// A 12 km/h breeze shapes to 0.4856 windFactor, with mean gust term 0.85 * windFactor = 0.41275f.
		val gust = 0.41275f

		val subtle = rainSlant(gust, 0.1f)
		val intense = rainSlant(gust, 2f)

		assertEquals(20f, intense / subtle, 0.01f)
		assertEquals(0.0453f, subtle, 0.0001f)
		assertEquals(0.9061f, intense, 0.0001f)
	}

	@Test
	fun `the floor is scalable rather than fixed`() {
		// 0.16 is the renderer's calm baseline slant; the user's Subtle must be able to go under it.
		val gust = 0.5f

		val unscaled = rainSlant(gust, 1f)
		val subtle = rainSlant(gust, 0.1f)

		assertTrue("Subtle must fall well below the unscaled floor", subtle < unscaled / 5f)
	}

	@Test
	fun `at default scale every value is unchanged from baseline`() {
		val table = listOf(
			0.28717f to 0.3333f,
			0.48559f to 0.4531f,
			0.65975f to 0.5582f,
			0.84147f to 0.6678f,
			1.00000f to 0.7635f
		)

		for ((windFactor, expectedSlant) in table) {
			val gust = 0.85f * windFactor
			val actualSlant = rainSlant(gust, 1f)

			assertEquals(expectedSlant, actualSlant, 0.0001f)
		}
	}

	@Test
	fun `the slant clamps at MAX_WIND_SLANT under high wind and scale`() {
		// High wind and maximum scale must not lean streaks past roughly 54 degrees off vertical.
		val galeGust = 1f

		val slant = rainSlant(galeGust, 2f, heavy = true)

		assertEquals(1.4f, slant, 0.0001f)
	}

	@Test
	fun `heavy rain leans steeper than light rain at the same scale`() {
		val gust = 0.5f

		assertTrue(rainSlant(gust, 1f, heavy = true) > rainSlant(gust, 1f, heavy = false))
	}
}
