package xyz.attacktive.weatherd.domain.render

import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase

@RunWith(AndroidJUnit4::class)
class SceneRendererStateTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun phaseSwitchBackdropStaysOpaqueAfterNightForeground() {
		val renderer = SceneRenderer(resources)
		val night = SceneParams(
			dayPhase = DayPhase.NIGHT,
			cloudiness = 0f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			moonVisible = false
		)

		val foreground = createBitmap(WIDTH, HEIGHT)
		renderer.renderForeground(Canvas(foreground), WIDTH, HEIGHT, night, timeSeconds = 0f)
		foreground.recycle()

		val backdrop = createBitmap(WIDTH, HEIGHT)
		renderer.renderBackdrop(Canvas(backdrop), WIDTH, HEIGHT, night.copy(dayPhase = DayPhase.DAY))
		val pixel = backdrop.getPixel(WIDTH / 2, HEIGHT / 2)

		assertEquals(
			"Rebuilding a backdrop after a translucent foreground pass must still produce an opaque sky",
			255,
			Color.alpha(pixel)
		)

		backdrop.recycle()
	}

	private companion object {
		const val WIDTH = 360
		const val HEIGHT = 780
	}
}
