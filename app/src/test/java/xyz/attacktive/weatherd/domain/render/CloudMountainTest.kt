package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.availableBackdropScenes
import xyz.attacktive.weatherd.domain.model.drawsScenery
import xyz.attacktive.weatherd.domain.model.toAppearancePresetSnapshot

class CloudMountainTest {
	@Test
	fun freshInstallRetainsBareSky() {
		assertEquals(BackdropScene.NONE, AppSettings().backdropScene)
	}

	@Test
	fun newPickerOffersOnlyCurrentChoices() {
		assertEquals(
			listOf(BackdropScene.NONE, BackdropScene.PHOTO, BackdropScene.CLOUD_MOUNTAINS),
			availableBackdropScenes(BackdropScene.NONE)
		)
	}

	@Test
	fun legacySceneryRemainsAvailableWhileSelected() {
		val supported = listOf(BackdropScene.NONE, BackdropScene.PHOTO, BackdropScene.CLOUD_MOUNTAINS)
		for (scene in BackdropScene.entries) {
			val expected = if (scene.drawsScenery) {
				supported + scene
			} else {
				supported
			}

			assertEquals(expected, availableBackdropScenes(scene))
			assertTrue(scene in availableBackdropScenes(scene))
		}
	}

	@Test
	fun oldAndNewStoredBackdropNamesRemainReadable() {
		for (scene in BackdropScene.entries) {
			assertEquals(scene, BackdropScene.fromName(scene.name))
		}

		assertEquals(BackdropScene.NONE, BackdropScene.fromName("unknown"))
		assertFalse(BackdropScene.CLOUD_MOUNTAINS.drawsScenery)
	}

	@Test
	fun mountainBackdropsParticipateInAppearancePresets() {
		assertEquals(
			BackdropScene.CLOUD_MOUNTAINS,
			AppSettings(backdropScene = BackdropScene.CLOUD_MOUNTAINS).toAppearancePresetSnapshot().backdropScene
		)
		assertEquals(
			BackdropScene.MOUNTAINS,
			AppSettings(backdropScene = BackdropScene.MOUNTAINS).toAppearancePresetSnapshot().backdropScene
		)
	}

	@Test
	fun mountainArtworkPreservesAspectRatioAtDifferentWidths() {
		assertEquals(360f * 430f / 697f, cloudMountainArtworkHeight(360f, 697, 430), 0.001f)
		assertEquals(780f * 430f / 697f, cloudMountainArtworkHeight(780f, 697, 430), 0.001f)
	}

	@Test
	fun invalidArtworkDimensionsHaveZeroDrawingHeight() {
		assertEquals(0f, cloudMountainArtworkHeight(0f, 697, 440), 0f)
		assertEquals(0f, cloudMountainArtworkHeight(360f, 0, 440), 0f)
		assertEquals(0f, cloudMountainArtworkHeight(360f, 697, -1), 0f)
	}
}
