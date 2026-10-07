package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

class CloudCoverageTest {
	@Test
	fun `banks grow above a retained dry population without an opacity dip`() {
		for (cover in listOf(0.70f, 0.72f, 0.75f, 0.80f, 0.85f, 0.90f)) {
			assertTrue(shouldDrawDryClouds(scene(cover)))
		}

		assertEquals(0f, overcastBankStrength(scene(0.70f)), 0.0001f)
		assertEquals(1f, overcastBankStrength(scene(0.85f)), 0.0001f)
		assertTrue(overcastBankStrength(scene(0.72f)) < overcastBankStrength(scene(0.80f)))
	}

	@Test
	fun `initial sparse population does not pop in at the clear boundary`() {
		assertEquals(0f, initialCumulusPopulationStrength(0.10f), 0.0001f)
		assertTrue(initialCumulusPopulationStrength(0.15f) in 0.01f..0.99f)
		assertEquals(1f, initialCumulusPopulationStrength(0.20f), 0.0001f)
		assertEquals(1f, initialCumulusPopulationStrength(0.90f), 0.0001f)
	}

	@Test
	fun `incoming population grows immediately above its boundary`() {
		for (boundary in listOf(0.10f, 0.10f + 0.65f / 3f, 0.10f + 1.30f / 3f)) {
			assertTrue(nearCumulusPopulationStep(boundary + 0.001f) > nearCumulusPopulationStep(boundary - 0.001f))
		}

		assertTrue(nearCumulusPopulationStep(0.74f) > nearCumulusPopulationStep(0.70f))
		assertEquals(0f, nearCumulusPopulationStep(-1f), 0f)
		assertEquals(3f, nearCumulusPopulationStep(1f), 0f)
	}

	@Test
	fun `dense dry fog suppresses both cloud families`() {
		for (fog in listOf(0.8f, 1f)) {
			val params = scene(0.9f).copy(fogDensity = fog)
			assertEquals(0f, overcastBankStrength(params), 0f)
			assertFalse(shouldDrawDryClouds(params))
		}
	}

	@Test
	fun `precipitation keeps banks despite fog`() {
		for (precipitation in listOf(Precipitation(PrecipitationKind.RAIN, 0.35f, 0.1f), Precipitation(PrecipitationKind.RAIN, 0.65f, 0.5f), Precipitation(PrecipitationKind.SNOW, 0.65f, 0.5f))) {
			val params = scene(0.05f).copy(fogDensity = 1f, precipitation = precipitation)
			assertEquals(1f, overcastBankStrength(params), 0f)
			assertFalse(shouldDrawDryClouds(params))
		}
	}

	@Test
	fun `high cloud cannot enable an opaque family`() {
		val params = scene(0.8f, SceneCloudLayers(0.05f, 0.05f, 0.8f))
		assertEquals(0f, overcastBankStrength(params), 0f)
		assertFalse(shouldDrawDryClouds(params))
	}

	private fun scene(cover: Float, layers: SceneCloudLayers? = null) = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = cover,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f,
		cloudLayers = layers
	)
}
