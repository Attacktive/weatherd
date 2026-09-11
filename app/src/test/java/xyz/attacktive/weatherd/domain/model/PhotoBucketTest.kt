package xyz.attacktive.weatherd.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoBucketTest {
	@Test
	fun `each phase draws its own photo when the user filled that bucket`() {
		val all = setOf(PhotoBucket.DAY, PhotoBucket.NIGHT, PhotoBucket.DAWN, PhotoBucket.DUSK)

		assertEquals(PhotoBucket.DAY, photoBucketFor(DayPhase.DAY, all))
		assertEquals(PhotoBucket.NIGHT, photoBucketFor(DayPhase.NIGHT, all))
		assertEquals(PhotoBucket.DAWN, photoBucketFor(DayPhase.DAWN, all))
		assertEquals(PhotoBucket.DUSK, photoBucketFor(DayPhase.DUSK, all))
	}

	@Test
	fun `an unfilled twilight borrows from the phase it borders in light`() {
		val lit = setOf(PhotoBucket.DAY, PhotoBucket.NIGHT)

		assertEquals(PhotoBucket.DAY, photoBucketFor(DayPhase.DAWN, lit))
		assertEquals(PhotoBucket.NIGHT, photoBucketFor(DayPhase.DUSK, lit))
	}

	@Test
	fun `a twilight never borrows across the light`() {
		assertNull("dawn must not reach for the night photo", photoBucketFor(DayPhase.DAWN, setOf(PhotoBucket.NIGHT)))
		assertNull("dusk must not reach for the day photo", photoBucketFor(DayPhase.DUSK, setOf(PhotoBucket.DAY)))
	}

	@Test
	fun `day and night never stand in for each other`() {
		assertNull("a night photo must not appear at noon", photoBucketFor(DayPhase.DAY, setOf(PhotoBucket.NIGHT)))
		assertNull("a day photo must not appear at midnight", photoBucketFor(DayPhase.NIGHT, setOf(PhotoBucket.DAY)))
	}

	@Test
	fun `a twilight photo never rises to the phase it borrows from`() {
		val twilight = setOf(PhotoBucket.DAWN, PhotoBucket.DUSK)

		assertNull("day must not fall back to its dawn", photoBucketFor(DayPhase.DAY, twilight))
		assertNull("night must not fall back to its dusk", photoBucketFor(DayPhase.NIGHT, twilight))
	}

	@Test
	fun `the fallback stops at one hop, so an empty day leaves dawn with nothing`() {
		val dusk = setOf(PhotoBucket.DUSK, PhotoBucket.NIGHT)

		assertNull("dawn borrows from day only, never onward to night", photoBucketFor(DayPhase.DAWN, dusk))
	}

	@Test
	fun `no photos at all leaves every phase to the procedural sky`() {
		for (phase in DayPhase.entries) {
			assertNull("$phase must fall through to the drawn sky", photoBucketFor(phase, emptySet()))
		}
	}

	@Test
	fun `an absent or unrecognized stored name resolves to no bucket`() {
		assertEquals(PhotoBucket.DUSK, PhotoBucket.fromName("DUSK"))
		assertNull(PhotoBucket.fromName("GOLDEN_HOUR"))
		assertNull(PhotoBucket.fromName(null))
	}
}
