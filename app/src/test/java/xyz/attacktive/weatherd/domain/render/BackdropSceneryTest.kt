package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.BackdropScene

class BackdropSceneryTest {
	@Test
	fun `NONE has no scenery`() {
		assertNull(sceneryOutlinesFor(BackdropScene.NONE, PORTRAIT))
	}

	@Test
	fun `every scene is deterministic`() {
		for (scene in SCENERY_SCENES) {
			assertEquals(sceneryOutlinesFor(scene, PORTRAIT), sceneryOutlinesFor(scene, PORTRAIT))
		}
	}

	@Test
	fun `outlines span the full width left to right`() {
		forEachPlane { scene, label, outline ->
			assertEquals("$scene $label first x", 0f, outline.first().x, 0f)
			assertEquals("$scene $label last x", 1f, outline.last().x, 0f)

			outline.zipWithNext().forEach { (previous, next) ->
				assertTrue("$scene $label x must not go backwards at ${previous.x}", next.x >= previous.x)
			}
		}
	}

	@Test
	fun `outlines stay inside the horizon band`() {
		forEachPlane { scene, label, outline ->
			outline.forEach { point ->
				assertTrue("$scene $label y=${point.y}", point.y in 0.69f..0.965f)
			}
		}
	}

	@Test
	fun `the far plane rises higher than the near plane`() {
		for (scene in SCENERY_SCENES) {
			val outlines = sceneryOutlinesFor(scene, PORTRAIT)!!
			val farHighest = farCrest(outlines)
			val nearHighest = outlines.near.minOf { it.y }

			assertTrue("$scene far=$farHighest near=$nearHighest", farHighest < nearHighest)
		}
	}

	@Test
	fun `the beach exposes a sea reflection line`() {
		val outlines = sceneryOutlinesFor(BackdropScene.BEACH, PORTRAIT)!!

		assertEquals(0.85f, outlines.reflectionY!!, 0f)
	}

	@Test
	fun `only the beach carries a reflection line`() {
		for (scene in SCENERY_SCENES) {
			val outlines = sceneryOutlinesFor(scene, PORTRAIT)!!
			if (scene == BackdropScene.BEACH) {
				assertTrue(outlines.reflectionY != null)
			} else {
				assertNull("$scene should not reflect", outlines.reflectionY)
			}
		}
	}

	@Test
	fun `the beach carries gulls parasols ships and marine life`() {
		val outlines = sceneryOutlinesFor(BackdropScene.BEACH, PORTRAIT)!!

		assertTrue(outlines.gulls.isNotEmpty())
		assertTrue(outlines.parasols.isNotEmpty())
		assertTrue(outlines.marine.any { it.kind == SceneryFaunaKind.SHARK })
		assertTrue(outlines.marine.any { it.kind == SceneryFaunaKind.WHALE })
		assertTrue(outlines.accents.isEmpty())
		assertTrue(outlines.parasols.all { it.x < 0.35f })

		// Planted on the bluff crest so the canopy can stick into the sky.
		assertTrue(outlines.parasols.all { it.y in 0.82f..0.88f })

		// One sloop: dark hull and mast under two off-white sails.
		assertEquals(
			listOf(SceneryMaterial.HULL, SceneryMaterial.HULL, SceneryMaterial.SAIL, SceneryMaterial.SAIL),
			outlines.glyphs.map { it.material }
		)

		// The boat sits over open water, not under the bluff, with the rig visibly clear of the waterline.
		assertTrue(outlines.glyphs.all { ship -> ship.outline.first().x >= 0.5f })
		assertTrue(outlines.glyphs.any { ship -> ship.outline.any { it.y < 0.83f } })
	}

	@Test
	fun `the beach is painted in water and sand`() {
		val outlines = sceneryOutlinesFor(BackdropScene.BEACH, PORTRAIT)!!

		assertEquals(
			listOf(SceneryMaterial.WATER, SceneryMaterial.SAND),
			outlines.layers.map { it.material }
		)
	}

	@Test
	fun `the metropolis is painted in steel and masonry with a park`() {
		val outlines = sceneryOutlinesFor(BackdropScene.METROPOLIS, PORTRAIT)!!

		assertEquals(
			listOf(SceneryMaterial.STEEL, SceneryMaterial.MASONRY, SceneryMaterial.MEADOW),
			outlines.layers.map { it.material }
		)

		// Park trees front the masonry blocks; four dark crane parts stand over the construction site.
		assertTrue(outlines.glyphs.any { it.material == SceneryMaterial.FOREST })
		assertEquals(4, outlines.glyphs.count { it.material == SceneryMaterial.HULL })
		assertTrue(outlines.glyphs.all { it.plane == SceneryPlane.NEAR })
	}

	@Test
	fun `the countryside is painted in pasture and meadow with a wheat strip`() {
		val outlines = sceneryOutlinesFor(BackdropScene.COUNTRYSIDE, PORTRAIT)!!

		assertEquals(
			listOf(SceneryMaterial.PASTURE, SceneryMaterial.MEADOW, SceneryMaterial.WHEAT),
			outlines.layers.map { it.material }
		)
	}

	@Test
	fun `the mountains are painted in rock and forest with snowcaps`() {
		for (aspect in listOf(PORTRAIT, 1f, 2f)) {
			val outlines = sceneryOutlinesFor(BackdropScene.MOUNTAINS, aspect)!!

			assertEquals(
				listOf(SceneryMaterial.ROCK, SceneryMaterial.FOREST, SceneryMaterial.MEADOW),
				outlines.layers.map { it.material }
			)

			assertTrue("snow must fall on some summit at $aspect", outlines.glyphs.isNotEmpty())
			assertTrue(outlines.glyphs.all { it.material == SceneryMaterial.SNOW })

			// Caps hug the far crest band: never above the tallest possible summit, never below the deepest snowline plus its dip.
			for (cap in outlines.glyphs) {
				assertTrue("caps need enough points to read as a cap", cap.outline.size >= 4)
				assertTrue(cap.outline.all { it.y in 0.69f..0.83f })
			}
		}
	}

	@Test
	fun `the countryside carries a painted farmhouse and a grounded windmill`() {
		val outlines = sceneryOutlinesFor(BackdropScene.COUNTRYSIDE, PORTRAIT)!!
		val mill = outlines.windmill!!

		// A red barn body with a dark chimney and roof, standing over the near plane.
		assertEquals(
			listOf(SceneryMaterial.BARN, SceneryMaterial.HULL, SceneryMaterial.HULL),
			outlines.glyphs.map { it.material }
		)
		assertTrue(outlines.glyphs.all { it.plane == SceneryPlane.NEAR })
		assertTrue(outlines.glyphs.all { glyph -> glyph.outline.all { it.y in 0.78f..0.955f } })

		// Fence: six posts plus two rails threaded through them.
		assertEquals(8, outlines.accents.size)
		assertTrue("hub must sit above the hill", mill.hubY < mill.groundY)
		assertTrue("tower must reach the hill, not float", mill.groundY - mill.hubY >= 0.02f)
		assertTrue("tower should stay short so sails aren't sky-high", mill.groundY - mill.hubY <= 0.06f)

		// Tower is welded into the near outline at the hub — not a separate floating draw.
		assertTrue(
			"near outline should include the mill hub height",
			outlines.near.any { kotlin.math.abs(it.x - mill.hubX) < 0.02f && kotlin.math.abs(it.y - mill.hubY) < 1e-3f }
		)
	}

	@Test
	fun `the barn keeps its proportions at every aspect`() {
		val ratios = listOf(PORTRAIT, 1f, 2f).map { aspect ->
			val points = sceneryOutlinesFor(BackdropScene.COUNTRYSIDE, aspect)!!.glyphs.flatMap { it.outline }
			val width = points.maxOf { it.x } - points.minOf { it.x }
			val height = points.maxOf { it.y } - points.minOf { it.y }

			// On-screen height over on-screen width: dividing by the aspect converts the unit-frame width into height units.
			height / (width * aspect)
		}

		ratios.zipWithNext().forEach { (a, b) ->
			assertEquals("on-screen proportions must not drift with aspect", a, b, 0.01f)
		}
	}

	@Test
	fun `the metropolis puts beacons on the tallest roofs and the crane apex`() {
		val outlines = sceneryOutlinesFor(BackdropScene.METROPOLIS, PORTRAIT)!!

		assertTrue(outlines.windows.isNotEmpty())
		assertTrue(outlines.windows.size <= 72)
		assertEquals(4, outlines.beacons.size)

		outlines.beacons.forEach { point ->
			assertTrue(point.x in 0f..1f)
			assertTrue(point.y in 0.69f..0.965f)
		}

		// The first three are the tallest far roofs: each matches a far-plane roof y exactly; the fourth rides the crane apex.
		val farRoofYs = outlines.far.map { it.y }.toSet()
		val roofBeacons = outlines.beacons.take(3)
		roofBeacons.forEach { beacon ->
			assertTrue("beacon y=${beacon.y} should sit on a roof", farRoofYs.any { kotlin.math.abs(it - beacon.y) < 1e-4f })
		}

		assertTrue(roofBeacons.maxOf { it.y } <= outlines.far.minOf { it.y } + 0.05f)
	}

	@Test
	fun `landscape metropolis keeps the same sparse beacon count`() {
		val portrait = sceneryOutlinesFor(BackdropScene.METROPOLIS, PORTRAIT)!!
		val landscape = sceneryOutlinesFor(BackdropScene.METROPOLIS, 2f)!!

		assertEquals(portrait.beacons.size, landscape.beacons.size)
		assertEquals(4, landscape.beacons.size)
	}

	@Test
	fun `non-city scenes stay dark at night`() {
		for (scene in SCENERY_SCENES.filter { it != BackdropScene.METROPOLIS }) {
			assertTrue(sceneryOutlinesFor(scene, PORTRAIT)!!.windows.isEmpty())
			assertTrue(sceneryOutlinesFor(scene, PORTRAIT)!!.beacons.isEmpty())
		}
	}

	@Test
	fun `accents stay inside the unit frame and the horizon band`() {
		for (scene in SCENERY_SCENES) {
			for (aspect in listOf(PORTRAIT, 1f, 2f)) {
				val outlines = sceneryOutlinesFor(scene, aspect)!!

				outlines.accents.forEach { accent ->
					assertTrue("$scene accents need at least two points to stroke", accent.size >= 2)

					accent.forEach { point ->
						assertTrue("$scene accent x=${point.x}", point.x in 0f..1f)
						assertTrue("$scene accent y=${point.y}", point.y in 0.69f..0.965f)
					}
				}
			}
		}
	}

	@Test
	fun `wider screens get more skyline detail`() {
		val portrait = sceneryOutlinesFor(BackdropScene.METROPOLIS, PORTRAIT)!!
		val landscape = sceneryOutlinesFor(BackdropScene.METROPOLIS, 2f)!!

		assertTrue(landscape.near.size > portrait.near.size)
	}

	private fun farCrest(outlines: SceneryOutlines): Float {
		val glyphCrest = outlines.glyphs
			.filter { it.plane == SceneryPlane.FAR }
			.minOfOrNull { glyph -> glyph.outline.minOf { it.y } } ?: 1f

		return minOf(outlines.far.minOf { it.y }, glyphCrest)
	}

	private fun forEachPlane(assertion: (BackdropScene, String, List<OutlinePoint>) -> Unit) {
		for (scene in SCENERY_SCENES) {
			for (aspect in listOf(PORTRAIT, 1f, 2f)) {
				val outlines = sceneryOutlinesFor(scene, aspect)!!
				assertion(scene, "far@$aspect", outlines.far)
				assertion(scene, "near@$aspect", outlines.near)
			}
		}
	}

	companion object {
		private const val PORTRAIT = 0.46f
		private val SCENERY_SCENES = BackdropScene.entries.filter { it != BackdropScene.NONE }
	}
}

/** The classic depth-plane views over the layered contract, so the geometry pins read as before. */
private val SceneryOutlines.far: List<OutlinePoint>
	get() = layers.first { it.plane == SceneryPlane.FAR }.outline

private val SceneryOutlines.near: List<OutlinePoint>
	get() = layers.first { it.plane == SceneryPlane.NEAR }.outline
