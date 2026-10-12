package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistantAircraftMotionTest {
	@Test
	fun `flight crosses slowly in both directions`() {
		val width = 360f
		val height = 780f
		val rightward = findPass(width, height, rightward = true)
		val leftward = findPass(width, height, rightward = false)

		for (pass in listOf(rightward, leftward)) {
			val frame = DistantAircraftFrame()
			assertTrue(frame.sample(width, height, pass.startSeconds + 30f))
			assertEquals(width * 0.5f, frame.planeX, 0.001f)
			assertEquals(0.016f * minOf(width, height), frame.bodyLength, 0.001f)
			assertEquals(1f, hypot(frame.directionX, frame.directionY), 0.0001f)

			val xBefore = frame.planeX
			assertTrue(frame.sample(width, height, pass.startSeconds + 31f))
			if (pass.rightward) {
				assertTrue(frame.planeX > xBefore)
			} else {
				assertTrue(frame.planeX < xBefore)
			}
		}

		for ((testWidth, testHeight) in listOf(360f to 780f, 780f to 360f, 540f to 780f)) {
			val pass = findPass(testWidth, testHeight)
			val frame = DistantAircraftFrame()
			assertTrue(frame.sample(testWidth, testHeight, pass.startSeconds + 30f))
			assertEquals(0.016f * minOf(testWidth, testHeight), frame.bodyLength, 0.001f)
			assertTrue(frame.trailStartAgeSeconds <= 20f)
			assertTrue(trailIsOnFlightLine(frame))
			assertTrue(trailEndsAtOrBehindPlane(frame))
		}
	}

	@Test
	fun `trail emission ages and expires after exit`() {
		val pass = findPass(540f, 780f)
		val frame = DistantAircraftFrame()

		assertTrue(frame.sample(540f, 780f, pass.startSeconds + 65f))
		assertEquals(0f, frame.planeOpacity, 0f)
		assertTrue(frame.hasTrail)
		assertTrue(frame.trailStartAgeSeconds <= 20f)
		assertTrue(frame.trailEndAgeSeconds >= 0f)

		assertFalse(frame.sample(540f, 780f, pass.startSeconds + 80f))
		assertEquals(0f, frame.planeOpacity, 0f)
		assertFalse(frame.hasTrail)
	}

	@Test
	fun `sampling skips and rewinds without history`() {
		val width = 540f
		val height = 780f
		val pass = findPass(width, height)
		val activeTime = pass.startSeconds + 30f
		val reused = DistantAircraftFrame()
		val fresh = DistantAircraftFrame()

		assertTrue(reused.sample(width, height, activeTime))
		assertFalse(reused.sample(width, height, pass.slotStartSeconds + 179.99f))
		assertTrue(reused.sample(width, height, activeTime))
		assertTrue(fresh.sample(width, height, activeTime))
		assertDrawableEquals(fresh, reused)

		val frozen = snapshot(reused)
		repeat(20) {
			assertTrue(reused.sample(width, height, activeTime))
			assertDrawableEquals(frozen, reused)
		}
	}

	@Test
	fun `every scheduling slot contains a discoverable pass`() {
		val width = 540f
		val height = 780f
		val frame = DistantAircraftFrame()

		for (slot in 0 until 120) {
			val slotStart = slot * 180f
			var active = false
			for (step in 10..170 step 5) {
				active = active || frame.sample(width, height, slotStart + step)
			}

			assertTrue("Expected an aircraft pass in scheduling slot $slot", active)
		}
	}

	@Test
	fun `clock boundaries leave no residual geometry`() {
		val width = 540f
		val height = 780f
		val active = findPass(width, height)
		val frame = DistantAircraftFrame()
		assertTrue(frame.sample(width, height, active.startSeconds + 30f))

		for (time in listOf(0f, 21_599.99f)) {
			assertFalse(frame.sample(width, height, time))
			assertCleared(frame)
		}
	}

	@Test
	fun `invalid viewport clears a previously active frame`() {
		val pass = findPass(540f, 780f)
		val frame = DistantAircraftFrame()
		assertTrue(frame.sample(540f, 780f, pass.startSeconds + 30f))

		val invalidSamples = listOf(
			Triple(0f, 780f, pass.startSeconds + 30f),
			Triple(-1f, 780f, pass.startSeconds + 30f),
			Triple(540f, 0f, pass.startSeconds + 30f),
			Triple(540f, -1f, pass.startSeconds + 30f),
			Triple(Float.NaN, 780f, pass.startSeconds + 30f),
			Triple(540f, Float.POSITIVE_INFINITY, pass.startSeconds + 30f),
			Triple(540f, 780f, Float.NaN),
			Triple(540f, 780f, Float.POSITIVE_INFINITY),
			Triple(540f, 780f, 21_600f)
		)

		for ((width, height, time) in invalidSamples) {
			assertFalse(frame.sample(width, height, time))
			assertCleared(frame)
		}
	}

	private fun findPass(width: Float, height: Float, rightward: Boolean? = null): PassFixture {
		val frame = DistantAircraftFrame()
		for (slot in 0 until 120) {
			val slotStart = slot * 180f
			for (step in 10..170) {
				if (!frame.sample(width, height, slotStart + step)) {
					continue
				}

				val isRightward = frame.directionX > 0f
				if (rightward == null || rightward == isRightward) {
					return PassFixture(slotStart, frame.passStartSeconds, isRightward)
				}
			}
		}

		error("No matching active aircraft pass found in one scene-clock period")
	}

	private fun trailIsOnFlightLine(frame: DistantAircraftFrame): Boolean {
		val trailX = frame.trailEndX - frame.trailStartX
		val trailY = frame.trailEndY - frame.trailStartY
		val cross = trailX * frame.directionY - trailY * frame.directionX

		return abs(cross) < 0.001f
	}

	private fun trailEndsAtOrBehindPlane(frame: DistantAircraftFrame): Boolean {
		val toPlaneX = frame.planeX - frame.trailEndX
		val toPlaneY = frame.planeY - frame.trailEndY

		return toPlaneX * frame.directionX + toPlaneY * frame.directionY >= -0.001f
	}

	private fun snapshot(frame: DistantAircraftFrame) = DrawableSnapshot(
		planeX = frame.planeX,
		planeY = frame.planeY,
		directionX = frame.directionX,
		directionY = frame.directionY,
		bodyLength = frame.bodyLength,
		planeOpacity = frame.planeOpacity,
		trailStartX = frame.trailStartX,
		trailStartY = frame.trailStartY,
		trailEndX = frame.trailEndX,
		trailEndY = frame.trailEndY,
		trailStartAgeSeconds = frame.trailStartAgeSeconds,
		trailEndAgeSeconds = frame.trailEndAgeSeconds,
		hasTrail = frame.hasTrail
	)

	private fun assertDrawableEquals(expected: DistantAircraftFrame, actual: DistantAircraftFrame) {
		assertDrawableEquals(snapshot(expected), actual)
	}

	private fun assertDrawableEquals(expected: DrawableSnapshot, actual: DistantAircraftFrame) {
		assertEquals(expected.planeX, actual.planeX, 0f)
		assertEquals(expected.planeY, actual.planeY, 0f)
		assertEquals(expected.directionX, actual.directionX, 0f)
		assertEquals(expected.directionY, actual.directionY, 0f)
		assertEquals(expected.bodyLength, actual.bodyLength, 0f)
		assertEquals(expected.planeOpacity, actual.planeOpacity, 0f)
		assertEquals(expected.trailStartX, actual.trailStartX, 0f)
		assertEquals(expected.trailStartY, actual.trailStartY, 0f)
		assertEquals(expected.trailEndX, actual.trailEndX, 0f)
		assertEquals(expected.trailEndY, actual.trailEndY, 0f)
		assertEquals(expected.trailStartAgeSeconds, actual.trailStartAgeSeconds, 0f)
		assertEquals(expected.trailEndAgeSeconds, actual.trailEndAgeSeconds, 0f)
		assertEquals(expected.hasTrail, actual.hasTrail)
	}

	private fun assertCleared(frame: DistantAircraftFrame) {
		assertEquals(0f, frame.planeX, 0f)
		assertEquals(0f, frame.planeY, 0f)
		assertEquals(0f, frame.directionX, 0f)
		assertEquals(0f, frame.directionY, 0f)
		assertEquals(0f, frame.bodyLength, 0f)
		assertEquals(0f, frame.planeOpacity, 0f)
		assertFalse(frame.hasTrail)
		assertEquals(0f, frame.trailStartX, 0f)
		assertEquals(0f, frame.trailStartY, 0f)
		assertEquals(0f, frame.trailEndX, 0f)
		assertEquals(0f, frame.trailEndY, 0f)
	}

	private data class PassFixture(
		val slotStartSeconds: Float,
		val startSeconds: Float,
		val rightward: Boolean
	)

	private data class DrawableSnapshot(
		val planeX: Float,
		val planeY: Float,
		val directionX: Float,
		val directionY: Float,
		val bodyLength: Float,
		val planeOpacity: Float,
		val trailStartX: Float,
		val trailStartY: Float,
		val trailEndX: Float,
		val trailEndY: Float,
		val trailStartAgeSeconds: Float,
		val trailEndAgeSeconds: Float,
		val hasTrail: Boolean
	)
}
