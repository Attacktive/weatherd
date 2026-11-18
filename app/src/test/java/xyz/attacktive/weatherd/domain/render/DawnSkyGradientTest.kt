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
			val stylized = clearDawn().copy(skyColorPreset = preset)
			assertEquals(gradient, skyGradientFor(stylized))
			assertEquals(0, dawnHorizonHazeAlpha(stylized))
		}

		for (preset in listOf(SkyColorPreset.NATURAL, SkyColorPreset.CLEAR_BLUE, SkyColorPreset.CUSTOM)) {
			assertEquals(52, dawnHorizonHazeAlpha(clearDawn().copy(skyColorPreset = preset)))
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

	@Test
	fun dawnHorizonHazePeaksAtSunriseAndVanishesByDaylight() {
		val clear = clearDawn()
		val alphaAtZero = dawnHorizonHazeAlpha(clear.copy(celestialProgress = 0f))
		val alphaAtQuarter = dawnHorizonHazeAlpha(clear.copy(celestialProgress = 0.25f))
		val alphaAtSunrise = dawnHorizonHazeAlpha(clear.copy(celestialProgress = 0.5f))
		val alphaAtMorning = dawnHorizonHazeAlpha(clear.copy(celestialProgress = 0.7f))

		assertTrue(alphaAtZero > 0)
		assertTrue(alphaAtQuarter > alphaAtZero)
		assertEquals(52, alphaAtSunrise)
		assertTrue(alphaAtMorning in 1 until alphaAtSunrise)

		assertTrue(abs(alphaAtSunrise - dawnHorizonHazeAlpha(clear.copy(celestialProgress = 0.499f))) <= 1)
		assertTrue(abs(alphaAtSunrise - dawnHorizonHazeAlpha(clear.copy(celestialProgress = 0.501f))) <= 1)
		assertTrue(abs(dawnHorizonHazeAlpha(clear.copy(celestialProgress = 0.849f)) - dawnHorizonHazeAlpha(clear.copy(celestialProgress = 0.85f))) <= 1)

		for (progress in listOf(0.85f, 0.92f, 1f)) {
			assertEquals(0, dawnHorizonHazeAlpha(clear.copy(celestialProgress = progress)))
		}

		for (phase in listOf(DayPhase.DAY, DayPhase.DUSK, DayPhase.NIGHT)) {
			for (progress in listOf(0f, 0.5f, 1f)) {
				assertEquals(0, dawnHorizonHazeAlpha(clear.copy(dayPhase = phase, celestialProgress = progress)))
			}
		}
	}

	@Test
	fun dawnHorizonHazeYieldsToOpaqueCloudsFogHumidityAndPrecipitation() {
		val clear = clearDawn()
		val baseline = dawnHorizonHazeAlpha(clear)

		assertEquals(baseline, dawnHorizonHazeAlpha(clear.copy(cloudiness = 0.9f, cloudLayers = SceneCloudLayers(0.05f, 0.05f, 0.9f))))
		assertEquals(baseline, dawnHorizonHazeAlpha(clear.copy(cloudCountScale = 2f)))
		assertEquals(baseline, dawnHorizonHazeAlpha(clear.copy(sunVisible = false)))

		for (coverage in listOf(0.2f, 0.45f, 0.72f, 0.75f)) {
			assertEquals(baseline, dawnHorizonHazeAlpha(clear.copy(cloudiness = coverage)))
		}

		val transitional = clear.copy(cloudiness = 0.80f)
		val transitionalAlpha = dawnHorizonHazeAlpha(transitional)
		assertTrue(transitionalAlpha in 1 until baseline)
		assertEquals(transitionalAlpha, dawnHorizonHazeAlpha(transitional.copy(cloudCountScale = 2f)))

		val humid = dawnHorizonHazeAlpha(clear.copy(humidityHazeDensity = 0.125f))
		assertTrue(humid in 1 until baseline)

		val suppressed = listOf(
			clear.copy(cloudiness = 0.85f),
			clear.copy(cloudiness = 0.9f),
			clear.copy(fogDensity = 0.1f),
			clear.copy(fogDensity = 1f),
			clear.copy(humidityHazeDensity = 0.25f),
			clear.copy(precipitation = Precipitation(PrecipitationKind.RAIN, 0.4f, observed = 0.3f)),
			clear.copy(precipitation = Precipitation(PrecipitationKind.SNOW, 0.4f, observed = 0.3f)),
			clear.copy(thunder = true)
		)

		for (params in suppressed) {
			assertEquals(0, dawnHorizonHazeAlpha(params))
		}
	}

	@Test
	fun dawnHorizonHazeColorsHonorBrightnessAndWarmWhiteGoldOrdering() {
		val clear = clearDawn()
		val skyBottom = skyGradientFor(clear).bottomColor
		val crest = dawnHorizonHazeCrestColor(clear, skyBottom)
		val bottom = dawnHorizonHazeBottomColor(clear, skyBottom)

		assertTrue(channel(crest, 16) >= channel(crest, 8) && channel(crest, 8) >= channel(crest, 0))
		assertTrue(channel(bottom, 16) >= channel(bottom, 8) && channel(bottom, 8) >= channel(bottom, 0))
		assertTrue(channel(bottom, 8) > channel(skyBottom, 8))
		assertTrue(channel(bottom, 0) > channel(skyBottom, 0))
		assertTrue(channel(bottom, 8) + channel(bottom, 0) > channel(crest, 8) + channel(crest, 0))

		val dim = clear.copy(skyBrightnessScale = 0.7f)
		val dimBottom = dawnHorizonHazeBottomColor(dim, skyGradientFor(dim).bottomColor)
		val bright = clear.copy(skyBrightnessScale = 1.3f)
		val brightBottom = dawnHorizonHazeBottomColor(bright, skyGradientFor(bright).bottomColor)

		for (shift in listOf(16, 8, 0)) {
			assertTrue(channel(dimBottom, shift) < channel(bottom, shift))
			assertTrue(channel(brightBottom, shift) >= channel(bottom, shift))
		}

		assertTrue(channel(brightBottom, 8) > channel(bottom, 8))
		assertTrue(channel(brightBottom, 0) > channel(bottom, 0))

		val muted = clear.copy(skySaturationScale = 0.5f)
		val vivid = clear.copy(skySaturationScale = 1.5f)
		val mutedCrest = dawnHorizonHazeCrestColor(muted, skyBottom)
		val vividCrest = dawnHorizonHazeCrestColor(vivid, skyBottom)
		val mutedBottom = dawnHorizonHazeBottomColor(muted, skyBottom)
		val vividBottom = dawnHorizonHazeBottomColor(vivid, skyBottom)

		assertTrue(spread(mutedCrest) < spread(crest) && spread(crest) < spread(vividCrest))
		assertTrue(spread(mutedBottom) < spread(bottom) && spread(bottom) < spread(vividBottom))

		val scaledMutedBottom = dawnHorizonHazeBottomColor(muted, skyGradientFor(muted).bottomColor)
		val scaledVividBottom = dawnHorizonHazeBottomColor(vivid, skyGradientFor(vivid).bottomColor)
		assertTrue(spread(scaledMutedBottom) < spread(bottom) && spread(bottom) < spread(scaledVividBottom))
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
