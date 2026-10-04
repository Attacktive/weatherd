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
	fun `known high cloud stays out of opaque and cumulus coverage`() {
		val params = sceneParams(
			cloudiness = 0.8f,
			cloudCountScale = 1f,
			cloudLayers = SceneCloudLayers(low = 0.05f, mid = 0.05f, high = 0.8f)
		)

		assertEquals(0.8f, effectiveCloudiness(params), 0.0001f)
		assertEquals(0.05f, effectiveLowCloudiness(params), 0.0001f)
		assertEquals(0.05f, effectiveMidCloudiness(params), 0.0001f)
		assertEquals(0.8f, effectiveHighCloudiness(params), 0.0001f)
		assertEquals(0.05f, effectiveOpaqueCloudiness(params), 0.0001f)
	}

	@Test
	fun `missing layers preserve legacy total cover behavior`() {
		val params = sceneParams(cloudiness = 0.8f, cloudCountScale = 1f)

		assertEquals(0.8f, effectiveLowCloudiness(params), 0.0001f)
		assertEquals(0.8f, effectiveMidCloudiness(params), 0.0001f)
		assertEquals(0f, effectiveHighCloudiness(params), 0.0001f)
		assertEquals(0.8f, effectiveOpaqueCloudiness(params), 0.0001f)
	}

	@Test
	fun `effective cloudiness clamps at full coverage`() {
		assertEquals(1f, effectiveCloudiness(sceneParams(cloudiness = 0.8f, cloudCountScale = 2f)), 0.0001f)
	}

	private fun sceneParams(cloudiness: Float, cloudCountScale: Float, cloudLayers: SceneCloudLayers? = null) = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = cloudiness,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f,
		cloudLayers = cloudLayers,
		cloudCountScale = cloudCountScale
	)
}
