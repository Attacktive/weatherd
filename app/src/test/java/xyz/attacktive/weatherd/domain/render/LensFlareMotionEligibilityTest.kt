package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

class LensFlareMotionEligibilityTest {
	private val clear = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f
	)

	@Test
	fun `sensor collection requires an explicit opt in and visible lens flare`() {
		assertFalse(lensFlareMotionActive(clear))
		val enabled = clear.copy(lensFlareMotionEnabled = true)
		assertTrue(lensFlareMotionActive(enabled))
		assertFalse(lensFlareMotionActive(enabled.copy(lensFlareEnabled = false)))
		assertFalse(lensFlareMotionActive(enabled.copy(sunVisible = false)))
	}

	@Test
	fun `hidden reflections do not request sensor collection`() {
		val enabled = clear.copy(lensFlareMotionEnabled = true)
		val hiddenScenes = listOf(
			enabled.copy(dayPhase = DayPhase.NIGHT),
			enabled.copy(dayPhase = DayPhase.DUSK, celestialProgress = 1f),
			enabled.copy(cloudiness = 0.9f),
			enabled.copy(cloudiness = 0.4f, cloudCountScale = 2f),
			enabled.copy(fogDensity = 1f),
			enabled.copy(precipitation = Precipitation(PrecipitationKind.RAIN, 1f, 1f))
		)

		for (scene in hiddenScenes) {
			assertFalse("Hidden reflections must not keep sensors active for $scene", lensFlareMotionActive(scene))
		}
	}

	@Test
	fun `motion preference cannot invalidate the cached sky`() {
		assertEquals(backdropSignature(clear), backdropSignature(clear.copy(lensFlareMotionEnabled = true)))
	}
}
