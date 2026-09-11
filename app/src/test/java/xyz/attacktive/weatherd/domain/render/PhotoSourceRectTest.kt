package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Rect's constructors, `set`, `width`, `height` and `equals` are stubbed out in the mockable android.jar these tests run against, so every assertion reads the four public fields directly.
 * That is also why the production code populates the rect field by field.
 */
class PhotoSourceRectTest {
	@Test
	fun `a landscape source on a portrait destination trims the sides and keeps the full height`() {
		val crop = photoSourceRect(4000, 3000, PHONE_WIDTH, PHONE_HEIGHT)

		assertEquals(0, crop.top)
		assertEquals(3000, crop.bottom)
		// 1080/2400 of 3000px tall is 1350px wide, leaving 1325px trimmed off each side.
		assertEquals(1325, crop.left)
		assertEquals(2675, crop.right)
	}

	@Test
	fun `a source taller than the destination trims the top and bottom and keeps the full width`() {
		val crop = photoSourceRect(1200, 2400, 1080f, 1920f)

		assertEquals(0, crop.left)
		assertEquals(1200, crop.right)
		// 1200px wide at 1080/1920 is 2133px tall, leaving 267px to split between top and bottom.
		assertEquals(133, crop.top)
		assertEquals(2266, crop.bottom)
	}

	@Test
	fun `an identical aspect ratio returns the whole source`() {
		val sameSize = photoSourceRect(1080, 2400, PHONE_WIDTH, PHONE_HEIGHT)
		val halfSize = photoSourceRect(1080, 2400, PHONE_WIDTH / 2f, PHONE_HEIGHT / 2f)

		for (crop in listOf(sameSize, halfSize)) {
			assertEquals(0, crop.left)
			assertEquals(0, crop.top)
			assertEquals(1080, crop.right)
			assertEquals(2400, crop.bottom)
		}
	}

	@Test
	fun `the crop is centered on the source`() {
		val wider = photoSourceRect(4000, 3000, 1000f, 1000f)
		val taller = photoSourceRect(3000, 4000, 1000f, 1000f)

		assertEquals(wider.left, 4000 - wider.right)
		assertEquals(0, wider.top)
		assertEquals(taller.top, 4000 - taller.bottom)
		assertEquals(0, taller.left)
	}

	@Test
	fun `the crop matches the destination's aspect ratio`() {
		// Any crop that does not is a stretched photo on screen.
		val wider = photoSourceRect(4000, 3000, PHONE_WIDTH, PHONE_HEIGHT)
		val taller = photoSourceRect(1200, 2400, 1920f, 1080f)

		assertEquals(PHONE_WIDTH / PHONE_HEIGHT, (wider.right - wider.left).toFloat() / (wider.bottom - wider.top).toFloat(), 0.001f)
		assertEquals(1920f / 1080f, (taller.right - taller.left).toFloat() / (taller.bottom - taller.top).toFloat(), 0.001f)
	}

	@Test
	fun `the crop never leaves the source and is never empty`() {
		val sources = listOf(4000 to 3000, 3000 to 4000, 1080 to 2400, 10000 to 1, 1 to 10000, 1 to 1)
		val destinations = listOf(PHONE_WIDTH to PHONE_HEIGHT, 2400f to 1080f, 1000f to 1000f, 4000f to 100f, 100f to 4000f)

		for ((sourceWidth, sourceHeight) in sources) {
			for ((destinationWidth, destinationHeight) in destinations) {
				val crop = photoSourceRect(sourceWidth, sourceHeight, destinationWidth, destinationHeight)
				val where = "${sourceWidth}x$sourceHeight onto ${destinationWidth}x$destinationHeight gave ${crop.left},${crop.top},${crop.right},${crop.bottom}"

				assertTrue(where, crop.left >= 0)
				assertTrue(where, crop.top >= 0)
				assertTrue(where, crop.right <= sourceWidth)
				assertTrue(where, crop.bottom <= sourceHeight)
				assertTrue(where, crop.right > crop.left)
				assertTrue(where, crop.bottom > crop.top)
			}
		}
	}

	@Test
	fun `a degenerate source or destination returns the whole source instead of dividing by zero`() {
		val noDestinationHeight = photoSourceRect(1080, 2400, PHONE_WIDTH, 0f)
		val noDestinationWidth = photoSourceRect(1080, 2400, 0f, PHONE_HEIGHT)
		val noSource = photoSourceRect(0, 0, PHONE_WIDTH, PHONE_HEIGHT)

		for (crop in listOf(noDestinationHeight, noDestinationWidth)) {
			assertEquals(0, crop.left)
			assertEquals(0, crop.top)
			assertEquals(1080, crop.right)
			assertEquals(2400, crop.bottom)
		}

		assertEquals(0, noSource.right)
		assertEquals(0, noSource.bottom)
	}

	private companion object {
		const val PHONE_WIDTH = 1080f
		const val PHONE_HEIGHT = 2400f
	}
}
