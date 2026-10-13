package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.TreeStyle

@RunWith(AndroidJUnit4::class)
class TreeStyleRendererStateTest {
	@Test
	fun metropolisTreeStyleSwitchRebuildsSceneryOnSameRenderer() {
		val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
		val renderer = SceneRenderer(resources)
		val width = 360
		val height = 780
		val rounded = createBitmap(width, height)
		val conifer = createBitmap(width, height)
		val params = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = 0f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			backdropScene = BackdropScene.METROPOLIS,
			treeStyle = TreeStyle.ROUNDED,
			sunVisible = false,
		)

		renderer.renderForeground(Canvas(rounded), width, height, params, timeSeconds = 0f)
		renderer.renderForeground(Canvas(conifer), width, height, params.copy(treeStyle = TreeStyle.CONIFER), timeSeconds = 0f)

		assertNotEquals(rounded.pixelFingerprint(), conifer.pixelFingerprint())

		rounded.recycle()
		conifer.recycle()
	}

	private fun Bitmap.pixelFingerprint(): Long {
		var fingerprint = 17L

		for (y in 0 until height step 4) {
			for (x in 0 until width step 4) {
				fingerprint = fingerprint * 31L + getPixel(x, y)
			}
		}

		return fingerprint
	}

}
