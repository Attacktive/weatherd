package xyz.attacktive.weatherd.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRateCapTest {
	@Test
	fun `uncapped frames never add a wait`() {
		assertEquals(0L, FrameRateCap.UNCAPPED.remainingDelayMillis(1_000_000_000L, 1_002_000_000L))
	}

	@Test
	fun `drawing and callback latency consume the capped frame budget`() {
		assertEquals(31L, FrameRateCap.FPS_30.remainingDelayMillis(1_000_000_000L, 1_002_000_000L))
		assertEquals(64L, FrameRateCap.FPS_15.remainingDelayMillis(1_000_000_000L, 1_002_000_000L))
		assertEquals(98L, FrameRateCap.FPS_10.remainingDelayMillis(1_000_000_000L, 1_002_000_000L))
		assertEquals(18L, FrameRateCap.FPS_30.remainingDelayMillis(1_000_000_000L, 1_015_000_000L))
	}

	@Test
	fun `fractional milliseconds are retained until the remaining wait is calculated`() {
		assertEquals(30L, FrameRateCap.FPS_30.remainingDelayMillis(1_000_000_000L, 1_002_800_000L))
		assertEquals(63L, FrameRateCap.FPS_15.remainingDelayMillis(1_000_000_000L, 1_002_800_000L))
	}

	@Test
	fun `overdue frames request the next vsync without another full wait`() {
		assertEquals(0L, FrameRateCap.FPS_30.remainingDelayMillis(1_000_000_000L, 1_033_333_333L))
		assertEquals(0L, FrameRateCap.FPS_30.remainingDelayMillis(1_000_000_000L, 1_050_000_000L))
	}

	@Test
	fun `the latest cap applies without an accumulated catch up deadline`() {
		assertEquals(80L, FrameRateCap.FPS_10.remainingDelayMillis(1_000_000_000L, 1_020_000_000L))
		assertEquals(13L, FrameRateCap.FPS_30.remainingDelayMillis(1_000_000_000L, 1_020_000_000L))
		assertEquals(31L, FrameRateCap.FPS_30.remainingDelayMillis(2_000_000_000L, 2_002_000_000L))
	}

	@Test
	fun `a frame timestamp ahead of now cannot extend the wait beyond one period`() {
		assertEquals(33L, FrameRateCap.FPS_30.remainingDelayMillis(1_000_000_000L, 999_000_000L))
	}

	@Test
	fun `an unrecognized name falls back to uncapped`() {
		assertEquals(FrameRateCap.UNCAPPED, FrameRateCap.fromName("FPS_240"))
		assertEquals(FrameRateCap.UNCAPPED, FrameRateCap.fromName(null))
	}
}
