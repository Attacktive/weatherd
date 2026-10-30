package xyz.attacktive.weatherd.domain.render

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.HolidayCelebration

class SeasonalCelebrationMotionTest {
	@Test
	fun `strict night gate rejects non-night phases even when holiday is configured`() {
		val frame = SeasonalCelebrationFrame()
		val width = 1080f
		val height = 2400f
		val epochMillis = 1761800000000L

		for (celebration in listOf(HolidayCelebration.HALLOWEEN, HolidayCelebration.CHRISTMAS, HolidayCelebration.NEW_YEAR)) {
			for (phase in listOf(DayPhase.DAY, DayPhase.DAWN, DayPhase.DUSK)) {
				val params = testParams(dayPhase = phase, holidayCelebration = celebration)
				assertFalse(
					"Celebration $celebration must not sample during $phase",
					frame.sample(width, height, params, epochMillis)
				)
			}
		}
	}

	@Test
	fun `none holiday celebration rejects sampling even at night`() {
		val frame = SeasonalCelebrationFrame()
		val width = 1080f
		val height = 2400f
		val params = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.NONE)

		for (secondOffset in 0L..300L) {
			assertFalse(
				"NONE celebration must never sample an active frame",
				frame.sample(width, height, params, 1761800000000L + secondOffset * 1000L)
			)
		}
	}

	@Test
	fun `halloween witch flyby scheduling is deterministic across repeated sampling and instances`() {
		val width = 1080f
		val height = 2400f
		val params = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.HALLOWEEN)
		val frameA = SeasonalCelebrationFrame()
		val frameB = SeasonalCelebrationFrame()
		val activeMillis = findActiveFlybyEpochMillis(HolidayCelebration.HALLOWEEN)

		assertTrue(frameA.sample(width, height, params, activeMillis))
		assertTrue(frameB.sample(width, height, params, activeMillis))

		assertEquals(frameA.flybyX, frameB.flybyX, 0.0001f)
		assertEquals(frameA.flybyY, frameB.flybyY, 0.0001f)
		assertEquals(frameA.flybyProgress, frameB.flybyProgress, 0.0001f)
		assertEquals(frameA.flybyTiltDegrees, frameB.flybyTiltDegrees, 0.0001f)
		assertEquals(frameA.flybyFliesRight, frameB.flybyFliesRight)
		assertEquals(frameA.flybyOpacity, frameB.flybyOpacity, 0.0001f)
	}

	@Test
	fun `halloween witch flyby progresses monotonically and expires after pass duration`() {
		val width = 1080f
		val height = 2400f
		val params = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.HALLOWEEN)
		val frame = SeasonalCelebrationFrame()
		val startMillis = findPassStartMillis(HolidayCelebration.HALLOWEEN)
		val durationMillis = SeasonalCelebrationFrame.HALLOWEEN_DURATION_MILLIS

		assertTrue(frame.sample(width, height, params, startMillis))
		val initialX = frame.flybyX
		val fliesRight = frame.flybyFliesRight

		assertTrue(frame.sample(width, height, params, startMillis + durationMillis / 2))
		val midProgress = frame.flybyProgress
		assertEquals(0.5f, midProgress, 0.02f)
		if (fliesRight) {
			assertTrue(frame.flybyX > initialX)
		} else {
			assertTrue(frame.flybyX < initialX)
		}

		assertTrue(frame.sample(width, height, params, startMillis + durationMillis - 100L))
		assertTrue(frame.flybyProgress > 0.95f)

		assertFalse(frame.sample(width, height, params, startMillis + durationMillis + 50L))
	}

	@Test
	fun `flybys avoid status bar and celestial moon area`() {
		val width = 1080f
		val height = 2400f
		val frame = SeasonalCelebrationFrame()
		val statusBarHeight = height * 0.08f
		val moonCenterX = width * 0.72f
		val moonCenterY = height * 0.24f
		val moonRadius = minOf(width, height) * 0.10f

		for (celebration in listOf(HolidayCelebration.HALLOWEEN, HolidayCelebration.CHRISTMAS)) {
			val startMillis = findPassStartMillis(celebration)
			val durationMillis = if (celebration == HolidayCelebration.HALLOWEEN) {
				SeasonalCelebrationFrame.HALLOWEEN_DURATION_MILLIS
			} else {
				SeasonalCelebrationFrame.CHRISTMAS_DURATION_MILLIS
			}

			val params = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = celebration, moonVisible = true)
			for (offset in 0L..durationMillis step 500L) {
				if (frame.sample(width, height, params, startMillis + offset)) {
					assertTrue(
						"Flyby Y must stay below status bar: ${frame.flybyY} vs $statusBarHeight",
						frame.flybyY > statusBarHeight
					)

					val distToMoon = hypot(frame.flybyX - moonCenterX, frame.flybyY - moonCenterY)
					assertTrue(
						"Flyby must avoid moon center: dist=$distToMoon vs radius=$moonRadius",
						distToMoon > moonRadius * 0.9f
					)
				}
			}
		}
	}

	@Test
	fun `christmas santa flyby scheduling advances gallop phase and respects pass duration`() {
		val width = 1080f
		val height = 2400f
		val params = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.CHRISTMAS)
		val frame = SeasonalCelebrationFrame()
		val startMillis = findPassStartMillis(HolidayCelebration.CHRISTMAS)
		val durationMillis = SeasonalCelebrationFrame.CHRISTMAS_DURATION_MILLIS

		assertTrue(frame.sample(width, height, params, startMillis + 1000L))
		val phase1 = frame.gallopPhase

		assertTrue(frame.sample(width, height, params, startMillis + 1200L))
		val phase2 = frame.gallopPhase
		assertNotEquals(phase1, phase2, 0.0001f)

		assertTrue(frame.sample(width, height, params, startMillis + durationMillis / 2))
		assertEquals(0.5f, frame.flybyProgress, 0.02f)

		assertFalse(frame.sample(width, height, params, startMillis + durationMillis + 200L))
	}

	@Test
	fun `new year fireworks scheduling includes ascent and bursting phases with gravity`() {
		val width = 1080f
		val height = 2400f
		val params = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.NEW_YEAR)
		val frame = SeasonalCelebrationFrame()
		val burstStartMillis = findFireworkStartMillis()

		assertTrue(frame.sample(width, height, params, burstStartMillis + 300L))
		val activeAscent = frame.activeBursts().firstOrNull { it.isAscent }

		assertTrue("Must have an ascending rocket during initial phase", activeAscent != null)
		val rocket = activeAscent!!
		assertTrue(rocket.rocketY > rocket.apexY)

		// Early burst phase (300ms into burst) exhibits active localized glow.
		assertTrue(frame.sample(width, height, params, burstStartMillis + 1100L))
		val activeBurst = frame.activeBursts().firstOrNull { !it.isAscent }

		assertTrue("Must transition to bursting phase after ascent", activeBurst != null)
		val burst = activeBurst!!
		assertTrue("Must have particles configured in burst", burst.sparkCount > 0)
		assertTrue("Glow radius must be non-zero during early burst", burst.glowRadius > 0f)

		val midY = burst.sparkY[0]
		assertTrue(frame.sample(width, height, params, burstStartMillis + 2500L))
		val lateBurst = frame.activeBursts().first { !it.isAscent }

		assertTrue("Gravity must pull particles downward over time", lateBurst.sparkY[0] > midY)
	}

	@Test
	fun `new year fireworks stay outside moon disc and avoid status bar`() {
		val width = 1080f
		val height = 2400f
		val params = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.NEW_YEAR, moonVisible = true)
		val frame = SeasonalCelebrationFrame()
		val statusBarHeight = height * 0.08f
		val moonCenterX = width * 0.72f
		val moonCenterY = height * 0.24f
		val moonRadius = minOf(width, height) * 0.10f

		for (slot in 0..15) {
			val millis = 1761800000000L + slot * SeasonalCelebrationFrame.FIREWORK_SLOT_MILLIS + 1200L
			if (frame.sample(width, height, params, millis)) {
				for (burst in frame.activeBursts()) {
					assertTrue(
						"Burst apex must stay below status bar: ${burst.apexY} vs $statusBarHeight",
						burst.apexY > statusBarHeight
					)

					val distToMoon = hypot(burst.apexX - moonCenterX, burst.apexY - moonCenterY)
					assertTrue(
						"Burst apex must avoid moon center: dist=$distToMoon vs radius=$moonRadius",
						distToMoon > moonRadius * 0.85f
					)
				}
			}
		}
	}

	@Test
	fun `orientation change maintains identical normalized progress and stable sampling`() {
		val portraitWidth = 1080f
		val portraitHeight = 2400f
		val landscapeWidth = 2400f
		val landscapeHeight = 1080f
		val params = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.HALLOWEEN)
		val framePortrait = SeasonalCelebrationFrame()
		val frameLandscape = SeasonalCelebrationFrame()
		val activeMillis = findActiveFlybyEpochMillis(HolidayCelebration.HALLOWEEN)

		assertTrue(framePortrait.sample(portraitWidth, portraitHeight, params, activeMillis))
		assertTrue(frameLandscape.sample(landscapeWidth, landscapeHeight, params, activeMillis))

		assertEquals(
			"Normalized progress must match across orientations at identical epoch time",
			framePortrait.flybyProgress,
			frameLandscape.flybyProgress,
			0.0001f
		)
		assertEquals(framePortrait.flybyFliesRight, frameLandscape.flybyFliesRight)
	}

	@Test
	fun `idle freeze maintains identical coordinates without drift`() {
		val width = 1080f
		val height = 2400f
		val params = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.CHRISTMAS)
		val frame = SeasonalCelebrationFrame()
		val frozenMillis = findActiveFlybyEpochMillis(HolidayCelebration.CHRISTMAS)

		assertTrue(frame.sample(width, height, params, frozenMillis))
		val x1 = frame.flybyX
		val y1 = frame.flybyY
		val progress1 = frame.flybyProgress
		val gallop1 = frame.gallopPhase

		for (i in 1..20) {
			assertTrue(frame.sample(width, height, params, frozenMillis))
			assertEquals(x1, frame.flybyX, 0f)
			assertEquals(y1, frame.flybyY, 0f)
			assertEquals(progress1, frame.flybyProgress, 0f)
			assertEquals(gallop1, frame.gallopPhase, 0f)
		}
	}

	@Test
	fun `low frame rate sampling maintains continuous monotonic flight`() {
		val width = 1080f
		val height = 2400f
		val params = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.HALLOWEEN)
		val frame = SeasonalCelebrationFrame()
		val startMillis = findPassStartMillis(HolidayCelebration.HALLOWEEN)
		val durationMillis = SeasonalCelebrationFrame.HALLOWEEN_DURATION_MILLIS

		// Sample at 5 FPS (200ms steps).
		assertTrue(frame.sample(width, height, params, startMillis))
		var previousProgress = frame.flybyProgress
		var previousX = frame.flybyX
		val fliesRight = frame.flybyFliesRight

		for (offset in 200L..durationMillis step 200L) {
			if (frame.sample(width, height, params, startMillis + offset)) {
				assertTrue(frame.flybyProgress >= previousProgress)
				previousProgress = frame.flybyProgress
				if (fliesRight) {
					assertTrue(frame.flybyX >= previousX)
				} else {
					assertTrue(frame.flybyX <= previousX)
				}

				previousX = frame.flybyX
			}
		}
	}

	@Test
	fun `night brightness scale attenuates celebration opacity smoothly`() {
		val width = 1080f
		val height = 2400f
		val frame = SeasonalCelebrationFrame()
		val activeMillis = findActiveFlybyEpochMillis(HolidayCelebration.HALLOWEEN)

		val brightParams = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.HALLOWEEN, nightBrightnessScale = 1f)
		assertTrue(frame.sample(width, height, brightParams, activeMillis))
		val brightOpacity = frame.flybyOpacity

		val dimParams = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.HALLOWEEN, nightBrightnessScale = 0f)
		assertTrue(frame.sample(width, height, dimParams, activeMillis))
		val dimOpacity = frame.flybyOpacity

		assertTrue(
			"Dim opacity ($dimOpacity) must be lower than bright opacity ($brightOpacity) while remaining visible",
			dimOpacity in 0.25f..brightOpacity
		)
	}

	private fun testParams(
		dayPhase: DayPhase = DayPhase.NIGHT,
		holidayCelebration: HolidayCelebration = HolidayCelebration.HALLOWEEN,
		moonVisible: Boolean = true,
		nightBrightnessScale: Float = 1f
	) = SceneParams(
		dayPhase = dayPhase,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f,
		moonVisible = moonVisible,
		nightBrightnessScale = nightBrightnessScale,
		holidayCelebration = holidayCelebration
	)

	private fun findPassStartMillis(celebration: HolidayCelebration): Long {
		val slotMillis = if (celebration == HolidayCelebration.HALLOWEEN) {
			SeasonalCelebrationFrame.HALLOWEEN_SLOT_MILLIS
		} else {
			SeasonalCelebrationFrame.CHRISTMAS_SLOT_MILLIS
		}

		val durationMillis = if (celebration == HolidayCelebration.HALLOWEEN) {
			SeasonalCelebrationFrame.HALLOWEEN_DURATION_MILLIS
		} else {
			SeasonalCelebrationFrame.CHRISTMAS_DURATION_MILLIS
		}

		val salt = if (celebration == HolidayCelebration.HALLOWEEN) {
			SeasonalCelebrationFrame.HALLOWEEN_SALT
		} else {
			SeasonalCelebrationFrame.CHRISTMAS_SALT
		}

		val baseSlot = 1761800000000L / slotMillis
		return SeasonalCelebrationFrame.passStartMillis(baseSlot, slotMillis, durationMillis, salt)
	}

	private fun findActiveFlybyEpochMillis(celebration: HolidayCelebration): Long {
		val start = findPassStartMillis(celebration)
		return start + 4000L
	}

	private fun findFireworkStartMillis(): Long {
		val baseSlot = 1761800000000L / SeasonalCelebrationFrame.FIREWORK_SLOT_MILLIS
		return SeasonalCelebrationFrame.fireworkStartMillis(baseSlot)
	}
}
