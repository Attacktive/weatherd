package xyz.attacktive.weatherd.domain.render

import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StormCloudSizeLimitTest {
	private val viewports = listOf(480f to 1056f, 800f to 1280f, 1056f to 480f, 720f to 720f)
	private val profile = CloudAlphaProfile.fromPixels(2, 4, intArrayOf(-1, -1, -1, -1, 0, -1, 0, -1))

	@Test
	fun oversizedInputsMatchTheMaximumForEveryPlaneAndViewport() {
		for ((width, height) in viewports) {
			for (plane in StormCloudPlane.entries) {
				for (size in listOf(1.5001f, 2f, 10f)) {
					val maximum = StormCloudGeometry()
					val oversized = StormCloudGeometry()
					for (time in listOf(0f, 30f, 600f)) {
						assertTrue(maximum.configure(width, height, 1600, 800, plane, time, 0.5f, 1f, 1.5f))
						assertTrue(oversized.configure(width, height, 1600, 800, plane, time, 0.5f, 1f, size))
						assertSameGeometry(maximum, oversized)
					}
				}
			}
		}
	}

	@Test
	fun validSizesKeepTheExistingAnchorAndUniformScale() {
		for ((width, height) in viewports) {
			for (plane in StormCloudPlane.entries) {
				for (size in listOf(0.5f, 1f, 1.25f, 1.5f)) {
					val geometry = StormCloudGeometry()
					assertTrue(geometry.configure(width, height, 1600, 800, plane, 0f, 0.5f, 1f, size))
					val depth = min(height * 0.62f, max(height * 0.48f, width * 0.70f))
					val expectedHeight = max(width * plane.widthScale / 2f, depth * plane.depthScale) * size
					assertEquals(expectedHeight, geometry.height, 0f)
					assertEquals(expectedHeight * 2f, geometry.width, 0f)
					assertEquals(depth * size * plane.bottom - expectedHeight - height * 0.15f, geometry.top, 0f)
				}
			}
		}
	}

	@Test
	fun legacySizeCannotLowerTheRainBoundaryPastTheMaximum() {
		for ((width, height) in viewports) {
			val maximum = boundary(width, height, 1.5f)
			val legacy = boundary(width, height, 2f)
			assertTrue(maximum.active)
			assertTrue(maximum.hasVisibleAreaBelowClouds())
			assertTrue(maximum.lowerEdges.contentEquals(legacy.lowerEdges))
		}
	}

	@Test
	fun upperBankRetainsItsCoveredTopEdgeAtBothLimits() {
		for ((width, height) in viewports) {
			for (size in listOf(0.5f, 1f, 1.25f, 1.5f, 2f)) {
				val geometry = StormCloudGeometry()
				assertTrue(geometry.configure(width, height, 1524, 344, StormCloudPlane.UPPER, 0f, 0.5f, 1f, size))
				val originalWidth = geometry.width
				val originalHeight = geometry.height
				geometry.coverTopEdge(profile)
				val sourceY = -geometry.top / geometry.height
				assertTrue(sourceY >= profile.coveredTop - 0.000001f)
				assertTrue(sourceY <= profile.coveredBottom + 0.000001f)
				assertEquals(originalWidth, geometry.width, 0f)
				assertEquals(originalHeight, geometry.height, 0f)
			}
		}
	}

	@Test
	fun nonFiniteSizesStillUseDefaultGeometry() {
		for (plane in StormCloudPlane.entries) {
			val expected = StormCloudGeometry()
			assertTrue(expected.configure(480f, 1056f, 1600, 800, plane, 0f, 0.5f, 1f, 1f))
			for (size in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
				val actual = StormCloudGeometry()
				assertTrue(actual.configure(480f, 1056f, 1600, 800, plane, 0f, 0.5f, 1f, size))
				assertSameGeometry(expected, actual)
			}
		}
	}

	private fun boundary(width: Float, height: Float, size: Float) = CloudRainBoundary().apply {
		reset(width, height)
		for (plane in listOf(StormCloudPlane.HERO, StormCloudPlane.DISTANT)) {
			val geometry = StormCloudGeometry()
			check(geometry.configure(width, height, 1600, 800, plane, 0f, 0.5f, 1f, size))
			include(profile, geometry.left, geometry.top, geometry.width, geometry.height, 255)
		}
	}

	private fun assertSameGeometry(expected: StormCloudGeometry, actual: StormCloudGeometry) {
		assertEquals(expected.left, actual.left, 0f)
		assertEquals(expected.top, actual.top, 0f)
		assertEquals(expected.width, actual.width, 0f)
		assertEquals(expected.height, actual.height, 0f)
	}
}
