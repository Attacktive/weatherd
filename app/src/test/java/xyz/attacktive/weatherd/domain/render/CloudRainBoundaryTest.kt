package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRainBoundaryTest {
	private val fullCloud = CloudAlphaProfile.fromPixels(2, 2, IntArray(4) { -1 })
	private val unevenCloud = CloudAlphaProfile.fromPixels(2, 4, intArrayOf(-1, -1, -1, -1, 0, -1, 0, -1))

	@Test
	fun transformedLowerEdgeFollowsActualCloudGeometry() {
		val boundary = CloudRainBoundary()
		boundary.reset(400f, 800f)
		boundary.include(unevenCloud, 0f, 10f, 400f, 200f, 255)
		assertTrue(boundary.active)
		assertEquals(110f, boundary.lowerEdges[64], 0.0001f)
		assertEquals(210f, boundary.lowerEdges[192], 0.0001f)
	}

	@Test
	fun lowestOverlappingBankWinsIndependentlyOfDrawingOrder() {
		val first = CloudRainBoundary()
		val second = CloudRainBoundary()
		first.reset(400f, 800f)
		second.reset(400f, 800f)
		first.include(unevenCloud, 0f, 10f, 400f, 200f, 255)
		first.include(fullCloud, 0f, 0f, 400f, 160f, 255)
		second.include(fullCloud, 0f, 0f, 400f, 160f, 255)
		second.include(unevenCloud, 0f, 10f, 400f, 200f, 255)
		assertTrue(first.lowerEdges.contentEquals(second.lowerEdges))
		assertEquals(160f, first.lowerEdges[64], 0f)
		assertEquals(210f, first.lowerEdges[192], 0f)
	}

	@Test
	fun wrappedOffsetsUseTheSameProfileAsTheRepeatingShader() {
		val boundary = CloudRainBoundary()
		boundary.reset(400f, 800f)
		boundary.include(unevenCloud, -200f, 10f, 400f, 200f, 255)
		val edges = boundary.lowerEdges.copyOf()
		boundary.reset(400f, 800f)
		boundary.include(unevenCloud, 600f, 10f, 400f, 200f, 255)
		assertTrue(edges.contentEquals(boundary.lowerEdges))
	}

	@Test
	fun zeroOpacityAndTransparentArtworkCannotHideRain() {
		val boundary = CloudRainBoundary()
		boundary.reset(400f, 800f)
		boundary.include(fullCloud, 0f, 0f, 400f, 200f, 0)
		boundary.include(CloudAlphaProfile.fromPixels(2, 2, IntArray(4)), 0f, 0f, 400f, 200f, 255)
		assertFalse(boundary.active)
		assertTrue(boundary.lowerEdges.all { it == 0f })
	}

	@Test
	fun offscreenBanksCannotSuppressVisibleRain() {
		val boundary = CloudRainBoundary()
		boundary.reset(400f, 800f)
		boundary.include(fullCloud, 0f, -200f, 400f, 200f, 255)
		boundary.include(fullCloud, 0f, 800f, 400f, 200f, 255)
		assertFalse(boundary.active)
	}

	@Test
	fun fullyCoveredLandscapeAtMaximumCloudSizeKeepsForegroundRainUnclipped() {
		val boundary = CloudRainBoundary()
		boundary.reset(528f, 240f)
		boundary.include(fullCloud, 0f, -240f, 528f, 480f, 255)
		assertTrue(boundary.active)
		assertTrue(boundary.lowerEdges.all { it == 240f })
		assertFalse(boundary.hasVisibleAreaBelowClouds())
	}

	@Test
	fun visibleCloudBaseStillClipsRainBelowItsSilhouette() {
		val boundary = CloudRainBoundary()
		boundary.reset(528f, 240f)
		boundary.include(fullCloud, 0f, 0f, 528f, 180f, 255)
		assertTrue(boundary.hasVisibleAreaBelowClouds())
	}

	@Test
	fun cloudsExtendingPastTheScreenClampToItsBottom() {
		val boundary = CloudRainBoundary()
		boundary.reset(400f, 800f)
		boundary.include(fullCloud, 0f, 100f, 400f, 900f, 255)
		assertTrue(boundary.active)
		assertTrue(boundary.lowerEdges.all { it == 800f })
	}

	@Test
	fun resettingReusesStorageAndClearsThePreviousScene() {
		val boundary = CloudRainBoundary()
		val originalArray = boundary.lowerEdges
		boundary.reset(400f, 800f)
		boundary.include(fullCloud, 0f, 0f, 400f, 200f, 255)
		assertTrue(boundary.active)
		boundary.reset(800f, 400f)
		assertFalse(boundary.active)
		assertSame(originalArray, boundary.lowerEdges)
		assertTrue(boundary.lowerEdges.all { it == 0f })
		assertEquals(800f, boundary.width, 0f)
		assertEquals(400f, boundary.height, 0f)
	}

	@Test
	fun orientationAndCloudSizeChangesDoNotReuseStaleCoordinates() {
		val boundary = CloudRainBoundary()
		for ((width, height) in listOf(480f to 1056f, 1056f to 480f, 800f to 1280f)) {
			for (scale in listOf(0.5f, 1f, 2f)) {
				boundary.reset(width, height)
				boundary.include(fullCloud, 0f, 0f, width, height * 0.2f * scale, 255)
				assertTrue(boundary.lowerEdges.all { kotlin.math.abs(it - height * 0.2f * scale) < 0.001f })
			}
		}
	}

	@Test
	fun invalidViewportAndCloudGeometryCannotProduceANanPath() {
		val boundary = CloudRainBoundary()
		for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
			boundary.reset(invalid, 800f)
			boundary.include(fullCloud, 0f, 0f, 400f, 200f, 255)
			assertFalse(boundary.active)
			boundary.reset(400f, invalid)
			boundary.include(fullCloud, 0f, 0f, 400f, 200f, 255)
			assertFalse(boundary.active)
			boundary.reset(400f, 800f)
			boundary.include(fullCloud, 0f, 0f, invalid, 200f, 255)
			boundary.include(fullCloud, 0f, 0f, 400f, invalid, 255)
			assertFalse(boundary.active)
			assertTrue(boundary.lowerEdges.all { it.isFinite() })
		}
	}

	@Test
	fun nonfiniteOffsetsCannotPoisonTheNextBank() {
		val boundary = CloudRainBoundary()
		boundary.reset(400f, 800f)
		boundary.include(fullCloud, Float.NaN, 0f, 400f, 200f, 255)
		boundary.include(fullCloud, 0f, Float.POSITIVE_INFINITY, 400f, 200f, 255)
		assertFalse(boundary.active)
		boundary.include(fullCloud, 0f, 10f, 400f, 200f, 255)
		assertTrue(boundary.lowerEdges.all { it == 210f })
	}
}
