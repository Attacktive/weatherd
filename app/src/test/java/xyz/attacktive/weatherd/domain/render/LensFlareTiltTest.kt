package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensFlareTiltTest {
	@Test
	fun `tilting horizontally moves only the horizontal reflections`() {
		val tilt = LensFlareTilt()
		tilt.update(0f, 9.81f, 0f, 0, 1_000_000_000L)
		tilt.update(4.905f, 8.4957f, 0f, 0, 2_000_000_000L)

		assertTrue(tilt.offsetX > 0.5f)
		assertTrue(tilt.offsetX <= 1f)
		assertEquals(0f, tilt.offsetY, 0.0001f)
	}

	@Test
	fun `tilting forward moves only the vertical reflections`() {
		val tilt = LensFlareTilt()
		tilt.update(0f, 9.81f, 0f, 0, 1_000_000_000L)
		tilt.update(0f, 8.4957f, 4.905f, 0, 2_000_000_000L)

		assertEquals(0f, tilt.offsetX, 0.0001f)
		assertTrue(tilt.offsetY > 0.5f)
		assertTrue(tilt.offsetY <= 1f)
	}

	@Test
	fun `equivalent screen tilts move reflections the same way in every rotation`() {
		val rotations = listOf(
			floatArrayOf(0f, 9.81f, 4.905f, 8.4957f),
			floatArrayOf(-9.81f, 0f, -8.4957f, 4.905f),
			floatArrayOf(0f, -9.81f, -4.905f, -8.4957f),
			floatArrayOf(9.81f, 0f, 8.4957f, -4.905f)
		)

		for ((rotation, gravity) in rotations.withIndex()) {
			val tilt = LensFlareTilt()
			tilt.update(gravity[0], gravity[1], 0f, rotation, 1_000_000_000L)
			tilt.update(gravity[2], gravity[3], 0f, rotation, 2_000_000_000L)

			assertTrue("Screen-right tilt must stay screen-right in rotation $rotation", tilt.offsetX > 0.5f)
			assertEquals(0f, tilt.offsetY, 0.0001f)
		}
	}

	@Test
	fun `sensor noise is smoothed rather than jumping to the full displacement`() {
		val tilt = LensFlareTilt()
		tilt.update(0f, 9.81f, 0f, 0, 1_000_000_000L)
		tilt.update(4.905f, 8.4957f, 0f, 0, 1_016_000_000L)
		val first = tilt.offsetX
		assertTrue(first > 0f && first < 0.3f)

		for (sample in 2..100) {
			tilt.update(4.905f, 8.4957f, 0f, 0, 1_000_000_000L + sample * 16_000_000L)
		}

		assertTrue(tilt.offsetX > 0.5f && tilt.offsetX <= 1f)
	}

	@Test
	fun `invalid and out of order samples cannot corrupt the current displacement`() {
		val tilt = LensFlareTilt()
		tilt.update(0f, 9.81f, 0f, 0, 1_000_000_000L)
		tilt.update(4.905f, 8.4957f, 0f, 0, 2_000_000_000L)
		val before = tilt.offsetX
		tilt.update(Float.NaN, 1f, 0f, 0, 3_000_000_000L)
		tilt.update(0f, 0f, 0f, 0, 3_000_000_000L)
		tilt.update(-4.905f, 8.4957f, 0f, 0, 1_500_000_000L)

		assertEquals(before, tilt.offsetX, 0f)
		assertEquals(0f, tilt.offsetY, 0f)
	}

	@Test
	fun `extreme tilts stay inside the reflection travel bounds`() {
		val tilt = LensFlareTilt()
		tilt.update(0f, 9.81f, 0f, 0, 1_000_000_000L)
		tilt.update(9.81f, 0f, 0f, 0, 2_000_000_000L)

		assertTrue(tilt.offsetX > 0.9f && tilt.offsetX <= 1f)
		tilt.update(-9.81f, 0f, 0f, 0, 3_000_000_000L)
		assertTrue(tilt.offsetX < -0.9f && tilt.offsetX >= -1f)
	}

	@Test
	fun `stopping and restarting recenters at the new phone position`() {
		val tilt = LensFlareTilt()
		tilt.update(0f, 9.81f, 0f, 0, 1_000_000_000L)
		tilt.update(4.905f, 8.4957f, 0f, 0, 2_000_000_000L)
		tilt.reset()

		assertEquals(0f, tilt.offsetX, 0f)
		assertEquals(0f, tilt.offsetY, 0f)
		tilt.update(4.905f, 8.4957f, 0f, 0, 3_000_000_000L)
		assertEquals(0f, tilt.offsetX, 0f)
		assertEquals(0f, tilt.offsetY, 0f)
	}

	@Test
	fun `changing display rotation recenters instead of jumping across the screen`() {
		val tilt = LensFlareTilt()
		tilt.update(0f, 9.81f, 0f, 0, 1_000_000_000L)
		tilt.update(4.905f, 8.4957f, 0f, 0, 2_000_000_000L)
		tilt.update(-8.4957f, 4.905f, 0f, 1, 3_000_000_000L)

		assertEquals(0f, tilt.offsetX, 0f)
		assertEquals(0f, tilt.offsetY, 0f)
	}

	@Test
	fun `opposite pitches from a face-up neutral pose move reflections in opposite directions`() {
		val forward = LensFlareTilt()
		forward.update(0f, 0f, 9.81f, 0, 1_000_000_000L)
		forward.update(0f, 4.905f, 8.4957f, 0, 2_000_000_000L)
		val backward = LensFlareTilt()
		backward.update(0f, 0f, 9.81f, 0, 1_000_000_000L)
		backward.update(0f, -4.905f, 8.4957f, 0, 2_000_000_000L)

		assertTrue(forward.offsetY < -0.5f)
		assertTrue(backward.offsetY > 0.5f)
	}

	@Test
	fun `pitch keeps moving in the same direction while crossing horizontal`() {
		val tilt = LensFlareTilt()
		tilt.update(0f, 4.905f, 8.4957f, 0, 1_000_000_000L)
		tilt.update(0f, 0f, 9.81f, 0, 2_000_000_000L)
		val atHorizontal = tilt.offsetY
		tilt.update(0f, -4.905f, 8.4957f, 0, 3_000_000_000L)

		assertTrue(atHorizontal > 0.5f)
		assertTrue(tilt.offsetY > atHorizontal)
	}

	@Test
	fun `pitch crossing the angle seam follows the short motion`() {
		val tilt = LensFlareTilt()
		tilt.update(0f, -9.804f, 0.342f, 0, 1_000_000_000L)
		tilt.update(0f, -9.804f, -0.342f, 0, 2_000_000_000L)

		assertTrue(tilt.offsetY > 0f && tilt.offsetY < 0.3f)
	}
}
