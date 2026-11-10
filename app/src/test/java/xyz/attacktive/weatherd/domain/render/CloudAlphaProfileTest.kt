package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAlphaProfileTest {
	@Test
	fun transparentArtworkHasNoCloudOrCoveredBand() {
		val profile = CloudAlphaProfile.fromPixels(4, 6, IntArray(24))
		assertFalse(profile.hasCloud)
		assertTrue(profile.coveredTop.isNaN())
		assertTrue(profile.coveredBottom.isNaN())
		for (index in -40..40) {
			assertEquals(0f, profile.lowerEdgeAt(index / 10f), 0f)
		}
	}

	@Test
	fun bottomEdgeUsesAlphaRatherThanRgbOrImageRectangle() {
		val pixels = IntArray(16) { 0x00ffffff }
		pixels[4] = 0xff123456.toInt()
		pixels[9] = 0xff123456.toInt()
		pixels[14] = 0xff123456.toInt()
		val profile = CloudAlphaProfile.fromPixels(4, 4, pixels)
		assertTrue(profile.hasCloud)
		assertEquals(0.5f, profile.lowerEdgeAt(0.125f), 0f)
		assertEquals(0.75f, profile.lowerEdgeAt(0.375f), 0f)
		assertEquals(1f, profile.lowerEdgeAt(0.625f), 0f)
		assertEquals(0f, profile.lowerEdgeAt(0.875f), 0f)
	}

	@Test
	fun faintExportNoiseDoesNotExtendTheCloudBase() {
		val profile = CloudAlphaProfile.fromPixels(1, 4, intArrayOf(0, 24 shl 24, 23 shl 24, 0x00ffffff))
		assertEquals(0.5f, profile.lowerEdgeAt(0.5f), 0f)
	}

	@Test
	fun sourceColumnsInterpolateAndRepeatInBothDirections() {
		val profile = CloudAlphaProfile.fromPixels(2, 4, intArrayOf(-1, -1, -1, -1, 0, -1, 0, -1))
		assertEquals(0.5f, profile.lowerEdgeAt(0.25f), 0f)
		assertEquals(1f, profile.lowerEdgeAt(0.75f), 0f)
		assertEquals(0.75f, profile.lowerEdgeAt(0.5f), 0f)
		assertEquals(0.75f, profile.lowerEdgeAt(0f), 0f)
		for (index in -128..128) {
			val u = index / 64f
			assertEquals(profile.lowerEdgeAt(u), profile.lowerEdgeAt(u + 7f), 0.00001f)
			assertEquals(profile.lowerEdgeAt(u), profile.lowerEdgeAt(u - 7f), 0.00001f)
		}
	}

	@Test
	fun repeatBoundaryHasNoDiscontinuity() {
		val profile = CloudAlphaProfile.fromPixels(2, 4, intArrayOf(-1, -1, -1, -1, 0, -1, 0, -1))
		assertEquals(profile.lowerEdgeAt(-0.000001f), profile.lowerEdgeAt(0.000001f), 0.00001f)
	}

	@Test
	fun coverageUsesTheLongestContiguousFullyCoveredBand() {
		val pixels = IntArray(3 * 8)
		for (y in listOf(0, 2, 3, 4, 7)) {
			for (x in 0 until 3) {
				pixels[y * 3 + x] = 64 shl 24
			}
		}

		val profile = CloudAlphaProfile.fromPixels(3, 8, pixels)
		assertEquals(2.5f / 8, profile.coveredTop, 0f)
		assertEquals(4.5f / 8, profile.coveredBottom, 0f)
	}

	@Test
	fun aTransparentColumnPreventsFalseFullWidthCoverage() {
		val pixels = IntArray(4 * 8) { -1 }
		for (y in 0 until 8) {
			pixels[y * 4 + 2] = 0
		}

		val profile = CloudAlphaProfile.fromPixels(4, 8, pixels)
		assertTrue(profile.hasCloud)
		assertTrue(profile.coveredTop.isNaN())
		assertTrue(profile.coveredBottom.isNaN())
	}

	@Test
	fun singletonTextureIsPeriodicAndHasAValidCoverageBand() {
		val profile = CloudAlphaProfile.fromPixels(1, 1, intArrayOf(-1))
		assertEquals(0.5f, profile.coveredTop, 0f)
		assertEquals(0.5f, profile.coveredBottom, 0f)
		for (index in -20..20) {
			assertEquals(1f, profile.lowerEdgeAt(index / 7f), 0f)
		}
	}

	@Test
	fun profilesDoNotRetainTheMutablePixelBuffer() {
		val pixels = IntArray(16) { -1 }
		val profile = CloudAlphaProfile.fromPixels(4, 4, pixels)
		pixels.fill(0)
		assertEquals(1f, profile.lowerEdgeAt(0.5f), 0f)
		assertTrue(profile.hasCloud)
	}

	@Test
	fun invalidSamplingCoordinatesReturnNoCloud() {
		val profile = CloudAlphaProfile.fromPixels(1, 1, intArrayOf(-1))
		for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
			assertEquals(0f, profile.lowerEdgeAt(value), 0f)
		}
	}

	@Test
	fun inconsistentDimensionsAreRejectedWithoutOverflow() {
		for ((width, height, pixels) in listOf(Triple(0, 1, IntArray(0)), Triple(2, 2, IntArray(3)), Triple(-1, -1, IntArray(1)), Triple(Int.MAX_VALUE, Int.MAX_VALUE, IntArray(1)))) {
			assertTrue(runCatching { CloudAlphaProfile.fromPixels(width, height, pixels) }.exceptionOrNull() is IllegalArgumentException)
		}
	}
}
