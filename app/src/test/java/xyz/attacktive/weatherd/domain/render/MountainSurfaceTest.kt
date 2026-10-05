package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase

class MountainSurfaceTest {
	@Test
	fun `surface preparation preserves seeded geometry and is deterministic`() {
		for (aspect in listOf(1080f / 2400f, 2400f / 1080f)) {
			val outlines = checkNotNull(sceneryOutlinesFor(BackdropScene.MOUNTAINS, aspect))
			val originalLayers = outlines.layers.map { it.copy(outline = it.outline.toList()) }
			val originalGlyphs = outlines.glyphs.map { it.copy(outline = it.outline.toList()) }
			val first = mountainSurfacesFor(outlines, aspect)

			assertEquals(first, mountainSurfacesFor(outlines, aspect))
			assertEquals(originalLayers, outlines.layers)
			assertEquals(originalGlyphs, outlines.glyphs)
			assertEquals(outlines.layers.indices.toList(), first.map { it.layerIndex })
		}
	}

	@Test
	fun `surface normals are normalized and aspect aware`() {
		for (aspect in listOf(0.45f, 2.2f)) {
			val outlines = checkNotNull(sceneryOutlinesFor(BackdropScene.MOUNTAINS, aspect))

			for (surface in mountainSurfacesFor(outlines, aspect)) {
				for (patch in surface.patches) {
					assertTrue(abs(patch.normalX * patch.normalX + patch.normalY * patch.normalY + patch.normalZ * patch.normalZ - 1f) < 0.0001f)
				}
			}
		}

		val first = mountainSurfacesFor(physicalRidge(1f), 1f).single().patches
		val wider = mountainSurfacesFor(physicalRidge(2f), 2f).single().patches
		assertEquals(first.size, wider.size)

		for (index in first.indices) {
			assertEquals(first[index].normalX, wider[index].normalX, 0.0001f)
			assertEquals(first[index].normalY, wider[index].normalY, 0.0001f)
			assertEquals(first[index].normalZ, wider[index].normalZ, 0.0001f)
		}
	}

	@Test
	fun `diffuse response clamps back facing surfaces`() {
		val lighting = SceneryLighting().apply { update(1080, 2400, SceneParams(DayPhase.DAY, 0f, 0f, null, false, 0f)) }
		val aligned = MountainSurfacePatch(emptyList(), lighting.directionX, lighting.directionY, lighting.directionZ)
		val opposed = aligned.copy(normalX = -aligned.normalX, normalY = -aligned.normalY, normalZ = -aligned.normalZ)

		assertEquals(1f, surfaceDiffuseFor(aligned, lighting), 0.0001f)
		assertEquals(0f, surfaceDiffuseFor(opposed, lighting), 0f)
	}

	@Test
	fun `night material color ignores directional response`() {
		val lighting = SceneryLighting().apply { update(1080, 2400, SceneParams(DayPhase.NIGHT, 0f, 0f, null, false, 0f)) }

		for (material in listOf(SceneryMaterial.ROCK, SceneryMaterial.SNOW, SceneryMaterial.FOREST, SceneryMaterial.MEADOW)) {
			for (plane in SceneryPlane.entries) {
				assertEquals(surfaceColorFor(material, plane, 0f, lighting), surfaceColorFor(material, plane, 1f, lighting))
			}
		}
	}

	/** Identical physical slopes encoded at two viewport aspect ratios. */
	private fun physicalRidge(aspect: Float) = SceneryOutlines(
		layers = listOf(
			SceneryLayer(
				listOf(OutlinePoint(0f, 0.85f), OutlinePoint(0.2f / aspect, 0.7f), OutlinePoint(0.4f / aspect, 0.85f)),
				SceneryMaterial.ROCK,
				SceneryPlane.FAR
			)
		)
	)
}
