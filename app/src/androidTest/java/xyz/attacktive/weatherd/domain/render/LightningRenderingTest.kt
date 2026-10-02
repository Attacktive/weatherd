package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase

@RunWith(AndroidJUnit4::class)
class LightningRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
	private val params = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = true,
		windFactor = 0f,
		sunVisible = false,
		moonVisible = false
	)

	@Test
	fun boltBloomFallsAwayFromTheChannelWithoutFlatOutlineBands() {
		val bitmap = render(SceneRenderer(resources), 1080, 2400, STRIKE_TIME)
		val near = glowAtDistance(bitmap, 8)
		val shoulder = glowAtDistance(bitmap, 13)
		val outer = glowAtDistance(bitmap, 26)

		assertTrue("Bloom must fall continuously rather than form a flat outline: $near -> $shoulder -> $outer", near > shoulder + 3f && shoulder > outer + 3f)

		bitmap.recycle()
	}

	@Test
	fun changingTheStrikeDoesNotReuseThePreviousChannels() {
		val renderer = SceneRenderer(resources)

		render(renderer, 360, 780, STRIKE_TIME).recycle()
		val next = render(renderer, 360, 780, NEXT_STRIKE_TIME)
		val fresh = render(SceneRenderer(resources), 360, 780, NEXT_STRIKE_TIME)

		assertTrue("A new strike must not retain the previous bolt or its bloom", next.sameAs(fresh))

		next.recycle()
		fresh.recycle()
	}

	@Test
	fun resizingAndReturningToPortraitRestoresTheSameStrike() {
		val renderer = SceneRenderer(resources)

		val portrait = render(renderer, 360, 780, STRIKE_TIME)
		val landscape = render(renderer, 780, 360, STRIKE_TIME)
		val freshLandscape = render(SceneRenderer(resources), 780, 360, STRIKE_TIME)
		val portraitAgain = render(renderer, 360, 780, STRIKE_TIME)

		assertTrue("Rotation must rebuild the bolt for the new surface rather than stretch a stale cache", landscape.sameAs(freshLandscape))
		assertTrue("Returning to portrait must reproduce the deterministic strike", portrait.sameAs(portraitAgain))

		portrait.recycle()
		landscape.recycle()
		freshLandscape.recycle()
		portraitAgain.recycle()
	}

	@Test
	fun fadingThenReplayingAStrikeDoesNotBakeItsBrightnessIntoTheCache() {
		val renderer = SceneRenderer(resources)

		render(renderer, 360, 780, STRIKE_TIME + 0.18f).recycle()
		val peak = render(renderer, 360, 780, STRIKE_TIME)
		val fresh = render(SceneRenderer(resources), 360, 780, STRIKE_TIME)

		assertTrue("The cached channel must follow the current flash envelope, not the first rendered frame's brightness", peak.sameAs(fresh))

		peak.recycle()
		fresh.recycle()
	}

	private fun render(renderer: SceneRenderer, width: Int, height: Int, time: Float): Bitmap {
		val bitmap = createBitmap(width, height)
		bitmap.eraseColor(Color.BLACK)
		renderer.renderForeground(Canvas(bitmap), width, height, params, time)

		return bitmap
	}

	private fun glowAtDistance(bitmap: Bitmap, distance: Int): Float {
		var brightness = 0f
		for (y in 90 until 180) {
			var firstPeakX = 0
			var lastPeakX = 0
			var peak = -1

			for (x in bitmap.width / 5 until bitmap.width * 4 / 5) {
				val value = Color.red(bitmap.getPixel(x, y))
				if (value > peak) {
					peak = value
					firstPeakX = x
					lastPeakX = x
				} else if (value == peak) {
					lastPeakX = x
				}
			}

			val centerX = (firstPeakX + lastPeakX) / 2
			brightness += Color.red(bitmap.getPixel(centerX - distance, y))
			brightness += Color.red(bitmap.getPixel(centerX + distance, y))
		}

		return brightness / 180f
	}

	private companion object {
		const val STRIKE_TIME = 38.025f
		const val NEXT_STRIKE_TIME = 47.05f
	}
}
