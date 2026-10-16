package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.BirdAppearanceInterval
import xyz.attacktive.weatherd.domain.model.BirdFlockSize

class BirdFlockFrameTest {
	@Test
	fun `natural timing retains the legacy quiet slots and flock characteristics`() {
		val frame = BirdFlockFrame()
		assertFalse(frame.sample(116.98321f, BirdFlockSize.SMALL, BirdAppearanceInterval.NATURAL))
		assertFalse(frame.sample(256.8304f, BirdFlockSize.SMALL, BirdAppearanceInterval.NATURAL))
		assertFalse(frame.sample(597.8f, BirdFlockSize.SMALL, BirdAppearanceInterval.NATURAL))
		assertTrue(frame.sample(608.885f, BirdFlockSize.SMALL, BirdAppearanceInterval.NATURAL))
		assertEquals(4, frame.flockSize)
		assertEquals(-1f, frame.direction, 0f)
		assertEquals(0.23721726f, frame.heightFraction, 0.0001f)
		assertEquals(0.5f, frame.progress, 0.0001f)
		assertFalse(frame.sample(620f, BirdFlockSize.SMALL, BirdAppearanceInterval.NATURAL))
	}

	@Test
	fun `explicit interval repeats a crossing without randomly skipping slots`() {
		val frame = BirdFlockFrame()
		for (slot in 0 until 20) {
			val start = slot * 30f
			assertTrue(frame.sample(start + 11f, BirdFlockSize.SMALL, BirdAppearanceInterval.SECONDS_30))
			assertEquals(0.5f, frame.progress, 0.0001f)
			assertFalse(frame.sample(start + 22.01f, BirdFlockSize.SMALL, BirdAppearanceInterval.SECONDS_30))
			assertFalse(frame.sample(start + 29.99f, BirdFlockSize.SMALL, BirdAppearanceInterval.SECONDS_30))
		}
	}

	@Test
	fun `longer interval leaves the requested quiet gap`() {
		val frame = BirdFlockFrame()
		assertTrue(frame.sample(11f, BirdFlockSize.SMALL, BirdAppearanceInterval.MINUTES_5))
		assertFalse(frame.sample(41f, BirdFlockSize.SMALL, BirdAppearanceInterval.MINUTES_5))
		assertFalse(frame.sample(299.99f, BirdFlockSize.SMALL, BirdAppearanceInterval.MINUTES_5))
		assertTrue(frame.sample(311f, BirdFlockSize.SMALL, BirdAppearanceInterval.MINUTES_5))
		assertEquals(0.5f, frame.progress, 0.0001f)
	}

	@Test
	fun `flock size changes do not restart or move a crossing`() {
		val frame = BirdFlockFrame()
		assertTrue(frame.sample(11f, BirdFlockSize.SMALL, BirdAppearanceInterval.SECONDS_30))
		val direction = frame.direction
		val height = frame.heightFraction
		assertTrue(frame.flockSize in 3..5)

		assertTrue(frame.sample(11f, BirdFlockSize.MEDIUM, BirdAppearanceInterval.SECONDS_30))
		assertTrue(frame.flockSize in 6..8)
		assertEquals(0.5f, frame.progress, 0.0001f)
		assertEquals(direction, frame.direction, 0f)
		assertEquals(height, frame.heightFraction, 0f)

		assertTrue(frame.sample(11f, BirdFlockSize.LARGE, BirdAppearanceInterval.SECONDS_30))
		assertTrue(frame.flockSize in 9..12)
		assertEquals(0.5f, frame.progress, 0.0001f)
		assertEquals(direction, frame.direction, 0f)
		assertEquals(height, frame.heightFraction, 0f)
	}

	@Test
	fun `turning birds off and back on retains the shared schedule`() {
		val frame = BirdFlockFrame()
		assertTrue(frame.sample(11f, BirdFlockSize.SMALL, BirdAppearanceInterval.SECONDS_30))
		assertFalse(frame.sample(12f, BirdFlockSize.OFF, BirdAppearanceInterval.SECONDS_30))
		assertTrue(frame.sample(13f, BirdFlockSize.SMALL, BirdAppearanceInterval.SECONDS_30))
		assertEquals(13f / 22f, frame.progress, 0.0001f)
	}

	@Test
	fun `changing the interval invalidates a cached quiet slot`() {
		val frame = BirdFlockFrame()
		assertFalse(frame.sample(11f, BirdFlockSize.SMALL, BirdAppearanceInterval.NATURAL))
		assertTrue(frame.sample(11f, BirdFlockSize.SMALL, BirdAppearanceInterval.SECONDS_30))
		assertEquals(0.5f, frame.progress, 0.0001f)
	}

	@Test
	fun `separate surfaces derive the same flock regardless of sampling history`() {
		val preview = BirdFlockFrame()
		val wallpaper = BirdFlockFrame()
		preview.sample(11f, BirdFlockSize.SMALL, BirdAppearanceInterval.SECONDS_30)
		assertTrue(preview.sample(131f, BirdFlockSize.LARGE, BirdAppearanceInterval.MINUTES_2))
		assertTrue(wallpaper.sample(131f, BirdFlockSize.LARGE, BirdAppearanceInterval.MINUTES_2))
		assertEquals(preview.flockSize, wallpaper.flockSize)
		assertEquals(preview.direction, wallpaper.direction, 0f)
		assertEquals(preview.heightFraction, wallpaper.heightFraction, 0f)
		assertEquals(preview.progress, wallpaper.progress, 0f)
	}
}
