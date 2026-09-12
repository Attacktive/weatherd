package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSpriteLayoutTest {
	@Test
	fun `cloud placements are deterministic and varied`() {
		val first = cloudSpritePlacements(seed = 22L, width = 400f, height = 200f, count = 12)
		val repeat = cloudSpritePlacements(seed = 22L, width = 400f, height = 200f, count = 12)
		val alternate = cloudSpritePlacements(seed = 23L, width = 400f, height = 200f, count = 12)

		assertEquals(first, repeat)
		assertNotEquals(first, alternate)
		assertTrue(first.any { it.flip })
		assertTrue(first.any { !it.flip })
		assertTrue(first.map { it.spriteIndex }.distinct().size > 1)
	}

	@Test
	fun `cloud placements stay inside the tile layout envelope`() {
		val placements = cloudSpritePlacements(seed = 22L, width = 400f, height = 200f, count = 32)

		assertTrue(placements.all { it.spriteIndex in 0 until CLOUD_SPRITE_COUNT })
		assertTrue(placements.all { it.centerX in 0f..400f })
		assertTrue(placements.all { it.baseline in 0f..200f })
		assertTrue(placements.all { it.width in 1f..400f })
	}
}
