package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StormCloudRainGeometryTest {
	@Test
	fun cloudLiftDoesNotStretchOrChangeHorizontalMotion() {
		for ((width, height) in listOf(480f to 1056f, 800f to 1280f, 1056f to 480f)) {
			for (plane in StormCloudPlane.entries) {
				val geometry = geometry(width, height, plane)
				val depth = min(height * 0.62f, max(height * 0.48f, width * 0.70f))
				val previousTop = depth * plane.bottom - geometry.height
				assertEquals(height * 0.03f, previousTop - geometry.top, 0.0001f)
				assertEquals(2f, geometry.width / geometry.height, 0.0001f)
				val start = geometry.left
				val top = geometry.top
				geometry.configure(width, height, 1600, 800, plane, 1f, 0.5f, 1f, 1f)
				assertTrue(geometry.left > start)
				assertEquals(top, geometry.top, 0f)
			}
		}
	}

	@Test
	fun upperEdgeStaysInsideTheCoveredBandAcrossSizesAndOrientations() {
		val pixels = IntArray(4 * 8)
		for (index in 8 until 24) {
			pixels[index] = -1
		}

		val profile = CloudAlphaProfile.fromPixels(4, 8, pixels)
		for ((width, height) in listOf(480f to 1056f, 800f to 1280f, 1056f to 480f, 720f to 720f)) {
			for (size in listOf(0.5f, 1f, 2f)) {
				val geometry = StormCloudGeometry()
				assertTrue(geometry.configure(width, height, 1524, 344, StormCloudPlane.UPPER, 0f, 0.5f, 1f, size))
				val previousWidth = geometry.width
				val previousHeight = geometry.height
				val previousLeft = geometry.left
				geometry.coverTopEdge(profile)
				val sourceY = -geometry.top / geometry.height
				assertTrue(sourceY >= profile.coveredTop - 0.000001f && sourceY <= profile.coveredBottom + 0.000001f)
				assertEquals(previousWidth, geometry.width, 0f)
				assertEquals(previousHeight, geometry.height, 0f)
				assertEquals(previousLeft, geometry.left, 0f)
			}
		}
	}

	@Test
	fun missingCoverageCannotMoveTheUpperCloudToNan() {
		val geometry = geometry(480f, 1056f, StormCloudPlane.UPPER)
		val top = geometry.top
		geometry.coverTopEdge(CloudAlphaProfile.fromPixels(2, 2, IntArray(4)))
		assertEquals(top, geometry.top, 0f)
	}

	@Test
	fun stationaryWindAndRepeatedFramesKeepTheSameRainBoundary() {
		val profile = CloudAlphaProfile.fromPixels(2, 4, intArrayOf(-1, -1, -1, -1, 0, -1, 0, -1))
		val geometry = StormCloudGeometry()
		val boundary = CloudRainBoundary()
		var previous: FloatArray? = null
		for (time in listOf(0f, 0f, 30f, 600f)) {
			assertTrue(geometry.configure(480f, 1056f, 1600, 800, StormCloudPlane.HERO, time, 0.5f, 0f, 1f))
			boundary.reset(480f, 1056f)
			boundary.include(profile, geometry.left, geometry.top, geometry.width, geometry.height, 255)
			previous?.let { assertTrue(it.contentEquals(boundary.lowerEdges)) }
			previous = boundary.lowerEdges.copyOf()
		}
	}

	@Test
	fun liftedDefaultHeroRetainsExistingMidSkyCoverageContract() {
		for ((width, height) in listOf(480f to 1056f, 800f to 1280f, 1056f to 480f)) {
			val geometry = StormCloudGeometry()
			assertTrue(geometry.configure(width, height, 1782, 892, StormCloudPlane.HERO, 0f, 0.5f, 1f, 1f))
			assertTrue(geometry.top + geometry.height >= height * 0.42f)
			assertTrue(geometry.top + geometry.height <= height * if (width < height) 0.51f else 0.63f)
		}
	}

	@Test
	fun everyPlaneKeepsContinuousForwardMotionAcrossWraps() {
		for (plane in StormCloudPlane.entries) {
			val geometry = StormCloudGeometry()
			assertTrue(geometry.configure(480f, 1056f, 1600, 800, plane, 0f, 0.5f, 2f, 1f))
			var previous = geometry.left
			var wraps = 0
			for (frame in 1..54000) {
				geometry.configure(480f, 1056f, 1600, 800, plane, frame / 30f, 0.5f, 2f, 1f)
				val step = geometry.left - previous
				val unwrapped = if (step < -1f) {
					wraps++
					step + geometry.width
				} else {
					step
				}

				assertTrue(unwrapped >= -0.005f && abs(unwrapped) < 0.25f)
				previous = geometry.left
			}

			assertTrue(wraps > 0)
		}
	}

	private fun geometry(width: Float, height: Float, plane: StormCloudPlane) = StormCloudGeometry().apply {
		check(configure(width, height, 1600, 800, plane, 0f, 0.5f, 1f, 1f))
	}
}
