package xyz.attacktive.weatherd.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.AnimationIdleTimeout

class WallpaperAnimationPolicyTest {
	@Test
	fun `never timeout keeps continuous animation active`() {
		assertTrue(
			wallpaperAnimationActive(
				AnimationIdleTimeout.NEVER,
				motionStartedAtNanos = 0L,
				nowNanos = Long.MAX_VALUE
			)
		)
	}

	@Test
	fun `finite timeout settles exactly at its deadline`() {
		val start = 5_000_000_000L

		assertTrue(
			wallpaperAnimationActive(
				AnimationIdleTimeout.SECONDS_30,
				motionStartedAtNanos = start,
				nowNanos = start + 29_999_999_999L
			)
		)
		assertFalse(
			wallpaperAnimationActive(
				AnimationIdleTimeout.SECONDS_30,
				motionStartedAtNanos = start,
				nowNanos = start + 30_000_000_000L
			)
		)
	}

	@Test
	fun `15 second timeout settles exactly at its deadline`() {
		val start = 5_000_000_000L

		assertTrue(
			wallpaperAnimationActive(
				AnimationIdleTimeout.SECONDS_15,
				motionStartedAtNanos = start,
				nowNanos = start + 14_999_999_999L
			)
		)
		assertFalse(
			wallpaperAnimationActive(
				AnimationIdleTimeout.SECONDS_15,
				motionStartedAtNanos = start,
				nowNanos = start + 15_000_000_000L
			)
		)
	}

	@Test
	fun `clock skew before the visible-session start stays active`() {
		assertTrue(
			wallpaperAnimationActive(
				AnimationIdleTimeout.SECONDS_30,
				motionStartedAtNanos = 10_000L,
				nowNanos = 9_000L
			)
		)
	}
}
