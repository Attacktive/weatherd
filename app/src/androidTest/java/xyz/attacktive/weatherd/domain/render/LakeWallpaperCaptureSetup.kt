package xyz.attacktive.weatherd.domain.render

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.attacktive.weatherd.di.StorageModule
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.FrameRateCap
import xyz.attacktive.weatherd.domain.repository.SettingsRepository

/** Seeds only the isolated measurement package; never write the user's simulator settings. */
class LakeWallpaperCaptureSetup {
	@Test
	fun seedIsolatedScene() = runBlocking {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val arguments = InstrumentationRegistry.getArguments()
		assumeTrue(
			"Lake wallpaper capture setup requires the isolated .lakepacing package and -e lakeCaptureSetup true",
			context.packageName == "xyz.attacktive.weatherd.lakepacing" && arguments.getString(ARG_LAKE_CAPTURE_SETUP)?.equals("true", ignoreCase = true) == true
		)

		val scene = arguments.getString("weather", "HEAVY RAIN")
		val settings = AppSettings(
			backdropScene = BackdropScene.LAKE,
			frameRateCap = FrameRateCap.FPS_30,
			sceneSimulatorEnabled = true,
			sceneSimulatorActive = true,
			sceneSimulatorPresetIndex = SCENE_PRESETS.indexOfFirst { it.name == scene }.also { require(it >= 0) },
			sceneSimulatorDayPhase = DayPhase.valueOf(arguments.getString("phase", "DAY"))
		)

		val repository = SettingsRepository(StorageModule.provideDataStore(context))
		repository.save(settings)

		val saved = repository.settings.first()
		assertEquals(settings.backdropScene, saved.backdropScene)
		assertEquals(settings.frameRateCap, saved.frameRateCap)
		assertEquals(settings.sceneSimulatorPresetIndex, saved.sceneSimulatorPresetIndex)
		assertEquals(settings.sceneSimulatorActive, saved.sceneSimulatorActive)
	}

	private companion object {
		const val ARG_LAKE_CAPTURE_SETUP = "lakeCaptureSetup"
	}
}
