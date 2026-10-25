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
	fun portraitMountainsRetainOriginalWidthBasedScale() {
		for ((width, height) in listOf(360f to 780f, 1264f to 2780f, 800f to 1280f)) {
			val artworkHeight = cloudMountainArtworkHeight(width, 697, 430)
			val top = cloudMountainArtworkTop(height, artworkHeight)

			assertEquals("Do not enlarge or crop the image based on portrait height at $width × $height", width * 430f / 697f, artworkHeight, 0.001f)
			assertEquals("Keep the mountain fade grounded at $width × $height", height, top + artworkHeight, 0.001f)
		}
	}

	@Test
	fun wideLandscapeKeepsTheMountainSkylineVisible() {
		for ((width, height) in listOf(780f to 360f, 1200f to 540f, 1920f to 540f)) {
			val artworkHeight = cloudMountainArtworkHeight(width, 697, 430)
			val top = cloudMountainArtworkTop(height, artworkHeight)
			val scale = width / 697f

			// The submitted image has mountain peaks at approximately source rows 38 through 106.
			assertEquals(0f, top, 0.001f)
			assertTrue(top + 38f * scale >= 0f)
			assertTrue(top + 106f * scale < height)
			assertEquals(scale, artworkHeight / 430f, 0.001f)
		}
	}

	@Test
	fun invalidArtworkDimensionsHaveZeroDrawingHeight() {
		assertEquals(0f, cloudMountainArtworkHeight(0f, 697, 440), 0f)
		assertEquals(0f, cloudMountainArtworkHeight(360f, 0, 440), 0f)
		assertEquals(0f, cloudMountainArtworkHeight(360f, 697, -1), 0f)
	}
}
