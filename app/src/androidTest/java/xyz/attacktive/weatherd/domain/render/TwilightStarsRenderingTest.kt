package xyz.attacktive.weatherd.domain.render

import java.io.File
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.DayPhase

@RunWith(AndroidJUnit4::class)
class TwilightStarsRenderingTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	private val renderer = SceneRenderer(context.resources)
	private val width = 720
	private val height = 1280

	@Test
	fun sameStarsFadeAcrossBothBoundariesWithoutResettingTheirTwinkle() {
		for (time in listOf(0f, 2f)) {
			val night = frame(DayPhase.NIGHT, 0.5f, time)
			assertTrue(night.any { it != 0 })
			assertArrayEquals(night, frame(DayPhase.DUSK, 1f, time))
			assertArrayEquals(night, frame(DayPhase.DAWN, 0f, time))

			for ((phase, progress) in listOf(DayPhase.DUSK to 0.78f, DayPhase.DAWN to 0.3f)) {
				val partial = frame(phase, progress, time)
				assertTrue(partial.count { it != 0 } in 1 until night.count { it != 0 })
				assertTrue(partial.indices.all { Color.alpha(partial[it]) <= Color.alpha(night[it]) })

				val darker = frame(phase, if (phase == DayPhase.DUSK) 0.92f else 0.1f, time)
				assertTrue(darker.count { it != 0 } > partial.count { it != 0 })
				assertTrue(partial.indices.all { Color.alpha(partial[it]) <= Color.alpha(darker[it]) })
			}

			for ((phase, progress) in listOf(DayPhase.DAY to 0.5f, DayPhase.DUSK to 0f, DayPhase.DAWN to 1f)) {
				assertTrue(frame(phase, progress, time).all { it == 0 })
			}
		}
	}

	@Test
	fun meteorAddsPixelsAtNightButNotAtEitherTwilightEndpoint() {
		// The first seeded meteor is in flight one second into the scene clock.
		val night = frame(DayPhase.NIGHT, 0.5f, 1f)
		val dusk = frame(DayPhase.DUSK, 1f, 1f)
		assertArrayEquals(dusk, frame(DayPhase.DAWN, 0f, 1f))
		assertTrue(night.indices.any { night[it] != 0 && dusk[it] == 0 })
	}

	private fun frame(phase: DayPhase, progress: Float, time: Float): IntArray {
		val params = SceneParams(
			dayPhase = phase,
			cloudiness = 0f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			celestialProgress = progress,
			sunVisible = false,
			moonVisible = false,
			distantAircraftEnabled = false,
			birdFlockSize = BirdFlockSize.OFF
		)

		val bitmap = createBitmap(width, height)

		try {
			val canvas = Canvas(bitmap)
			renderer.renderForeground(canvas, width, height, params, time, includeOverlayLabels = false)

			val pixels = IntArray(width * height)
			bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

			if (time == 0f && InstrumentationRegistry.getArguments().getString("captureTwilightStars") == "true") {
				renderer.renderBackdrop(canvas, width, height, params)
				renderer.renderForeground(canvas, width, height, params, time, includeOverlayLabels = false)
				File(context.filesDir, "stars-$phase-$progress.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
			}

			return pixels
		} finally {
			bitmap.recycle()
		}
	}
}
