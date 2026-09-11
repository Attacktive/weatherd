package xyz.attacktive.weatherd.domain.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoTargetLongEdgeTest {
	@Test
	fun `a display is stored at its own long edge, however small that is`() {
		// A 540x960 or 480x854 phone is an ordinary minSdk 26 device, and any floor above its own long edge would have the render thread decode pixels it can never show.
		assertEquals(2400, photoTargetLongEdge(1080, 2400))
		assertEquals(960, photoTargetLongEdge(540, 960))
		assertEquals(854, photoTargetLongEdge(480, 854))
	}

	@Test
	fun `the long edge decides, whichever side it is`() {
		// One stored file serves both orientations, so a display reported landscape must pick the same size as the same display reported portrait.
		assertEquals(photoTargetLongEdge(1080, 2400), photoTargetLongEdge(2400, 1080))
	}

	@Test
	fun `an absent metrics read falls back rather than scaling a photo to nothing`() {
		// Non-positive metrics are not a small display, they are no display; 1280 is the fallback the repository keeps for exactly that read.
		assertEquals(1280, photoTargetLongEdge(0, 0))
		assertEquals(1280, photoTargetLongEdge(-1, -1))
	}
}
