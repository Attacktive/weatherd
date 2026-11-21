package xyz.attacktive.weatherd.domain.render

import android.graphics.Canvas
import android.graphics.Color
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
class LegacyPrecipitationRenderingTest {
	@Test
	fun snowIsClippedByVisibleLegacyBanksAndReturnsWhenBanksAreHidden() {
		val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
		val renderer = SceneRenderer(resources, StormCloudPrototype(resources, enabled = false), epochDay = 0L)
		renderer.prewarmCloudTextures()
		val width = 240
		val height = 528
		val snow = SceneParams(DayPhase.DAY, 1f, 0f, Precipitation(PrecipitationKind.SNOW, 0.5f, 1f), false, 0.5f, precipitationScale = 16f)
		val visibleWithoutSnow = createBitmap(width, height)
		val visibleWithSnow = createBitmap(width, height)
		val hiddenWithoutSnow = createBitmap(width, height)
		val hiddenWithSnow = createBitmap(width, height)

		try {
			renderer.renderForeground(Canvas(visibleWithoutSnow), width, height, snow.copy(precipitationScale = 0f), 0f, celebrationEpochMillis = 0L)
			renderer.renderForeground(Canvas(visibleWithSnow), width, height, snow, 0f, celebrationEpochMillis = 0L)
			val hidden = snow.copy(cloudScale = 0f)
			renderer.renderForeground(Canvas(hiddenWithoutSnow), width, height, hidden.copy(precipitationScale = 0f), 0f, celebrationEpochMillis = 0L)
			renderer.renderForeground(Canvas(hiddenWithSnow), width, height, hidden, 0f, celebrationEpochMillis = 0L)

			var restoredUnderBanks = 0
			var visibleSnow = 0

			for (y in 0 until height) {
				for (x in 0 until width) {
					val bankPixel = visibleWithoutSnow.getPixel(x, y)
					val visiblePixel = visibleWithSnow.getPixel(x, y)

					if (y >= 120 && bankPixel != visiblePixel) {
						visibleSnow++
					}

					if (y < 120 && Color.alpha(bankPixel) >= 128 && hiddenWithoutSnow.getPixel(x, y) != hiddenWithSnow.getPixel(x, y)) {
						assertEquals("Snow must not paint over a visible legacy bank at ($x, $y)", bankPixel, visiblePixel)
						restoredUnderBanks++
					}
				}
			}

			assertTrue("Expected visible snow below the legacy banks", visibleSnow > 0)
			assertTrue("Expected upper-field snow where visible legacy banks previously clipped it", restoredUnderBanks > 0)
		} finally {
			visibleWithoutSnow.recycle()
			visibleWithSnow.recycle()
			hiddenWithoutSnow.recycle()
			hiddenWithSnow.recycle()
		}
	}
}
