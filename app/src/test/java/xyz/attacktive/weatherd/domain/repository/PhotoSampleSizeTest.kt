package xyz.attacktive.weatherd.domain.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSampleSizeTest {
	@Test
	fun `a source already the target size is decoded whole`() {
		// Downsampling here would throw away pixels the display is about to ask for.
		assertEquals(1, photoSampleSize(1080, 2400, 2400))
	}

	@Test
	fun `a source twice the target halves`() {
		// 4800 sampled by 2 lands exactly on 2400, so the scale step afterwards has nothing left to do.
		assertEquals(2, photoSampleSize(2160, 4800, 2400))
	}

	@Test
	fun `the long edge decides, whichever side it is`() {
		// A landscape pick and its portrait transpose must sample identically, because one stored file serves both orientations.
		assertEquals(photoSampleSize(2160, 4800, 2400), photoSampleSize(4800, 2160, 2400))
	}

	@Test
	fun `a ratio between two powers of two takes the smaller one`() {
		// Three times the target: sampling by 4 would decode 1600, under the display, and the import never upscales — that softness would be stored forever.
		assertEquals(2, photoSampleSize(3240, 7200, 2400))
	}

	@Test
	fun `a sampled source never lands under the target`() {
		// The invariant behind every ratio case, checked across the awkward sizes a real camera produces.
		// Starts at the target itself: anything below it is the no-upscale case and is covered separately.
		val target = 2400
		val longEdges = listOf(2400, 2401, 3000, 4799, 4800, 4801, 7200, 9600, 12000, 19200)

		for (longEdge in longEdges) {
			val sampled = longEdge / photoSampleSize(longEdge / 2, longEdge, target)

			assertTrue("A source long edge of $longEdge must not sample below $target", sampled >= target)
		}
	}

	@Test
	fun `a source smaller than the target is never upscaled`() {
		// A modest pick stays exactly as modest as it was; inventing pixels for it would only cost storage.
		assertEquals(1, photoSampleSize(800, 1200, 2400))
	}

	@Test
	fun `a zero dimension does not divide by zero`() {
		// BitmapFactory reports 0 or -1 for data it cannot read at all, and that path must reach the full decode to fail there rather than here.
		assertEquals(1, photoSampleSize(0, 0, 2400))
		assertEquals(1, photoSampleSize(0, 4800, 2400))
		assertEquals(1, photoSampleSize(-1, -1, 2400))
	}

	@Test
	fun `a degenerate target does not divide by zero`() {
		// Display metrics should never be empty, but a sample size derived from zero would be an infinite loop rather than a bad photo.
		assertEquals(1, photoSampleSize(2160, 4800, 0))
		assertEquals(1, photoSampleSize(2160, 4800, -2400))
	}
}
