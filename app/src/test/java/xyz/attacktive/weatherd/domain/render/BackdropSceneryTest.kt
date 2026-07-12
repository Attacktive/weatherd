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
			val farHighest = outlines.far.minOf { it.y }
			val nearHighest = outlines.near.minOf { it.y }

			assertTrue("$scene far=$farHighest near=$nearHighest", farHighest < nearHighest)
		}
	}

	@Test
	fun `the beach carries accent marks between the planes`() {
		val outlines = sceneryOutlinesFor(BackdropScene.BEACH, PORTRAIT)!!

		assertTrue(outlines.accents.isNotEmpty())
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
