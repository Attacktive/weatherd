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
		assertTrue(outlines.ships.size >= 2)
		assertTrue(outlines.marine.any { it.kind == SceneryFaunaKind.SHARK })
		assertTrue(outlines.marine.any { it.kind == SceneryFaunaKind.WHALE })
		assertTrue(outlines.accents.isEmpty())
		assertTrue(outlines.parasols.all { it.x < 0.35f })

		// Planted on the bluff crest so the canopy can stick into the sky.
		assertTrue(outlines.parasols.all { it.y in 0.82f..0.88f })

		// Ships sit over open water, not under the bluff.
		assertTrue(outlines.ships.all { ship -> ship.first().x >= 0.5f })
		assertTrue(outlines.ships.any { ship -> ship.any { it.y < 0.80f } })
	}

	@Test
	fun `the countryside carries a farmhouse bump and a grounded windmill`() {
		val outlines = sceneryOutlinesFor(BackdropScene.COUNTRYSIDE, PORTRAIT)!!
		val nearHighest = outlines.near.minOf { it.y }
		val mill = outlines.windmill!!

		assertTrue("farmhouse or mill should rise above the bare hill crest", nearHighest < 0.86f)
		assertTrue(outlines.accents.isNotEmpty())
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
	fun `the metropolis puts a few beacons on the tallest roofs only`() {
		val outlines = sceneryOutlinesFor(BackdropScene.METROPOLIS, PORTRAIT)!!

		assertTrue(outlines.windows.isNotEmpty())
		assertTrue(outlines.windows.size <= 72)
		assertEquals(3, outlines.beacons.size)

		outlines.beacons.forEach { point ->
			assertTrue(point.x in 0f..1f)
			assertTrue(point.y in 0.69f..0.965f)
		}

		// Beacons are the tallest roofs: each should match a far-plane roof y, not float above every building.
		val farRoofYs = outlines.far.map { it.y }.toSet()
		outlines.beacons.forEach { beacon ->
			assertTrue("beacon y=${beacon.y} should sit on a roof", farRoofYs.any { kotlin.math.abs(it - beacon.y) < 1e-4f })
		}

		val beaconYs = outlines.beacons.map { it.y }
		assertTrue(beaconYs.max() <= outlines.far.minOf { it.y } + 0.05f)
	}

	@Test
	fun `landscape metropolis keeps the same sparse beacon count`() {
		val portrait = sceneryOutlinesFor(BackdropScene.METROPOLIS, PORTRAIT)!!
		val landscape = sceneryOutlinesFor(BackdropScene.METROPOLIS, 2f)!!

		assertEquals(portrait.beacons.size, landscape.beacons.size)
		assertEquals(3, landscape.beacons.size)
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
		val shipCrest = outlines.ships.minOfOrNull { ship -> ship.minOf { it.y } } ?: 1f

		return minOf(outlines.far.minOf { it.y }, shipCrest)
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
