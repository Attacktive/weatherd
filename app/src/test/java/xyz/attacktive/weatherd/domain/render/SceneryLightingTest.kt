package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SunColorPreset

class SceneryLightingTest {
	@Test
	fun `night removes directional sunlight`() {
		assertEquals(0f, lighting(clearParams(DayPhase.NIGHT)).directStrength, 0f)
	}

	@Test
	fun `decorative visibility preserves terrain lighting`() {
		val clear = clearParams(DayPhase.DAY)
		val visible = lighting(clear)
		val hidden = lighting(clear.copy(sunVisible = false, moonVisible = false, lensFlareEnabled = false))

		assertEquals(visible.directStrength, hidden.directStrength, 0f)
		assertEquals(visible.ambientColor, hidden.ambientColor)
	}

	@Test
	fun `decorative sun styling preserves terrain lighting`() {
		val clear = clearParams(DayPhase.DAY)
		val natural = lighting(clear)
		val styled = lighting(clear.copy(sunColorPreset = SunColorPreset.ORANGE, sunSizeScale = 2f))

		assertEquals(natural.directColor, styled.directColor)
		assertEquals(natural.ambientColor, styled.ambientColor)
	}

	@Test
	fun `opaque cloud layers attenuate direct light`() {
		val total = clearParams(DayPhase.DAY).copy(cloudiness = 0.8f)
		val high = total.copy(cloudLayers = SceneCloudLayers(low = 0.05f, mid = 0.05f, high = 0.8f))
		val low = total.copy(cloudLayers = SceneCloudLayers(low = 0.8f, mid = 0.05f, high = 0.05f))
		val mid = total.copy(cloudLayers = SceneCloudLayers(low = 0.05f, mid = 0.8f, high = 0.05f))

		assertTrue(lighting(high).directStrength > lighting(low).directStrength)
		assertEquals(lighting(low).directStrength, lighting(mid).directStrength, 0f)
	}

	@Test
	fun `total cover fallback attenuates direct light`() {
		val clear = clearParams(DayPhase.DAY)

		assertTrue(lighting(clear.copy(cloudiness = 0.85f)).directStrength < lighting(clear).directStrength)
	}

	@Test
	fun `fog attenuates direct light`() {
		val clear = clearParams(DayPhase.DAY)

		assertTrue(lighting(clear.copy(fogDensity = 1f)).directStrength < lighting(clear).directStrength)
	}

	@Test
	fun `fully dense fog removes directional sunlight`() {
		val denseFog = clearParams(DayPhase.DAY).copy(fogDensity = 1f)

		assertEquals(0f, lighting(denseFog).directStrength, 0f)
	}

	@Test
	fun `thunder alone attenuates direct light and surface contrast`() {
		val clearDayParams = clearParams(DayPhase.DAY)
		val thunderOnlyParams = clearDayParams.copy(thunder = true)
		val clearDayLighting = lighting(clearDayParams)
		val thunderOnlyLighting = lighting(thunderOnlyParams)

		assertTrue(thunderOnlyLighting.directStrength < clearDayLighting.directStrength)
		assertTrue(thunderOnlyLighting.textureStrength < clearDayLighting.textureStrength)
	}

	@Test
	fun `dawn meets daylight continuously`() {
		assertContinuous(DayPhase.DAWN, DayPhase.DAY)
	}

	@Test
	fun `dusk meets night continuously`() {
		assertContinuous(DayPhase.DUSK, DayPhase.NIGHT)
	}

	@Test
	fun `reduced night brightness preserves dusk to night terrain continuity`() {
		for (brightness in listOf(0f, 0.4f)) {
			assertContinuous(DayPhase.DUSK, DayPhase.NIGHT, brightness)
		}
	}

	@Test
	fun `sky customization reaches ambient terrain light`() {
		val clear = clearParams(DayPhase.DAY)
		val natural = lighting(clear)

		assertNotEquals(natural.ambientColor, lighting(clear.copy(skyBrightnessScale = 0.5f)).ambientColor)
		assertNotEquals(natural.ambientColor, lighting(clear.copy(skyColorPreset = SkyColorPreset.WARM)).ambientColor)
	}

	@Test
	fun `weather changes restore cached lighting without retaining stale shading`() {
		val clear = clearParams(DayPhase.DAY)
		val reused = lighting(clear)
		reused.update(2400, 1080, clear.copy(thunder = true, fogDensity = 1f))
		reused.update(1080, 2400, clear)
		val fresh = lighting(clear)

		for (plane in SceneryPlane.entries) {
			assertEquals(surfaceColorFor(SceneryMaterial.ROCK, plane, 0.8f, fresh), surfaceColorFor(SceneryMaterial.ROCK, plane, 0.8f, reused))
		}
	}

	private fun assertContinuous(from: DayPhase, to: DayPhase, nightBrightness: Float = 1f) {
		val before = lighting(clearParams(from).copy(celestialProgress = 1f, nightBrightnessScale = nightBrightness))
		val after = lighting(clearParams(to).copy(celestialProgress = 0f, nightBrightnessScale = nightBrightness))

		for (material in listOf(SceneryMaterial.ROCK, SceneryMaterial.SNOW, SceneryMaterial.FOREST, SceneryMaterial.MEADOW)) {
			for (plane in SceneryPlane.entries) {
				for (diffuse in listOf(0f, 0.6f, 1f)) {
					val first = surfaceColorFor(material, plane, diffuse, before)
					val second = surfaceColorFor(material, plane, diffuse, after)
					val distance = abs((first ushr 16 and 255) - (second ushr 16 and 255)) + abs((first ushr 8 and 255) - (second ushr 8 and 255)) + abs((first and 255) - (second and 255))

					assertTrue("$material $plane $from/$to has RGB discontinuity $distance", distance <= 3)
				}
			}
		}
	}

	private fun clearParams(phase: DayPhase) = SceneParams(phase, 0f, 0f, null, false, 0f)

	private fun lighting(params: SceneParams) = SceneryLighting().apply { update(1080, 2400, params) }
}
