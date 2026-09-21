package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
		val steel = sceneryLayerColor(SceneryMaterial.STEEL, SceneryPlane.FAR, params, skyBottom)
		val masonry = sceneryLayerColor(SceneryMaterial.MASONRY, SceneryPlane.NEAR, params, skyBottom)

		// Water reads cool, sand and wheat warm, pasture green — the point of painting at all.
		assertTrue("water should be blue/green dominant", blue(water) > red(water))
		assertTrue("sand should be warm", red(sand) > blue(sand))
		assertTrue("pasture should be green dominant", green(pasture) > red(pasture) && green(pasture) > blue(pasture))
		assertTrue("wheat should be warm", red(wheat) > blue(wheat))
		assertTrue("steel should be cool", blue(steel) > red(steel))
		assertTrue("masonry should be warm", red(masonry) > blue(masonry))
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

	@Test
	fun `a scattered sky keeps the clear day blue`() {
		val clear = skyGradientFor(clearParams(DayPhase.DAY))
		val scattered = skyGradientFor(clearParams(DayPhase.DAY).copy(cloudiness = 0.5f))

		// Cumulus darken a sky by covering it, so the gaps between them stay as blue as an empty sky does.
		assertEquals("scattered cloud must not drain the sky's blue", clear, scattered)
	}

	@Test
	fun `the sky starts graying where the overcast ceiling starts drawing`() {
		val clear = skyGradientFor(clearParams(DayPhase.DAY))
		val atThreshold = skyGradientFor(clearParams(DayPhase.DAY).copy(cloudiness = 0.55f))
		val pastThreshold = skyGradientFor(clearParams(DayPhase.DAY).copy(cloudiness = 0.65f))

		assertEquals("blue must hold right up to the ceiling threshold", clear, atThreshold)
		assertNotEquals("past the threshold the sky must start graying", clear, pastThreshold)
	}

	@Test
	fun `cloud count drives the same dry palette as equivalent observed coverage`() {
		val clear = skyGradientFor(clearParams(DayPhase.DAY))
		val scaled = clearParams(DayPhase.DAY).copy(cloudiness = 0.45f, cloudCountScale = 2f)
		val equivalent = clearParams(DayPhase.DAY).copy(cloudiness = 0.9f)

		assertEquals(0.45f, scaled.cloudiness, 0.0001f)
		assertNotEquals(clear, skyGradientFor(scaled))
		assertEquals(skyGradientFor(equivalent), skyGradientFor(scaled))
	}

	@Test
	fun `the overcast ceiling fades in instead of jumping at the threshold`() {
		assertEquals(0f, overcastCeilingStrength(0.55f), 0.0001f)
		assertEquals(1f / 3f, overcastCeilingStrength(0.65f), 0.0001f)
		assertEquals(1f, overcastCeilingStrength(0.85f), 0.0001f)
	}

	@Test
	fun `an overcast sky gives up its blue entirely`() {
		val overcast = skyGradientFor(clearParams(DayPhase.DAY).copy(cloudiness = 0.85f))

		// Both ends have reached the same gray, so the gradient has no blue left to lose.
		assertEquals("a fully overcast sky should flatten to the phase gray", overcast.topColor, overcast.bottomColor)
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
