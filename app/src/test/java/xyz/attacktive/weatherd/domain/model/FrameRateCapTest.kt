package xyz.attacktive.weatherd.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRateCapTest {
	@Test
	fun `an uncapped rate never waits between frames`() {
		assertEquals(0L, FrameRateCap.UNCAPPED.intervalMillis)
	}

	@Test
	fun `each cap waits its own period between frames`() {
		assertEquals(33L, FrameRateCap.FPS_30.intervalMillis)
		assertEquals(66L, FrameRateCap.FPS_15.intervalMillis)
		assertEquals(100L, FrameRateCap.FPS_10.intervalMillis)
	}

	@Test
	fun `an unrecognized name falls back to uncapped`() {
		assertEquals(FrameRateCap.UNCAPPED, FrameRateCap.fromName("FPS_240"))
		assertEquals(FrameRateCap.UNCAPPED, FrameRateCap.fromName(null))
	}
}
