package xyz.attacktive.weatherd.service

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperAnimationClockTest {
	@Test
	fun `animation phase follows the shared monotonic frame clock`() {
		val frameTimeNanos = 3_601_000_000_000L

		assertEquals(3_601f, wallpaperAnimationTimeSeconds(frameTimeNanos), 0.0001f)
	}

	@Test
	fun `animation clock repeats every six hours`() {
		val phaseNanos = 1_234_567_890L
		val wrapNanos = 21_600L * 1_000_000_000L

		assertEquals(
			wallpaperAnimationTimeSeconds(phaseNanos),
			wallpaperAnimationTimeSeconds(phaseNanos + wrapNanos),
			0.0001f
		)
	}

	@Test
	fun `animation clock keeps sub-second precision after long uptime`() {
		val wrapNanos = 21_600L * 1_000_000_000L
		val frameTimeNanos = wrapNanos * 10_000L + 1_250_000_000L

		assertEquals(1.25f, wallpaperAnimationTimeSeconds(frameTimeNanos), 0.0001f)
	}
}
