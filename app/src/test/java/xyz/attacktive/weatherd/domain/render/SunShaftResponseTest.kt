package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SunShaftResponseTest {
	@Test
	fun `new openings brighten gradually and closed openings fade rather than snap off`() {
		val response = SunShaftResponse(1)
		response.update(0f)
		response.targets[0] = 1f
		response.update(0.1f)
		assertTrue(response.strengths[0] in 0.01f..0.2f)
		response.update(1.1f)
		val brightened = response.strengths[0]
		assertTrue(brightened in 0.4f..0.8f)
		response.targets[0] = 0f
		response.update(1.2f)
		assertTrue(response.strengths[0] > brightened * 0.8f)
		response.update(2.2f)
		assertTrue(response.strengths[0] in 0.1f..0.4f)
	}

	@Test
	fun `the same elapsed time produces the same fade at different frame rates`() {
		fun strengthAt(frameRate: Int): Float {
			val response = SunShaftResponse(1)
			response.update(0f)
			response.targets[0] = 1f
			for (frame in 1..frameRate) {
				response.update(frame.toFloat() / frameRate)
			}

			return response.strengths[0]
		}

		assertEquals(strengthAt(15), strengthAt(60), 0.00001f)
	}

	@Test
	fun `first frame and discontinuities use the current scene without stale light`() {
		val response = SunShaftResponse(1)
		response.targets[0] = 0.7f
		response.update(20f)
		assertEquals(0.7f, response.strengths[0], 0f)
		response.targets[0] = 0f
		response.update(20.1f, reset = true)
		assertEquals(0f, response.strengths[0], 0f)
		response.targets[0] = 1f
		response.update(19f)
		assertEquals(1f, response.strengths[0], 0f)
		response.targets[0] = 0f
		response.update(100f)
		assertEquals(0f, response.strengths[0], 0f)
	}

	@Test
	fun `repeated timestamps do not advance fades or couple angular channels`() {
		val response = SunShaftResponse(2)
		response.update(0f)
		response.targets[0] = 1f
		response.update(0.5f)
		val strength = response.strengths[0]
		response.targets[1] = 1f
		response.update(0.5f)
		assertEquals(strength, response.strengths[0], 0f)
		assertEquals(0f, response.strengths[1], 0f)
	}
}
