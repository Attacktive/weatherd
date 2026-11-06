package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StormCloudMotionTest {
	@Test
	fun keepsSourceAspectInBothOrientations() {
		for ((width, height) in listOf(480f to 1056f, 1056f to 480f)) {
			for (plane in StormCloudPlane.entries) {
				val geometry = StormCloudGeometry()
				assertTrue(geometry.configure(width, height, 1774, 887, plane, 17f, 0.5f, 1f, 1f))
				assertEquals(2f, geometry.width / geometry.height, 0.0001f)
			}
		}
	}

	@Test
	fun zeroWindFreezesEachLayer() {
		for (plane in StormCloudPlane.entries) {
			val geometry = geometry(plane, 0f, windScale = 0f)
			val initial = geometry.left
			geometry.configure(480f, 1056f, 1600, 800, plane, 600f, 0.5f, 0f, 1f)
			assertEquals(initial, geometry.left, 0f)
		}
	}

	@Test
	fun layersDriftIndependentlyWithoutVerticalBobOrPulsing() {
		val deltas = StormCloudPlane.entries.map { plane ->
			val initial = geometry(plane, 0f)
			val later = geometry(plane, 30f)
			assertEquals(initial.top, later.top, 0f)
			assertEquals(initial.width, later.width, 0f)
			assertEquals(initial.height, later.height, 0f)
			later.left - initial.left
		}

		assertTrue(deltas.all { abs(it) > 0.1f })
		assertEquals(4, deltas.map { (it * 100f).toInt() }.distinct().size)
	}

	@Test
	fun wrapsContinuouslyWithoutReversing() {
		for (plane in StormCloudPlane.entries) {
			val geometry = geometry(plane, 0f, windScale = 2f)
			var previous = geometry.left
			var wraps = 0
			for (frame in 1..54000) {
				assertTrue(geometry.configure(480f, 1056f, 1600, 800, plane, frame / 30f, 0.5f, 2f, 1f))
				val step = geometry.left - previous
				val unwrappedStep = if (step < -1f) {
					wraps++
					step + geometry.width
				} else {
					step
				}

				assertTrue("$plane must keep moving with the rain", unwrappedStep >= -0.005f)
				assertTrue("$plane must not jump at the wrap boundary", unwrappedStep < 0.25f)
				previous = geometry.left
			}

			assertTrue("$plane must eventually wrap", wraps > 0)
		}
	}

	@Test
	fun stormCloudsDriftWithTheRainRatherThanAgainstIt() {
		val slant = rainSlant(0.5f, 2f, 0.65f)
		val rainStart = precipitationHorizontalPosition(0.5f, 0f, slant, 480f)
		val rainLater = precipitationHorizontalPosition(0.5f, 10f, slant, 480f)
		assertTrue("Rain is blown toward positive X", rainLater > rainStart)

		for (plane in StormCloudPlane.entries) {
			val geometry = geometry(plane, 0f, windScale = 2f)
			val start = geometry.left
			assertTrue(geometry.configure(480f, 1056f, 1600, 800, plane, 1f, 0.5f, 2f, 1f))
			assertTrue("$plane must drift in the same direction as rain", geometry.left > start)
		}
	}

	@Test
	fun maximumWindMakesCloudMotionNoticeablyFaster() {
		for (plane in StormCloudPlane.entries) {
			val normalStart = geometry(plane, 0f, windScale = 1f)
			val normalLater = geometry(plane, 1f, windScale = 1f)
			val strongStart = geometry(plane, 0f, windScale = 2f)
			val strongLater = geometry(plane, 1f, windScale = 2f)
			val normalTravel = abs(normalLater.left - normalStart.left)
			val strongTravel = abs(strongLater.left - strongStart.left)

			assertTrue("$plane should respond visibly to maximum wind", strongTravel > normalTravel * 4f)
		}
	}

	@Test
	fun changingWindIntensityDoesNotTeleportTheClouds() {
		for (plane in StormCloudPlane.entries) {
			val geometry = geometry(plane, 20f)
			val previous = geometry.left
			assertTrue(geometry.configure(480f, 1056f, 1600, 800, plane, 20f, 0.5f, 2f, 1f))
			assertEquals(previous, geometry.left, 0f)
			assertTrue(geometry.configure(480f, 1056f, 1600, 800, plane, 21f, 0.5f, 2f, 1f))
			assertTrue("$plane should continue drifting right", geometry.left > previous)
		}
	}

	@Test
	fun rejectsInvalidDimensionsAndSanitizesOtherNonfiniteInputs() {
		val geometry = StormCloudGeometry()
		assertFalse(geometry.configure(0f, 100f, 100, 100, StormCloudPlane.HERO, 0f, 0f, 1f, 1f))
		assertFalse(geometry.configure(Float.NaN, 100f, 100, 100, StormCloudPlane.HERO, 0f, 0f, 1f, 1f))
		assertFalse(geometry.configure(100f, 100f, 100, 0, StormCloudPlane.HERO, 0f, 0f, 1f, 1f))
		assertTrue(geometry.configure(480f, 1056f, 1600, 800, StormCloudPlane.HERO, Float.NaN, Float.NaN, Float.NaN, Float.NaN))
		assertTrue(listOf(geometry.left, geometry.top, geometry.width, geometry.height).all { it.isFinite() })
	}

	@Test
	fun sizeControlRemainsUniform() {
		val normal = geometry(StormCloudPlane.HERO, 0f)
		val enlarged = StormCloudGeometry()
		enlarged.configure(480f, 1056f, 1600, 800, StormCloudPlane.HERO, 0f, 0.5f, 1f, 1.5f)
		assertEquals(normal.width * 1.5f, enlarged.width, 0.001f)
		assertEquals(normal.height * 1.5f, enlarged.height, 0.001f)
	}

	@Test
	fun actualAssetPlanesOverlapAcrossViewportsAndCloudSizes() {
		for ((width, height) in listOf(480f to 1056f, 800f to 1280f, 1056f to 480f, 720f to 720f)) {
			for (size in listOf(0.5f, 1f, 2f)) {
				val veil = assetGeometry(StormCloudPlane.VEIL, width, height, size)
				val upper = assetGeometry(StormCloudPlane.UPPER, width, height, size)
				val hero = assetGeometry(StormCloudPlane.HERO, width, height, size)
				val distant = assetGeometry(StormCloudPlane.DISTANT, width, height, size)
				assertTrue("Upper bank must meet the screen edge", upper.top < 0f)
				assertTrue("Upper and central banks must overlap", hero.top < upper.top + upper.height)
				assertTrue("Atmospheric veil must bridge the bottom of the hero", veil.top < hero.top + hero.height && veil.top + veil.height > hero.top + hero.height)
				assertTrue("Distant bank must not form a detached row", distant.top < hero.top + hero.height)
				assertTrue("Distant bank must overlap the central cloud lower edge", distant.top < hero.top + hero.height * 0.8f)
				assertTrue("Distant bank must reach the central cloud lower region", distant.top + distant.height > hero.top + hero.height * 0.75f)
			}
		}
	}

	@Test
	fun thinnerHeroAndFeatheringVeilLeaveDepthForTheLowerAtmosphere() {
		for ((width, height) in listOf(480f to 1056f, 800f to 1280f, 1056f to 480f)) {
			val hero = assetGeometry(StormCloudPlane.HERO, width, height, 1f)
			val distant = assetGeometry(StormCloudPlane.DISTANT, width, height, 1f)
			val veil = assetGeometry(StormCloudPlane.VEIL, width, height, 1f)

			assertTrue("Distant strip must overlap the hero lower edge", distant.top < hero.top + hero.height)
			assertTrue("The translucent veil must continue below the detailed cloud bank", veil.top + veil.height > hero.top + hero.height + height * 0.08f)
			assertTrue("The veil must not fill the entire screen", veil.top + veil.height < height * 0.85f)
		}
	}

	@Test
	fun seamlessCloudSamplingCoversTheViewportWhileScrolling() {
		for ((width, height) in listOf(480f to 1056f, 800f to 1280f, 1056f to 480f)) {
			for (plane in StormCloudPlane.entries) {
				val initial = assetGeometry(plane, width, height, 1f).left
				for (time in listOf(0f, 30f, 120f, 600f, 3600f)) {
					val geometry = assetGeometry(plane, width, height, 1f, time)
					assertTrue("$plane must stay at least a viewport wide", geometry.width >= width)
					assertTrue("$plane cannot reverse past its initial anchor", geometry.left >= initial - 0.01f)
					assertTrue("$plane must stay inside the seamless sampling period", geometry.left < initial + geometry.width + 0.01f)
				}
			}
		}
	}

	@Test
	fun defaultCloudDeckReachesMidSkyWithoutCoveringTheWholeScreen() {
		for ((width, height) in listOf(480f to 1056f, 800f to 1280f, 1056f to 480f)) {
			val hero = assetGeometry(StormCloudPlane.HERO, width, height, 1f)
			val distant = assetGeometry(StormCloudPlane.DISTANT, width, height, 1f)
			assertTrue("Hero bank must extend below the upper clouds", hero.top + hero.height >= height * 0.42f)
			if (width < height) {
				assertTrue("Portrait hero bank must stay above the middle of the sky", hero.top + hero.height <= height * 0.51f)
			} else {
				assertTrue("Landscape hero bank must leave lower sky visible", hero.top + hero.height <= height * 0.63f)
			}
			assertTrue(distant.top + distant.height <= height * 0.70f)
		}
	}

	private fun assetGeometry(plane: StormCloudPlane, width: Float, height: Float, size: Float, time: Float = 0f): StormCloudGeometry {
		val source = when (plane) {
			StormCloudPlane.VEIL -> 912 to 552
			StormCloudPlane.UPPER -> 1524 to 344
			StormCloudPlane.HERO -> 1782 to 892
			StormCloudPlane.DISTANT -> 1514 to 204
		}

		return StormCloudGeometry().apply {
			check(configure(width, height, source.first, source.second, plane, 0f, 0.5f, 1f, size))
			if (time != 0f) {
				check(configure(width, height, source.first, source.second, plane, time, 0.5f, 1f, size))
			}
		}
	}

	private fun geometry(plane: StormCloudPlane, timeSeconds: Float, windScale: Float = 1f) = StormCloudGeometry().apply {
		check(configure(480f, 1056f, 1600, 800, plane, 0f, 0.5f, windScale, 1f))
		if (timeSeconds != 0f) {
			check(configure(480f, 1056f, 1600, 800, plane, timeSeconds, 0.5f, windScale, 1f))
		}
	}
}
