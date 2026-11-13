package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.CustomSkyGradient
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SkyGradientDirection

class DawnSkyGradientTest {
	@Test
	fun naturalDawnHasAnExplicitCoolBridgeAndTheOriginalBronze() {
		for (preset in listOf(SkyColorPreset.NATURAL, SkyColorPreset.CLEAR_BLUE, SkyColorPreset.CUSTOM)) {
			for (progress in listOf(0f, 0.25f, 0.5f)) {
				val gradient = skyGradientFor(clearDawn().copy(skyColorPreset = preset, celestialProgress = progress))
				assertEquals(0xFF3A6797.toInt(), gradient.topColor)
				assertEquals(0xFF8493B3.toInt(), gradient.middleColor)
				assertEquals(0xFFF6A984.toInt(), gradient.bottomColor)
				assertEquals(0.4f, gradient.middlePosition, 0f)
				assertEquals(0.88f, gradient.endPosition, 0f)
				assertEquals(SkyGradientDirection.VERTICAL, gradient.direction)
			}
		}
	}

	@Test
	fun lowerSkyIsWarmAboveTheOldTwilightMidpoint() {
		val gradient = skyGradientFor(clearDawn())
		val upper = sample(gradient, 0.3f)
		val lower = sample(gradient, 0.65f)
		assertTrue(channel(upper, 0) > channel(upper, 16))
		assertTrue(channel(lower, 16) > channel(lower, 0) + 20)
		assertEquals(gradient.bottomColor, sample(gradient, 0.95f))
	}

	@Test
	fun allSkySamplesConvergeContinuouslyIntoEachBuiltInDayPalette() {
		for (preset in listOf(SkyColorPreset.NATURAL, SkyColorPreset.CLEAR_BLUE)) {
			val params = clearDawn().copy(skyColorPreset = preset)
			var previous = skyGradientFor(params)
			for (step in 501..850) {
				val current = skyGradientFor(params.copy(celestialProgress = step / 1000f))
				for (row in 0..200) {
					assertColorsNear("$preset progress=$step row=$row", sample(previous, row / 200f), sample(current, row / 200f), 3)
				}

				assertTrue(current.startPosition < current.middlePosition && current.middlePosition < current.endPosition)
				previous = current
			}

			assertEquals(skyGradientFor(params.copy(dayPhase = DayPhase.DAY)), previous)
			assertNull(previous.middleColor)
		}
	}

	@Test
	fun completeDayGradientIsRestoredFromEightyFivePercentOnward() {
		for (preset in SkyColorPreset.entries) {
			val params = clearDawn().copy(skyColorPreset = preset, customSkyGradient = custom())
			for (progress in listOf(0.85f, 0.9f, 1f)) {
				assertEquals(skyGradientFor(params.copy(dayPhase = DayPhase.DAY)), skyGradientFor(params.copy(celestialProgress = progress)))
			}
		}
	}

	@Test
	fun allDawnStopsHonorBrightnessAndSaturation() {
		val params = clearDawn()
		val normal = skyGradientFor(params)
		for (brightness in listOf(0.8f, 1.2f)) {
			val adjusted = skyGradientFor(params.copy(skyBrightnessScale = brightness))
			for ((before, after) in colors(normal).zip(colors(adjusted))) {
				for (shift in listOf(16, 8, 0)) {
					assertEquals((channel(before, shift) * brightness).roundToInt().coerceAtMost(255), channel(after, shift))
				}
			}
		}

		val muted = skyGradientFor(params.copy(skySaturationScale = 0.5f))
		for ((before, after) in colors(normal).zip(colors(muted))) {
			assertTrue(spread(after) < spread(before))
		}
	}

	@Test
	fun fullOvercastRemovesColorFromEveryStop() {
		for (progress in listOf(0f, 0.5f, 0.7f, 0.849f)) {
			val params = clearDawn().copy(cloudiness = 0.9f, celestialProgress = progress)
			for (thunder in listOf(false, true)) {
				val gradient = skyGradientFor(params.copy(thunder = thunder))
				assertEquals(gradient.topColor, gradient.middleColor)
				assertEquals(gradient.topColor, gradient.bottomColor)
			}
		}
	}

	@Test
	fun fogAndPrecipitationStillAttenuateTheBridgeBeforeRendering() {
		val clear = clearDawn()
		val baseline = naturalSkyGradientFor(clear)
		val scenes = listOf(
			clear.copy(fogDensity = 0.5f),
			clear.copy(precipitation = Precipitation(PrecipitationKind.RAIN, 0.65f, observed = 0.5f)),
			clear.copy(precipitation = Precipitation(PrecipitationKind.SNOW, 0.65f, observed = 0.5f))
		)

		for (scene in scenes) {
			val gradient = naturalSkyGradientFor(scene)
			assertTrue(spread(checkNotNull(gradient.middleColor)) < spread(checkNotNull(baseline.middleColor)))
			assertEquals(baseline.middlePosition, gradient.middlePosition, 0f)
			assertEquals(baseline.endPosition, gradient.endPosition, 0f)
		}
	}

	@Test
	fun scatteredCloudsDoNotChangeTheDawnGradient() {
		val clear = clearDawn()
		for (coverage in listOf(0.2f, 0.5f, 0.7f, 0.75f)) {
			assertEquals(skyGradientFor(clear), skyGradientFor(clear.copy(cloudiness = coverage)))
		}
	}

	@Test
	fun customDaylightGeometryDoesNotLeakIntoEarlyDawn() {
		for (direction in SkyGradientDirection.entries) {
			for (threeColors in listOf(false, true)) {
				val params = clearDawn().copy(skyColorPreset = SkyColorPreset.CUSTOM, customSkyGradient = custom().copy(direction = direction, threeColors = threeColors))
				assertEquals(skyGradientFor(clearDawn()), skyGradientFor(params))
				val day = customDaylightSkyGradientFor(params)
				assertEquals(direction, day.direction)
				assertEquals(params.customSkyGradient.startColor, day.topColor)
				assertEquals(params.customSkyGradient.endColor, day.bottomColor)
				assertEquals(0.2f, day.startPosition, 0f)
				assertEquals(0.8f, day.endPosition, 0f)
			}
		}
	}

	@Test
	fun stylizedDawnColorsAndGeometryRemainUnchanged() {
		val expected = mapOf(
			SkyColorPreset.WARM to SkyGradient(0xFF4A3D69.toInt(), 0xFFFAB179.toInt()),
			SkyColorPreset.PASTEL to SkyGradient(0xFF746C9F.toInt(), 0xFFFAC2B5.toInt()),
			SkyColorPreset.CYBERPUNK to SkyGradient(0xFF482D79.toInt(), 0xFFFF74A8.toInt())
		)

		for ((preset, gradient) in expected) {
			assertEquals(gradient, skyGradientFor(clearDawn().copy(skyColorPreset = preset)))
		}
	}

	@Test
	fun naturalDayDuskAndNightKeepTheirPreviousPalette() {
		val expected = mapOf(
			DayPhase.DAY to SkyGradient(0xFF4A90D9.toInt(), 0xFFA9D6F5.toInt()),
			DayPhase.DUSK to SkyGradient(0xFF26314F.toInt(), 0xFFE8825B.toInt()),
			DayPhase.NIGHT to SkyGradient(0xFF0B1026.toInt(), 0xFF1B2450.toInt())
		)

		for ((phase, gradient) in expected) {
			assertEquals(gradient, skyGradientFor(clearDawn().copy(dayPhase = phase)))
		}
	}

	private fun clearDawn() = SceneParams(dayPhase = DayPhase.DAWN, cloudiness = 0.05f, fogDensity = 0f, precipitation = null, thunder = false, windFactor = 0f, celestialProgress = 0.5f)

	private fun custom() = CustomSkyGradient(startColor = 0xFF224488.toInt(), middleColor = 0xFF66AACC.toInt(), endColor = 0xFFDDEEFF.toInt(), threeColors = true, startPosition = 0.2f, middlePosition = 0.4f, endPosition = 0.8f, direction = SkyGradientDirection.HORIZONTAL)

	private fun colors(gradient: SkyGradient) = listOfNotNull(gradient.topColor, gradient.middleColor, gradient.bottomColor)

	private fun channel(color: Int, shift: Int) = color ushr shift and 255

	private fun spread(color: Int) = listOf(16, 8, 0).map { channel(color, it) }.let { it.max() - it.min() }

	private fun sample(gradient: SkyGradient, position: Float): Int {
		val middle = gradient.middleColor
		return if (middle == null) {
			blend(gradient.topColor, gradient.bottomColor, (position - gradient.startPosition) / (gradient.endPosition - gradient.startPosition))
		} else if (position <= gradient.middlePosition) {
			blend(gradient.topColor, middle, (position - gradient.startPosition) / (gradient.middlePosition - gradient.startPosition))
		} else {
			blend(middle, gradient.bottomColor, (position - gradient.middlePosition) / (gradient.endPosition - gradient.middlePosition))
		}
	}

	private fun blend(first: Int, second: Int, fraction: Float): Int {
		var result = 0xFF000000.toInt()
		for (shift in listOf(16, 8, 0)) {
			val start = channel(first, shift)
			result = result or ((start + (channel(second, shift) - start) * fraction.coerceIn(0f, 1f)).roundToInt() shl shift)
		}

		return result
	}

	private fun assertColorsNear(message: String, first: Int, second: Int, tolerance: Int) {
		for (shift in listOf(16, 8, 0)) {
			assertTrue("$message channel=$shift first=$first second=$second", abs(channel(first, shift) - channel(second, shift)) <= tolerance)
		}
	}
}
