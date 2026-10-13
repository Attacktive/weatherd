package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.TreeStyle

private const val PORTRAIT = 9f / 16f
private const val EPSILON = 0.000001f

class TreeStyleSceneryTest {
	@Test
	fun `rounded preserves existing metropolis scenery exactly`() {
		assertEquals(
			sceneryOutlinesFor(BackdropScene.METROPOLIS, PORTRAIT),
			styledSceneryOutlinesFor(BackdropScene.METROPOLIS, PORTRAIT, TreeStyle.ROUNDED)
		)
	}

	@Test
	fun `conifer changes only metropolis near forest glyph outlines`() {
		val rounded = styledSceneryOutlinesFor(BackdropScene.METROPOLIS, PORTRAIT, TreeStyle.ROUNDED)!!
		val conifer = styledSceneryOutlinesFor(BackdropScene.METROPOLIS, PORTRAIT, TreeStyle.CONIFER)!!

		assertEquals(rounded.layers, conifer.layers)
		assertEquals(rounded.accents, conifer.accents)
		assertEquals(rounded.windows, conifer.windows)
		assertEquals(rounded.beacons, conifer.beacons)
		assertEquals(rounded.glyphs.size, conifer.glyphs.size)

		rounded.glyphs.zip(conifer.glyphs).forEach { (before, after) ->
			assertEquals(before.material, after.material)
			assertEquals(before.plane, after.plane)

			if (before.material == SceneryMaterial.FOREST && before.plane == SceneryPlane.NEAR) {
				assertNotEquals(before.outline, after.outline)
				assertEquals(before.outline.minOf { it.x }, after.outline.minOf { it.x }, EPSILON)
				assertEquals(before.outline.maxOf { it.x }, after.outline.maxOf { it.x }, EPSILON)
				assertEquals(before.outline.minOf { it.y }, after.outline.minOf { it.y }, EPSILON)
				assertEquals(before.outline.maxOf { it.y }, after.outline.maxOf { it.y }, EPSILON)
			} else {
				assertEquals(before, after)
			}
		}
	}

	@Test
	fun `tree style does not change other backdrops`() {
		for (scene in listOf(BackdropScene.NONE, BackdropScene.BEACH, BackdropScene.MOUNTAINS, BackdropScene.COUNTRYSIDE, BackdropScene.PHOTO)) {
			assertEquals(
				styledSceneryOutlinesFor(scene, PORTRAIT, TreeStyle.ROUNDED),
				styledSceneryOutlinesFor(scene, PORTRAIT, TreeStyle.CONIFER)
			)
		}
	}

	@Test
	fun `tree style is excluded from static backdrop signature`() {
		val rounded = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = 0.2f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0.2f,
			backdropScene = BackdropScene.METROPOLIS,
			treeStyle = TreeStyle.ROUNDED
		)

		assertEquals(
			backdropSignature(rounded),
			backdropSignature(rounded.copy(treeStyle = TreeStyle.CONIFER))
		)
	}

	@Test
	fun `debug params carry tree style`() {
		assertEquals(
			TreeStyle.CONIFER,
			debugSceneParams(
				preset = SCENE_PRESETS.first(),
				dayPhase = DayPhase.DAY,
				treeStyle = TreeStyle.CONIFER
			).treeStyle
		)
	}
}
