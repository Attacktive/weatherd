package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase

class CloudAppearanceTest {
	@Test
	fun `count scale changes rendered coverage without mutating observed cloudiness`() {
		val params = sceneParams(cloudiness = 0.4f, cloudCountScale = 1.5f)

		assertEquals(0.4f, params.cloudiness, 0.0001f)
		assertEquals(0.6f, effectiveCloudiness(params), 0.0001f)
	}

	@Test
	fun `effective cloudiness clamps at full coverage`() {
		assertEquals(1f, effectiveCloudiness(sceneParams(cloudiness = 0.8f, cloudCountScale = 2f)), 0.0001f)
	}

	private fun sceneParams(cloudiness: Float, cloudCountScale: Float) = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = cloudiness,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f,
		cloudCountScale = cloudCountScale
	)
}
