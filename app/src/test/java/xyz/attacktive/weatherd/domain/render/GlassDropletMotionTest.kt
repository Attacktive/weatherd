package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STEADY

class GlassDropletMotionTest {
	@Test
	fun onlyRainProducesGlassDroplets() {
		val drizzle = Precipitation(PrecipitationKind.RAIN, SEVERITY_DRIZZLE, observed = 0f)
		val snow = Precipitation(PrecipitationKind.SNOW, SEVERITY_STEADY, observed = 1f)
		val sleet = Precipitation(PrecipitationKind.SLEET, SEVERITY_STEADY, observed = 1f)

		assertEquals(0, glassDropletCount(params(null), WIDTH, HEIGHT))
		assertEquals(0, glassDropletCount(params(snow), WIDTH, HEIGHT))
		assertEquals(0, glassDropletCount(params(sleet), WIDTH, HEIGHT))
		assertEquals(0, glassDropletCount(params(drizzle, scale = 0f), WIDTH, HEIGHT))
		assertEquals(0, glassDropletCount(params(drizzle, scale = 1f), 0f, HEIGHT))
		assertTrue(glassDropletCount(params(drizzle, scale = 0.1f), WIDTH, HEIGHT) > 0)
	}

	@Test
	fun densityRespectsIntensityAndRemainsBounded() {
		val rain = Precipitation(PrecipitationKind.RAIN, SEVERITY_STEADY, observed = 0.65f)
		val subtle = glassDropletCount(params(rain, scale = 0.1f), WIDTH, HEIGHT)
		val normal = glassDropletCount(params(rain, scale = 1f), WIDTH, HEIGHT)
		val intense = glassDropletCount(params(rain, scale = 2f), WIDTH, HEIGHT)

		assertTrue(subtle <= normal)
		assertTrue(normal <= intense)
		assertTrue(intense <= 48)
		assertTrue(glassDropletCount(params(rain, scale = 2f), 64f, 32_000f) <= 48)
		assertTrue(glassDropletCount(params(rain, scale = 2f), 32_000f, 64f) <= 48)

		for (scale in listOf(0.1f, 1f, 2f)) {
			assertEquals(
				glassDropletCount(params(rain, scale), 360f, 780f),
				glassDropletCount(params(rain, scale), 1080f, 2340f)
			)
		}
	}

	@Test
	fun beadHoldsThenSlidesDownward() {
		val frame = GlassDropletFrame()
		var previous: Sample? = null
		var foundHold = false
		var foundSlide = false
		var foundInvisible = false
		var wasInvisible = false
		val cycleEntries = mutableListOf<Sample>()

		for (step in 0..192) {
			frame.sample(7, WIDTH, HEIGHT, step * 0.25f)
			val current = Sample(frame.centerX, frame.centerY, frame.radius, frame.verticalStretch, frame.opacity)

			if (current.opacity == 0f) {
				foundInvisible = true
				wasInvisible = true
			} else {
				if (wasInvisible) {
					cycleEntries += current
					wasInvisible = false
				}

				previous?.let {
					if (isStationaryBead(it, current)) {
						foundHold = true
					}

					if (isSlidingDroplet(it, current)) {
						foundSlide = true
					}
				}
			}

			previous = current
		}

		assertTrue("Expected a stationary full-size bead interval", foundHold)
		assertTrue("Expected a visible downward sliding interval", foundSlide)
		assertTrue("Expected an invisible exit/reset interval", foundInvisible)
		assertTrue("Expected at least two cycle entries", cycleEntries.size >= 2)
		assertTrue(
			"A new cycle must choose a different lane",
			abs(cycleEntries[0].centerX - cycleEntries[1].centerX) > EPSILON || abs(cycleEntries[0].centerY - cycleEntries[1].centerY) > EPSILON
		)
	}

	@Test
	fun coalescenceIsDeterministicAndRemovesCapturedBeads() {
		val coalescenceTime = findCoalescenceTime()
		assertNotNull("Expected deterministic seeded lanes to produce at least one coalescence", coalescenceTime)
		val expected = GlassDropletField()
		val replayed = GlassDropletField()
		expected.sample(GLASS_DROPLET_MAX_COUNT, WIDTH, HEIGHT, coalescenceTime!!)
		replayed.sample(GLASS_DROPLET_MAX_COUNT, WIDTH * 3f, HEIGHT * 3f, coalescenceTime + 17f)
		replayed.sample(GLASS_DROPLET_MAX_COUNT, WIDTH, HEIGHT, coalescenceTime)
		assertFieldsEqual(expected, replayed, GLASS_DROPLET_MAX_COUNT)
	}

	@Test
	fun coalescenceCacheTracksDensityChangesWithinACycle() {
		val reused = GlassDropletField()
		val fresh = GlassDropletField()

		for (step in 0..192) {
			val time = step * 0.5f
			reused.sample(LOW_DENSITY_COUNT, WIDTH, HEIGHT, time)
			reused.sample(GLASS_DROPLET_MAX_COUNT, WIDTH, HEIGHT, time)
			fresh.sample(GLASS_DROPLET_MAX_COUNT, WIDTH, HEIGHT, time)
			assertFieldsEqual(fresh, reused, GLASS_DROPLET_MAX_COUNT)
		}
	}

	@Test
	fun reusedOutputSurvivesResizeAndClockWrap() {
		val reused = GlassDropletFrame()
		val samples = arrayOf(
			SampleRequest(360f, 780f, 0f),
			SampleRequest(1080f, 2340f, 5.5f),
			SampleRequest(2340f, 1080f, 17.25f),
			SampleRequest(360f, 780f, 21_599.9f),
			SampleRequest(360f, 780f, 0f)
		)

		for (request in samples) {
			reused.sample(11, request.width, request.height, request.timeSeconds)
			assertFinite(reused)
		}

		val fresh = GlassDropletFrame()
		fresh.sample(11, 360f, 780f, 0f)

		assertFrameEquals(fresh, reused)
	}

	@Test
	fun geometryScalesWithTheVisibleViewport() {
		val small = GlassDropletFrame()
		val large = GlassDropletFrame()
		small.sample(3, 360f, 780f, 10.25f)
		large.sample(3, 1080f, 2340f, 10.25f)

		assertEquals(small.centerX / 360f, large.centerX / 1080f, EPSILON)
		assertEquals(small.centerY / 780f, large.centerY / 2340f, EPSILON)
		assertEquals(small.radius / 360f, large.radius / 1080f, EPSILON)
		assertEquals(small.verticalStretch, large.verticalStretch, EPSILON)
		assertEquals(small.opacity, large.opacity, EPSILON)
	}

	private fun findCoalescenceTime(): Float? {
		val field = GlassDropletField()
		val raw = GlassDropletFrame()
		for (step in 0..384) {
			val time = step * 0.25f
			field.sample(GLASS_DROPLET_MAX_COUNT, WIDTH, HEIGHT, time)
			if (hasCapturedBead(field, raw, time) && hasGrownCollector(field, raw, time)) {
				return time
			}
		}

		return null
	}

	private fun hasCapturedBead(field: GlassDropletField, raw: GlassDropletFrame, time: Float): Boolean {
		for (slot in 0 until GLASS_DROPLET_MAX_COUNT) {
			raw.sample(slot, WIDTH, HEIGHT, time)
			if (raw.opacity > 0f && field.frame(slot).opacity == 0f) {
				return true
			}
		}

		return false
	}

	private fun hasGrownCollector(field: GlassDropletField, raw: GlassDropletFrame, time: Float): Boolean {
		for (slot in 0 until GLASS_DROPLET_MAX_COUNT) {
			raw.sample(slot, WIDTH, HEIGHT, time)
			val coalesced = field.frame(slot)
			if (coalesced.opacity > 0f && coalesced.radius > raw.radius + EPSILON) {
				return true
			}
		}

		return false
	}

	private fun assertFieldsEqual(expected: GlassDropletField, actual: GlassDropletField, count: Int) {
		for (slot in 0 until count) {
			assertFrameEquals(expected.frame(slot), actual.frame(slot))
		}
	}

	private fun isStationaryBead(previous: Sample, current: Sample): Boolean {
		val fullyOpaque = previous.opacity > 0.99f && current.opacity > 0.99f
		val stationary = abs(current.centerY - previous.centerY) < EPSILON && abs(current.radius - previous.radius) < EPSILON

		return fullyOpaque && stationary && current.verticalStretch == 1f
	}

	private fun isSlidingDroplet(previous: Sample, current: Sample): Boolean {
		val fullyOpaque = previous.opacity > 0.99f && current.opacity > 0.99f
		val movingDown = current.centerY > previous.centerY + EPSILON

		return fullyOpaque && movingDown && current.verticalStretch > 1f
	}

	private fun params(precipitation: Precipitation?, scale: Float = 1f) = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = precipitation,
		thunder = false,
		windFactor = 0f,
		precipitationScale = scale
	)

	private fun assertFinite(frame: GlassDropletFrame) {
		assertTrue(frame.centerX.isFinite())
		assertTrue(frame.centerY.isFinite())
		assertTrue(frame.radius.isFinite())
		assertTrue(frame.verticalStretch.isFinite())
		assertTrue(frame.opacity.isFinite())
	}

	private fun assertFrameEquals(expected: GlassDropletFrame, actual: GlassDropletFrame) {
		assertEquals(expected.centerX, actual.centerX, EPSILON)
		assertEquals(expected.centerY, actual.centerY, EPSILON)
		assertEquals(expected.radius, actual.radius, EPSILON)
		assertEquals(expected.verticalStretch, actual.verticalStretch, EPSILON)
		assertEquals(expected.opacity, actual.opacity, EPSILON)
	}

	private data class Sample(
		val centerX: Float,
		val centerY: Float,
		val radius: Float,
		val verticalStretch: Float,
		val opacity: Float
	)

	private data class SampleRequest(
		val width: Float,
		val height: Float,
		val timeSeconds: Float
	)

	private companion object {
		const val WIDTH = 360f
		const val HEIGHT = 780f
		const val LOW_DENSITY_COUNT = 8
		const val EPSILON = 0.0001f
	}
}
