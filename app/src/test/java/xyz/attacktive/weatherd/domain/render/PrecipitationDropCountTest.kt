package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

class PrecipitationDropCountTest {
	@Test
	fun `the scale spans about 4 point 5 x on screen`() {
		// The slider promises 0.1x..2.0x, shaped by the exponent to compress the perceived span to ~4.5x.
		val drizzle = Precipitation(PrecipitationKind.RAIN, SEVERITY_DRIZZLE, observed = 0.2512f)

		val subtle = precipitationDropCount(drizzle, 0.1f, WIDTH, HEIGHT)
		val intense = precipitationDropCount(drizzle, 2f, WIDTH, HEIGHT)

		assertEquals(4.5f, intense.toFloat() / subtle.toFloat(), 0.1f)
	}

	@Test
	fun `the default at 1f produces exactly the unscaled count`() {
		// Any exponent leaves 1f exactly 1f, so the default is untouched.
		val storm = Precipitation(PrecipitationKind.RAIN, SEVERITY_STORM, observed = 0.8747f)
		val unscaled = (precipitationBaseCount(storm, WIDTH, HEIGHT) * (0.4f + 0.6f * storm.observed)).roundToInt()

		assertEquals(unscaled, precipitationDropCount(storm, 1f, WIDTH, HEIGHT))
	}

	@Test
	fun `the floor is scalable rather than fixed`() {
		// 0.4 x base is the renderer's "any precipitation is visible" floor; the user's Subtle must be able to go under it.
		val storm = Precipitation(PrecipitationKind.RAIN, SEVERITY_STORM, observed = 0.8747f)

		val unscaled = precipitationDropCount(storm, 1f, WIDTH, HEIGHT)
		val subtle = precipitationDropCount(storm, 0.1f, WIDTH, HEIGHT)

		assertTrue("Subtle must fall well below the unscaled floor", subtle < unscaled / 2)
	}

	@Test
	fun `heavier weather still outdraws lighter weather at the same scale`() {
		// The scale is a preference, not a replacement for the observation: ordering by severity must survive it.
		val drizzle = Precipitation(PrecipitationKind.RAIN, SEVERITY_DRIZZLE, observed = 0.2512f)
		val storm = Precipitation(PrecipitationKind.RAIN, SEVERITY_STORM, observed = 0.8747f)

		assertTrue(precipitationDropCount(storm, 0.5f, WIDTH, HEIGHT) > precipitationDropCount(drizzle, 0.5f, WIDTH, HEIGHT))
	}

	@Test
	fun `a scale of 0f does not produce NaN or a negative count`() {
		// Negative or zero scales must be clamped at zero so pow never receives a negative base.
		val drizzle = Precipitation(PrecipitationKind.RAIN, SEVERITY_DRIZZLE, observed = 0.2512f)

		val zeroCount = precipitationDropCount(drizzle, 0f, WIDTH, HEIGHT)
		val negativeCount = precipitationDropCount(drizzle, -1f, WIDTH, HEIGHT)

		assertEquals(0, zeroCount)
		assertEquals(0, negativeCount)
		assertTrue(zeroCount >= 0)
		assertTrue(negativeCount >= 0)
	}

	@Test
	fun `the shaped scale is monotonic across the slider range`() {
		// A higher slider position must never yield fewer drops.
		val drizzle = Precipitation(PrecipitationKind.RAIN, SEVERITY_DRIZZLE, observed = 0.2512f)
		val sliderPositions = listOf(0f, 0.1f, 0.2f, 0.5f, 0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)
		val counts = sliderPositions.map {
			precipitationDropCount(drizzle, it, WIDTH, HEIGHT)
		}

		for (i in 0 until counts.size - 1) {
			assertTrue(
				"Drop count must be monotonic: ${counts[i + 1]} at slider ${sliderPositions[i + 1]} should be >= ${counts[i]} at slider ${sliderPositions[i]}",
				counts[i + 1] >= counts[i]
			)
		}
	}

	private companion object {
		const val WIDTH = 1080f
		const val HEIGHT = 2400f
	}
}
