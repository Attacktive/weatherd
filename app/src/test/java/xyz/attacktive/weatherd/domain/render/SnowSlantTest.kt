package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnowSlantTest {
	@Test
	fun `the scale spans its full range on screen`() {
		// The slider promises 0.1x..2.0x.
		// A 12 km/h breeze shapes to 0.4856 windFactor, with mean gust term 0.85 * windFactor = 0.41275f.
		val gust = 0.41275f

		val subtle = snowSlant(gust, 0.1f)
		val intense = snowSlant(gust, 2f)

		assertEquals(20f, intense / subtle, 0.01f)
		assertEquals(0.0431f, subtle, 0.0001f)
		assertEquals(0.8630f, intense, 0.0001f)
	}

	@Test
	fun `the floor is scalable rather than fixed`() {
		// 0.06 is the calm baseline slant; the user's Subtle must be able to go under it.
		val gust = 0.5f

		val unscaled = snowSlant(gust, 1f)
		val subtle = snowSlant(gust, 0.1f)

		assertTrue("Subtle must fall well below the unscaled floor", subtle < unscaled / 5f)
	}

	@Test
	fun `at default scale values sit naturally beside rain across breeze levels`() {
		// In dead calm, snow barely leans.
		assertEquals(0.06f, snowSlant(0f, 1f), 0.0001f)

		// At a 12 km/h breeze, snow slant (0.4315) sits naturally beside rain (0.4531).
		val breezeGust = 0.41275f
		val snowBreeze = snowSlant(breezeGust, 1f)
		val rainBreeze = rainSlant(breezeGust, 1f)

		assertEquals(0.4315f, snowBreeze, 0.0001f)
		assertEquals(0.4531f, rainBreeze, 0.0001f)

		// Snow catches wind more readily than rain at higher gusts.
		val highGust = 0.85f

		assertTrue(snowSlant(highGust, 1f) > rainSlant(highGust, 1f))
	}

	@Test
	fun `the slant clamps at MAX_WIND_SLANT under high wind and scale`() {
		// High wind and maximum scale must not lean past roughly 54 degrees off vertical.
		val galeGust = 1f

		val slant = snowSlant(galeGust, 2f)

		assertEquals(MAX_WIND_SLANT, slant, 0.0001f)
	}

	@Test
	fun `at minimum slider setting travel is near-vertical`() {
		// Even under moderate wind, setting the slider to 0.1x produces a near-vertical path.
		val gust = 0.5f

		val calmSlant = snowSlant(0f, 0.1f)
		val gustSlant = snowSlant(gust, 0.1f)

		assertTrue(calmSlant < 0.01f)
		assertTrue(gustSlant < 0.06f)
	}
}
