package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecipitationHorizontalPositionTest {
	@Test
	fun `at slant zero the position is exactly the lane position unchanged regardless of fall distance`() {
		val width = 1080f
		val band = width + PRECIPITATION_HORIZONTAL_PAD * 2f
		val testFractions = listOf(0f, 0.1f, 0.35f, 0.5f, 0.72f, 0.95f)
		val testFalls = listOf(0f, 50f, 300f, 1200f, 2500f)

		for (fraction in testFractions) {
			val expectedLanePosition = fraction * band - PRECIPITATION_HORIZONTAL_PAD

			for (fall in testFalls) {
				val actualPosition = precipitationHorizontalPosition(fraction, fall, 0f, width)

				assertEquals("At zero slant, horizontal position must match lane position exactly", expectedLanePosition, actualPosition, 0.0001f)
			}
		}
	}

	@Test
	fun `horizontal position advances with distance fallen at the slants ratio`() {
		val width = 1080f
		val laneFraction = 0.1f
		val slant = 0.65f
		val fall1 = 40f
		val fall2 = 280f

		val x1 = precipitationHorizontalPosition(laneFraction, fall1, slant, width)
		val x2 = precipitationHorizontalPosition(laneFraction, fall2, slant, width)

		// Before wrapping, the horizontal delta must match the vertical delta multiplied by slant.
		val horizontalDelta = x2 - x1
		val verticalDelta = fall2 - fall1
		val observedRatio = horizontalDelta / verticalDelta

		assertEquals(slant, observedRatio, 0.0001f)
		assertEquals(verticalDelta * slant, horizontalDelta, 0.0001f)
	}

	@Test
	fun `the result stays inside the band for any fall distance and any slant up to MAX_WIND_SLANT`() {
		val width = 1080f
		val minBound = -PRECIPITATION_HORIZONTAL_PAD
		val maxBound = width + PRECIPITATION_HORIZONTAL_PAD

		// Dense sampling over multiple fall cycles, slants up to MAX_WIND_SLANT, and lane fractions.
		for (laneStep in 0..20) {
			val fraction = laneStep / 20f

			for (slantStep in 0..14) {
				val slant = slantStep * 0.1f

				for (fallStep in 0..50) {
					val fall = fallStep * 100f
					val x = precipitationHorizontalPosition(fraction, fall, slant, width)

					assertTrue("Position $x must be >= $minBound", x >= minBound)
					assertTrue("Position $x must be < $maxBound", x < maxBound)
				}
			}
		}
	}

	@Test
	fun `particles wrap smoothly across the boundary without tearing`() {
		val width = 1080f
		val band = width + PRECIPITATION_HORIZONTAL_PAD * 2f
		val slant = 1.0f

		// Set up a drop right at the right edge boundary just before wrapping.
		val fraction = 0f
		val justBeforeWrapFall = band - 0.01f
		val justAfterWrapFall = band + 0.01f

		val xBefore = precipitationHorizontalPosition(fraction, justBeforeWrapFall, slant, width)
		val xAfter = precipitationHorizontalPosition(fraction, justAfterWrapFall, slant, width)

		assertEquals(width + PRECIPITATION_HORIZONTAL_PAD - 0.01f, xBefore, 0.01f)
		assertEquals(-PRECIPITATION_HORIZONTAL_PAD + 0.01f, xAfter, 0.01f)

		// The distance between the exit point and entry point equals the band width.
		assertEquals(band, (xBefore - xAfter) + 0.02f, 0.01f)
	}

	@Test
	fun `particle distribution across lanes remains uniform under non-zero wind`() {
		val width = 1080f
		val band = width + PRECIPITATION_HORIZONTAL_PAD * 2f
		val sampleCount = 100
		val slant = 0.85f
		val fall = 1450f

		// Take uniformly spaced particles in lane fraction.
		val positions = (0 until sampleCount).map { i ->
			val fraction = i.toFloat() / sampleCount
			val x = precipitationHorizontalPosition(fraction, fall, slant, width)

			// Map to periodic interval [0, band).
			(x + PRECIPITATION_HORIZONTAL_PAD) % band
		}.sorted()

		// Periodic distance between consecutive particles should be strictly uniform.
		val expectedGap = band / sampleCount
		for (i in 0 until sampleCount - 1) {
			val gap = positions[i + 1] - positions[i]

			assertEquals(expectedGap, gap, 0.01f)
		}
	}
}
