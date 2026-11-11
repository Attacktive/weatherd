package xyz.attacktive.weatherd.domain.render

import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RainCloudLiftTest {
	private val viewports = listOf(480f to 1056f, 800f to 1280f, 1056f to 480f, 720f to 720f)
	private val unevenCloud = CloudAlphaProfile.fromPixels(2, 4, intArrayOf(-1, -1, -1, -1, 0, -1, 0, -1))

	@Test
	fun allPlanesRiseByTheSameViewportFractionWithoutResizing() {
		for ((width, height) in viewports) {
			for (size in listOf(0.5f, 1f, 2f)) {
				for (plane in StormCloudPlane.entries) {
					val geometry = StormCloudGeometry()
					assertTrue(geometry.configure(width, height, 1600, 800, plane, 0f, 0.5f, 1f, size))
					val depth = min(height * 0.62f, max(height * 0.48f, width * 0.70f))
					val expectedHeight = max(width * plane.widthScale / 2f, depth * plane.depthScale) * size
					val previousTop = depth * size * plane.bottom - expectedHeight - height * 0.03f
					assertEquals(expectedHeight, geometry.height, 0f)
					assertEquals(expectedHeight * 2f, geometry.width, 0f)
					assertEquals(height * 0.12f, previousTop - geometry.top, 0.001f)
				}
			}
		}
	}

	@Test
	fun rainBoundaryTracksTheRaisedSilhouetteAcrossSizesAndDrift() {
		val previous = CloudRainBoundary()
		val raised = CloudRainBoundary()
		for ((width, height) in viewports) {
			for (size in listOf(0.5f, 1f, 2f)) {
				for (plane in listOf(StormCloudPlane.HERO, StormCloudPlane.DISTANT)) {
					val geometry = StormCloudGeometry()
					for (time in listOf(0f, 30f, 600f)) {
						assertTrue(geometry.configure(width, height, 1600, 800, plane, time, 0.5f, 1f, size))
						val depth = min(height * 0.62f, max(height * 0.48f, width * 0.70f))
						val previousTop = depth * size * plane.bottom - geometry.height - height * 0.03f
						previous.reset(width, height)
						previous.include(unevenCloud, geometry.left, previousTop, geometry.width, geometry.height, 255)
						raised.reset(width, height)
						raised.include(unevenCloud, geometry.left, geometry.top, geometry.width, geometry.height, 255)
						for (index in raised.lowerEdges.indices) {
							val x = width * index / (raised.lowerEdges.size - 1)
							val sourceEdge = unevenCloud.lowerEdgeAt((x - geometry.left) / geometry.width)
							val expected = (previousTop + sourceEdge * geometry.height - height * 0.12f).coerceIn(0f, height)
							assertEquals(expected, raised.lowerEdges[index], 0.001f)
							assertTrue(raised.lowerEdges[index] <= previous.lowerEdges[index] + 0.001f)
						}

						if (size == 1f) {
							assertTrue(raised.lowerEdges.indices.any { raised.lowerEdges[it] < previous.lowerEdges[it] - 1f })
						}
					}
				}
			}
		}
	}

	@Test
	fun doubledViewportKeepsNormalizedCloudAndRainPlacement() {
		for ((width, height) in viewports) {
			for (plane in StormCloudPlane.entries) {
				val original = StormCloudGeometry()
				val doubled = StormCloudGeometry()
				val originalBoundary = CloudRainBoundary()
				val doubledBoundary = CloudRainBoundary()
				for (time in listOf(0f, 30f, 600f)) {
					assertTrue(original.configure(width, height, 1600, 800, plane, time, 0.5f, 1f, 1f))
					assertTrue(doubled.configure(width * 2f, height * 2f, 1600, 800, plane, time, 0.5f, 1f, 1f))
					assertEquals(original.left * 2f, doubled.left, 0.001f)
					assertEquals(original.top * 2f, doubled.top, 0.001f)
					assertEquals(original.width * 2f, doubled.width, 0.001f)
					assertEquals(original.height * 2f, doubled.height, 0.001f)
					originalBoundary.reset(width, height)
					originalBoundary.include(unevenCloud, original.left, original.top, original.width, original.height, 255)
					doubledBoundary.reset(width * 2f, height * 2f)
					doubledBoundary.include(unevenCloud, doubled.left, doubled.top, doubled.width, doubled.height, 255)
					for (index in originalBoundary.lowerEdges.indices) {
						assertEquals(originalBoundary.lowerEdges[index] * 2f, doubledBoundary.lowerEdges[index], 0.001f)
					}
				}
			}
		}
	}
}
