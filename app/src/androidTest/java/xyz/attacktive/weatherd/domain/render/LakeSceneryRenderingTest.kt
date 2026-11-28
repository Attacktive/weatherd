package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase

class LakeSceneryRenderingTest {
	@Test
	fun windMovesOpenWaterWithoutMovingTheWoodedBank() {
		val renderer = SceneRenderer(InstrumentationRegistry.getInstrumentation().targetContext.resources)
		renderer.prewarmSceneryTexture(BackdropScene.LAKE)
		val params = debugSceneParams(SCENE_PRESETS.first(), DayPhase.DAY, windScale = 1.5f).copy(backdropScene = BackdropScene.LAKE)
		val early = frame(renderer, params, 1f)
		val late = frame(renderer, params, 4f)
		val bare = frame(renderer, params.copy(backdropScene = BackdropScene.NONE), 1f)
		var fixedLand = 0
		var movingWater = 0
		for (y in early.height / 3 until early.height) {
			for (x in 0 until early.width) {
				val color = early.getPixel(x, y)
				if (y < early.height * 0.665f && color != bare.getPixel(x, y) && color == late.getPixel(x, y)) {
					fixedLand++
				}

				if (y > early.height * 0.7f && color != late.getPixel(x, y)) {
					movingWater++
				}
			}
		}

		assertTrue("No stationary bank: $fixedLand pixels", fixedLand > 100)
		assertTrue("No moving water: $movingWater pixels", movingWater > 100)
		assertEquals(bare.getPixel(10, 10), early.getPixel(10, 10))
		assertNotEquals(bare.getPixel(100, 380), early.getPixel(100, 380))
	}

	private fun frame(renderer: SceneRenderer, params: SceneParams, seconds: Float) = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888).also {
		renderer.render(Canvas(it), it.width, it.height, params, seconds)
	}
}
