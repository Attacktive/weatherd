package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

@RunWith(AndroidJUnit4::class)
class FrostOnGlassRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
	private val effects = SceneScreenEffects(SceneRenderer(resources))
	private val snow = SceneParams(DayPhase.DAY, 0.8f, 0f, Precipitation(PrecipitationKind.SNOW, 0.5f, 0.7f), false, 0.4f, frostOnGlassEnabled = true)

	@Test
	fun frostGrowsInwardOnAllEdgesWithoutChangingTheCenter() {
		for ((width, height) in listOf(240 to 480, 480 to 240)) {
			val bitmap = createBitmap(width, height)
			try {
				draw(bitmap, snow.copy(precipitation = null), 99f)
				draw(bitmap, snow, 100f)
				assertEquals(0, bitmap.frostedPixels(0, 0, width, height))

				draw(bitmap, snow, 102f)
				val earlyInnerEdge = bitmap.frostedPixels(width / 4, 8, width * 3 / 4, 24)

				draw(bitmap, snow, 112f)
				assertTrue(bitmap.frostedPixels(width / 4, 8, width * 3 / 4, 24) > earlyInnerEdge)
				assertTrue(bitmap.frostedPixels(width / 4, 0, width * 3 / 4, 8) > 0)
				assertTrue(bitmap.frostedPixels(width / 4, height - 8, width * 3 / 4, height) > 0)
				assertTrue(bitmap.frostedPixels(0, height / 4, 8, height * 3 / 4) > 0)
				assertTrue(bitmap.frostedPixels(width - 8, height / 4, width, height * 3 / 4) > 0)
				assertEquals(0, bitmap.frostedPixels(width / 4, height / 4, width * 3 / 4, height * 3 / 4))
			} finally {
				bitmap.recycle()
			}
		}
	}

	@Test
	fun onlyVisibleEnabledSnowBuildsFrostAndEachNewSnowfallStartsClear() {
		val bitmap = createBitmap(240, 480)
		try {
			val heavySnow = snow.copy(precipitation = Precipitation(PrecipitationKind.SNOW, 1f, 1f))
			val clearScenes = listOf(
				snow.copy(precipitation = null),
				snow.copy(precipitation = Precipitation(PrecipitationKind.RAIN, 0.5f, 1f)),
				snow.copy(precipitation = Precipitation(PrecipitationKind.SLEET, 0.5f, 1f)),
				snow.copy(frostOnGlassEnabled = false),
				snow.copy(precipitationScale = 0f)
			)

			for ((index, clear) in clearScenes.withIndex()) {
				val start = index * 30f
				draw(bitmap, heavySnow, start)
				assertEquals(0, bitmap.frostedPixels(0, 0, 240, 480))
				draw(bitmap, heavySnow, start + 12f)
				assertTrue(bitmap.frostedPixels(0, 0, 240, 480) > 0)

				draw(bitmap, clear, start + 13f)
				assertEquals(0, bitmap.frostedPixels(0, 0, 240, 480))
				draw(bitmap, clear, start + 25f)
				assertEquals(0, bitmap.frostedPixels(0, 0, 240, 480))
			}
		} finally {
			bitmap.recycle()
		}
	}

	private fun draw(bitmap: Bitmap, params: SceneParams, time: Float) {
		bitmap.eraseColor(0)
		effects.render(Canvas(bitmap), bitmap.width, bitmap.height, params, time, noticeEpochSeconds = 0L)
	}

	private fun Bitmap.frostedPixels(left: Int, top: Int, right: Int, bottom: Int): Int {
		val width = right - left
		val height = bottom - top
		val pixels = IntArray(width * height)
		getPixels(pixels, 0, width, left, top, width, height)

		return pixels.count { it ushr 24 > 6 }
	}
}
