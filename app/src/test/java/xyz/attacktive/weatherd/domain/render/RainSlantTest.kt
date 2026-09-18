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
		val observed = 0.65f

		val subtle = rainSlant(gust, 0.1f, observed)
		val intense = rainSlant(gust, 2f, observed)

		assertEquals(20f, intense / subtle, 0.01f)
		assertEquals(0.0518f, subtle, 0.0001f)
		assertEquals(1.0361f, intense, 0.0001f)
	}

	@Test
	fun `the floor is scalable rather than fixed`() {
		// 0.16 is the renderer's calm baseline slant; the user's Subtle must be able to go under it.
		val gust = 0.5f
		val observed = 0.65f

		val unscaled = rainSlant(gust, 1f, observed)
		val subtle = rainSlant(gust, 0.1f, observed)

		assertTrue("Subtle must fall well below the unscaled floor", subtle < unscaled / 5f)
	}

	@Test
	fun `measured precipitation continuously changes the production slant`() {
		val gust = 0.5f

		val drizzle = rainSlant(gust, 1f, observed = 0.35f)
		val steady = rainSlant(gust, 1f, observed = 0.65f)
		val heavy = rainSlant(gust, 1f, observed = 0.9f)
		val downpour = rainSlant(gust, 1f, observed = 1f)

		assertTrue(steady > drizzle)
		assertTrue(heavy > steady)
		assertTrue(downpour > heavy)
	}

	@Test
	fun `zero observed precipitation preserves the original calm floor`() {
		// With no measured precipitation contribution, the renderer keeps the original baseline values across breeze levels.
		val table = listOf(
			0.28717f to 0.3333f,
			0.48559f to 0.4531f,
			0.65975f to 0.5582f,
			0.84147f to 0.6678f,
			1.00000f to 0.7635f
		)

		for ((windFactor, expectedSlant) in table) {
			val gust = 0.85f * windFactor
			val actualSlant = rainSlant(gust, 1f, observed = 0f)

			assertEquals(expectedSlant, actualSlant, 0.0001f)
		}
	}

	@Test
	fun `the slant clamps at MAX_WIND_SLANT under high wind and scale`() {
		// High wind and maximum scale must not lean streaks past roughly 54 degrees off vertical.
		val galeGust = 1f

		val slant = rainSlant(galeGust, 2f, observed = 1f)

		assertEquals(1.4f, slant, 0.0001f)
	}

	@Test
	fun `out of range observed precipitation is clamped`() {
		val gust = 0.5f

		assertEquals(rainSlant(gust, 1f, observed = 0f), rainSlant(gust, 1f, observed = -1f), 0.0001f)
		assertEquals(rainSlant(gust, 1f, observed = 1f), rainSlant(gust, 1f, observed = 2f), 0.0001f)
	}
}
