package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.sqrt
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
	fun `clear day lights right-facing facets brighter and warmer than left-facing ones`() {
		val params = clearParams(DayPhase.DAY)
		val skyBottom = skyGradientFor(params).bottomColor
		val light = lightDirectionFor(params)
		val right = SceneryFacet(emptyList(), normalX = 0.35f, normalY = -0.9367497f)
		val left = SceneryFacet(emptyList(), normalX = -0.35f, normalY = -0.9367497f)

		val lit = sceneryFacetColor(SceneryMaterial.ROCK, SceneryPlane.NEAR, params, skyBottom, right, light)
		val shaded = sceneryFacetColor(SceneryMaterial.ROCK, SceneryPlane.NEAR, params, skyBottom, left, light)

		assertTrue("right-facing facet should be brighter", brightness(lit) > brightness(shaded))
		assertTrue("right-facing facet should be warmer", warmth(lit) > warmth(shaded))
		assertTrue("daylight face contrast should survive phone-scale rendering", channelDistance(lit, shaded) >= 15)
	}

	@Test
	fun `night and thunder collapse facets onto the plain layer color`() {
		val storm = clearParams(DayPhase.DAY).copy(
			cloudiness = 0.75f,
			precipitation = Precipitation(PrecipitationKind.RAIN, SEVERITY_STORM, observed = 0.9f),
			thunder = true
		)

		val facets = listOf(
			SceneryFacet(emptyList(), normalX = 0.35f, normalY = -0.9367497f),
			SceneryFacet(emptyList(), normalX = -0.35f, normalY = -0.9367497f)
		)

		for (params in listOf(storm, clearParams(DayPhase.NIGHT))) {
			val skyBottom = skyGradientFor(params).bottomColor
			val light = lightDirectionFor(params)

			for ((material, plane) in listOf(
				SceneryMaterial.ROCK to SceneryPlane.FAR,
				SceneryMaterial.FOREST to SceneryPlane.NEAR
			)) {
				val plain = sceneryLayerColor(material, plane, params, skyBottom)

				for (facet in facets) {
					assertEquals(plain, sceneryFacetColor(material, plane, params, skyBottom, facet, light))
				}
			}
		}
	}

	@Test
	fun `far facet contrast is lower than near contrast for the same normal`() {
		val params = clearParams(DayPhase.DAY)
		val skyBottom = skyGradientFor(params).bottomColor
		val light = lightDirectionFor(params)
		val facet = SceneryFacet(emptyList(), normalX = 0.35f, normalY = -0.9367497f)

		val nearBase = sceneryLayerColor(SceneryMaterial.ROCK, SceneryPlane.NEAR, params, skyBottom)
		val farBase = sceneryLayerColor(SceneryMaterial.ROCK, SceneryPlane.FAR, params, skyBottom)
		val nearFacet = sceneryFacetColor(SceneryMaterial.ROCK, SceneryPlane.NEAR, params, skyBottom, facet, light)
		val farFacet = sceneryFacetColor(SceneryMaterial.ROCK, SceneryPlane.FAR, params, skyBottom, facet, light)

		assertTrue(channelDistance(farFacet, farBase) < channelDistance(nearFacet, nearBase))
	}

	@Test
	fun `key light is unit length and points up right in every phase`() {
		for (phase in DayPhase.entries) {
			val direction = lightDirectionFor(clearParams(phase))
			val length = sqrt(direction.x * direction.x + direction.y * direction.y)

			assertEquals("$phase light must be normalized", 1f, length, 0.0001f)
			assertTrue("$phase light must point right", direction.x > 0f)
			assertTrue("$phase light must point up", direction.y < 0f)
		}
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

	private fun brightness(color: Int) = red(color) + green(color) + blue(color)

	private fun warmth(color: Int) = red(color) - blue(color)

	private fun channelDistance(a: Int, b: Int) = abs(red(a) - red(b)) + abs(green(a) - green(b)) + abs(blue(a) - blue(b))
}
