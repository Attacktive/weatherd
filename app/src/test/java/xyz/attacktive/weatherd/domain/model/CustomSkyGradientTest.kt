package xyz.attacktive.weatherd.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSkyGradientTest {
	@Test
	fun `reversed swaps start and end colors while keeping middle and positions intact`() {
		val gradient = CustomSkyGradient(
			startColor = 0xFF112233.toInt(),
			middleColor = 0xFF445566.toInt(),
			endColor = 0xFF778899.toInt(),
			threeColors = true,
			startPosition = 0.1f,
			middlePosition = 0.4f,
			endPosition = 0.9f,
			direction = SkyGradientDirection.DIAGONAL
		)

		val reversed = gradient.reversed()

		assertEquals(0xFF778899.toInt(), reversed.startColor)
		assertEquals(0xFF445566.toInt(), reversed.middleColor)
		assertEquals(0xFF112233.toInt(), reversed.endColor)
		assertTrue(reversed.threeColors)
		assertEquals(0.1f, reversed.startPosition, 0.0001f)
		assertEquals(0.4f, reversed.middlePosition, 0.0001f)
		assertEquals(0.9f, reversed.endPosition, 0.0001f)
		assertEquals(SkyGradientDirection.DIAGONAL, reversed.direction)
	}

	@Test
	fun `normalized forces full opacity on all color stops`() {
		val gradient = CustomSkyGradient(
			startColor = 0x0032C5F4,
			middleColor = 0x7FABEBFA.toInt(),
			endColor = 0x00000000
		)

		val normalized = gradient.normalized()

		assertEquals(0xFF32C5F4.toInt(), normalized.startColor)
		assertEquals(0xFFABEBFA.toInt(), normalized.middleColor)
		assertEquals(0xFF000000.toInt(), normalized.endColor)
	}

	@Test
	fun `normalized clamps positions and restores defaults for non-finite values`() {
		val nonFinite = CustomSkyGradient(
			startPosition = Float.NaN,
			middlePosition = Float.POSITIVE_INFINITY,
			endPosition = Float.NEGATIVE_INFINITY
		).normalized()

		assertEquals(0f, nonFinite.startPosition, 0.0001f)
		assertEquals(0.5f, nonFinite.middlePosition, 0.0001f)
		assertEquals(1f, nonFinite.endPosition, 0.0001f)

		val outOfRange = CustomSkyGradient(
			startPosition = -0.5f,
			middlePosition = 0.5f,
			endPosition = 1.5f
		).normalized()

		assertEquals(0f, outOfRange.startPosition, 0.0001f)
		assertEquals(0.5f, outOfRange.middlePosition, 0.0001f)
		assertEquals(1f, outOfRange.endPosition, 0.0001f)
	}

	@Test
	fun `normalized enforces start and middle and end ordering in three-color mode`() {
		val inverted = CustomSkyGradient(
			threeColors = true,
			startPosition = 0.8f,
			middlePosition = 0.3f,
			endPosition = 0.4f
		).normalized()

		assertEquals(0.8f, inverted.startPosition, 0.0001f)
		assertEquals(0.8f, inverted.middlePosition, 0.0001f)
		assertEquals(0.8f, inverted.endPosition, 0.0001f)

		val middleBeforeStart = CustomSkyGradient(
			threeColors = true,
			startPosition = 0.6f,
			middlePosition = 0.2f,
			endPosition = 0.9f
		).normalized()

		assertEquals(0.6f, middleBeforeStart.startPosition, 0.0001f)
		assertEquals(0.6f, middleBeforeStart.middlePosition, 0.0001f)
		assertEquals(0.9f, middleBeforeStart.endPosition, 0.0001f)

		val endBeforeMiddle = CustomSkyGradient(
			threeColors = true,
			startPosition = 0.2f,
			middlePosition = 0.7f,
			endPosition = 0.5f
		).normalized()

		assertEquals(0.2f, endBeforeMiddle.startPosition, 0.0001f)
		assertEquals(0.7f, endBeforeMiddle.middlePosition, 0.0001f)
		assertEquals(0.7f, endBeforeMiddle.endPosition, 0.0001f)
	}

	@Test
	fun `normalized preserves middle color and position when two-color mode is selected`() {
		val twoColors = CustomSkyGradient(
			threeColors = false,
			startColor = 0xFF111111.toInt(),
			middleColor = 0xFF222222.toInt(),
			endColor = 0xFF333333.toInt(),
			startPosition = 0.4f,
			middlePosition = 0.2f,
			endPosition = 0.8f
		).normalized()

		assertEquals(0xFF222222.toInt(), twoColors.middleColor)
		assertEquals(0.2f, twoColors.middlePosition, 0.0001f)
		assertEquals(0.4f, twoColors.startPosition, 0.0001f)
		assertEquals(0.8f, twoColors.endPosition, 0.0001f)
	}

	@Test
	fun `normalized enforces start before or equal to end in two-color mode`() {
		val invertedTwoColors = CustomSkyGradient(
			threeColors = false,
			startPosition = 0.7f,
			endPosition = 0.3f
		).normalized()

		assertEquals(0.7f, invertedTwoColors.startPosition, 0.0001f)
		assertEquals(0.7f, invertedTwoColors.endPosition, 0.0001f)
	}

	@Test
	fun `duplicate stops are allowed without error`() {
		val duplicateStops = CustomSkyGradient(
			threeColors = true,
			startPosition = 0.5f,
			middlePosition = 0.5f,
			endPosition = 0.5f
		).normalized()

		assertEquals(0.5f, duplicateStops.startPosition, 0.0001f)
		assertEquals(0.5f, duplicateStops.middlePosition, 0.0001f)
		assertEquals(0.5f, duplicateStops.endPosition, 0.0001f)
	}

	@Test
	fun `normalization is idempotent`() {
		val arbitrary = CustomSkyGradient(
			startColor = 0x55123456,
			middleColor = 0x88ABCDEF.toInt(),
			endColor = 0x11FEDCBA,
			threeColors = true,
			startPosition = 0.9f,
			middlePosition = 0.3f,
			endPosition = 0.1f
		)

		val firstNormalized = arbitrary.normalized()
		val secondNormalized = firstNormalized.normalized()

		assertEquals(firstNormalized, secondNormalized)
	}
}
