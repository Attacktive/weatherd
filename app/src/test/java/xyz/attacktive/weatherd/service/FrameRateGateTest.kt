package xyz.attacktive.weatherd.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.FrameRateCap

class FrameRateGateTest {
	@Test
	fun `an uncapped rate draws on every vsync`() {
		val interval = FrameRateCap.UNCAPPED.intervalNanos

		assertTrue(drawsFrame(frameTimeNanos = 16_666_666L, lastDrawNanos = 16_666_665L, intervalNanos = interval))
	}

	@Test
	fun `the very first frame draws however tight the cap`() {
		val interval = FrameRateCap.FPS_15.intervalNanos

		assertTrue(drawsFrame(frameTimeNanos = 1L, lastDrawNanos = 0L, intervalNanos = interval))
	}

	@Test
	fun `a capped rate skips vsyncs until its interval has elapsed`() {
		val interval = FrameRateCap.FPS_30.intervalNanos
		val lastDraw = 5_000_000_000L

		assertFalse(drawsFrame(lastDraw + interval - 1L, lastDraw, interval))
		assertTrue(drawsFrame(lastDraw + interval, lastDraw, interval))
	}
}
