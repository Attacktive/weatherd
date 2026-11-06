package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.SkyColorPreset

class StormCloudAtmosphereTest {
	private val rainy = SceneParams(DayPhase.DAY, 1f, 0f, Precipitation(PrecipitationKind.RAIN, 0.5f, 1f), false, 0.5f)

	@Test
	fun naturalDayRainBecomesMutedWithoutRemovingTheGradient() {
		val original = naturalSkyGradientFor(rainy)
		val muted = stormCloudSkyGradient(rainy, original)
		assertTrue(chroma(muted.topColor) < chroma(original.topColor))
		assertTrue(chroma(muted.bottomColor) < chroma(original.bottomColor))
		assertTrue(luminance(muted.topColor) < luminance(original.topColor))
		assertTrue(luminance(muted.bottomColor) < luminance(original.bottomColor))
		assertTrue(muted.topColor != muted.bottomColor)
		assertEquals(original.startPosition, muted.startPosition, 0f)
		assertEquals(original.endPosition, muted.endPosition, 0f)
	}

	@Test
	fun nightAndTwilightEndpointsKeepTheirOriginalLighting() {
		for (params in listOf(rainy.copy(dayPhase = DayPhase.NIGHT), rainy.copy(dayPhase = DayPhase.DAWN, celestialProgress = 0f), rainy.copy(dayPhase = DayPhase.DUSK, celestialProgress = 1f))) {
			val original = naturalSkyGradientFor(params)
			assertSame(original, stormCloudSkyGradient(params, original))
		}
	}

	@Test
	fun gradingJoinsDaylightAtBothTwilightBoundaries() {
		val gradient = naturalSkyGradientFor(rainy)
		val daylight = stormCloudSkyGradient(rainy, gradient)
		assertEquals(daylight, stormCloudSkyGradient(rainy.copy(dayPhase = DayPhase.DAWN, celestialProgress = 1f), gradient))
		assertEquals(daylight, stormCloudSkyGradient(rainy.copy(dayPhase = DayPhase.DUSK, celestialProgress = 0f), gradient))
	}

	@Test
	fun drySkyOtherPrecipitationAndCustomPalettesAreUntouched() {
		val gradient = naturalSkyGradientFor(rainy)
		for (precipitation in listOf(null, Precipitation(PrecipitationKind.SNOW, 0.5f, 1f), Precipitation(PrecipitationKind.SLEET, 0.5f, 1f))) {
			assertSame(gradient, stormCloudSkyGradient(rainy.copy(precipitation = precipitation), gradient))
		}

		for (preset in SkyColorPreset.entries.filter { it != SkyColorPreset.NATURAL }) {
			assertSame(gradient, stormCloudSkyGradient(rainy.copy(skyColorPreset = preset), gradient))
		}

	}

	@Test
	fun skyBrightnessStillControlsTheMutedRainySky() {
		val dim = rainy.copy(skyBrightnessScale = 0.6f)
		val bright = rainy.copy(skyBrightnessScale = 1.4f)
		val dimSky = stormCloudSkyGradient(dim, naturalSkyGradientFor(dim))
		val brightSky = stormCloudSkyGradient(bright, naturalSkyGradientFor(bright))
		assertTrue(luminance(brightSky.topColor) > luminance(dimSky.topColor))
		assertTrue(luminance(brightSky.bottomColor) > luminance(dimSky.bottomColor))
	}

	private fun chroma(color: Int): Int {
		val channels = listOf(color ushr 16 and 255, color ushr 8 and 255, color and 255)

		return channels.max() - channels.min()
	}

	private fun luminance(color: Int) = (color ushr 16 and 255) * 0.2126f + (color ushr 8 and 255) * 0.7152f + (color and 255) * 0.0722f
}
