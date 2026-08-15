package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

class ScenePaletteTest {
	@Test
	fun `silhouette takes the plane tone exactly`() {
		for (phase in DayPhase.entries) {
			val params = clearParams(phase)
			val skyBottom = skyGradientFor(params).bottomColor

			for (plane in SceneryPlane.entries) {
				assertEquals(
					"$phase $plane",
					sceneryPlaneTone(plane, skyBottom),
					sceneryLayerColor(SceneryMaterial.SILHOUETTE, plane, params, skyBottom)
				)
			}
		}
	}

	@Test
	fun `clear day keeps painted materials distinct from the silhouette tone`() {
		val params = clearParams(DayPhase.DAY)
		val skyBottom = skyGradientFor(params).bottomColor

		val water = sceneryLayerColor(SceneryMaterial.WATER, SceneryPlane.FAR, params, skyBottom)
		val sand = sceneryLayerColor(SceneryMaterial.SAND, SceneryPlane.NEAR, params, skyBottom)
		val pasture = sceneryLayerColor(SceneryMaterial.PASTURE, SceneryPlane.FAR, params, skyBottom)
		val wheat = sceneryLayerColor(SceneryMaterial.WHEAT, SceneryPlane.NEAR, params, skyBottom)

		// Water reads cool, sand and wheat warm, pasture green — the point of painting at all.
		assertTrue("water should be blue/green dominant", blue(water) > red(water))
		assertTrue("sand should be warm", red(sand) > blue(sand))
		assertTrue("pasture should be green dominant", green(pasture) > red(pasture) && green(pasture) > blue(pasture))
		assertTrue("wheat should be warm", red(wheat) > blue(wheat))
	}

	@Test
	fun `a thunderstorm collapses paint to the silhouette tone`() {
		val params = clearParams(DayPhase.DAY).copy(
			cloudiness = 0.75f,
			precipitation = Precipitation(PrecipitationKind.RAIN, SEVERITY_STORM, observed = 0.9f),
			thunder = true
		)
		val skyBottom = skyGradientFor(params).bottomColor

		for (material in SceneryMaterial.entries) {
			assertEquals(
				"$material must gray out in a storm",
				sceneryPlaneTone(SceneryPlane.NEAR, skyBottom),
				sceneryLayerColor(material, SceneryPlane.NEAR, params, skyBottom)
			)
		}
	}

	@Test
	fun `night sits within a hair of the silhouette tone`() {
		val params = clearParams(DayPhase.NIGHT)
		val skyBottom = skyGradientFor(params).bottomColor
		val tone = sceneryPlaneTone(SceneryPlane.NEAR, skyBottom)
		val sand = sceneryLayerColor(SceneryMaterial.SAND, SceneryPlane.NEAR, params, skyBottom)

		// Night keeps 5% of the intrinsic color, so the residue across three channels stays under ~30 of 765.
		assertTrue("night sand should be nearly silhouette", channelDistance(sand, tone) <= 30)
	}

	@Test
	fun `the far plane sits deeper in atmosphere than the near plane`() {
		val params = clearParams(DayPhase.DAY)
		val skyBottom = skyGradientFor(params).bottomColor

		val nearWater = sceneryLayerColor(SceneryMaterial.WATER, SceneryPlane.NEAR, params, skyBottom)
		val farWater = sceneryLayerColor(SceneryMaterial.WATER, SceneryPlane.FAR, params, skyBottom)
		val nearTone = sceneryPlaneTone(SceneryPlane.NEAR, skyBottom)
		val farTone = sceneryPlaneTone(SceneryPlane.FAR, skyBottom)

		assertTrue(
			"far water should sit closer to its plane tone than near water does",
			channelDistance(farWater, farTone) < channelDistance(nearWater, nearTone)
		)
	}

	private fun clearParams(dayPhase: DayPhase) = SceneParams(
		dayPhase = dayPhase,
		cloudiness = 0.05f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0.3f
	)

	private fun red(color: Int) = color ushr 16 and 0xFF

	private fun green(color: Int) = color ushr 8 and 0xFF

	private fun blue(color: Int) = color and 0xFF

	private fun channelDistance(a: Int, b: Int) = abs(red(a) - red(b)) + abs(green(a) - green(b)) + abs(blue(a) - blue(b))
}
