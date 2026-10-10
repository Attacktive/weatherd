package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

@RunWith(AndroidJUnit4::class)
class GlassDropletSceneRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun screenPassUsesVisibleWidthAfterWideWorld() {
		val params = rainParams(
			overlayLabels = OverlayLabels(weather = "RAIN", location = "Seoul")
		)
		val reusedRenderer = SceneRenderer(resources)
		val reusedEffects = SceneScreenEffects(reusedRenderer)
		val wideWorld = createBitmap(WIDE_WIDTH, HEIGHT)
		reusedRenderer.renderForeground(
			Canvas(wideWorld),
			WIDE_WIDTH,
			HEIGHT,
			params,
			TIME_SECONDS,
			includeOverlayLabels = false
		)
		val reused = renderScreenEffects(reusedEffects, params, TIME_SECONDS)
		val fresh = renderScreenEffects(SceneScreenEffects(SceneRenderer(resources)), params, TIME_SECONDS)

		assertTrue("A prior wide-world pass must not move or distort visible-width screen effects", reused.sameAs(fresh))

		wideWorld.recycle()
		reused.recycle()
		fresh.recycle()
	}

	@Test
	fun screenPassKeepsLabelsAboveGlass() {
		val labels = OverlayLabels(weather = "MMMMMMMMMMMMMMMMMMMM", location = null)
		val rainy = rainParams(overlayLabels = labels)
		val time = findLabelOverlapTime(rainy)
		val background = Color.rgb(46, 58, 72)
		val combined = bitmapWithBackground(background)
		val combinedEffects = SceneScreenEffects(SceneRenderer(resources))
		combinedEffects.render(Canvas(combined), WIDTH, HEIGHT, rainy, time)

		val expected = bitmapWithBackground(background)
		val orderedEffects = SceneScreenEffects(SceneRenderer(resources))
		orderedEffects.render(Canvas(expected), WIDTH, HEIGHT, rainy.copy(overlayLabels = null), time)
		orderedEffects.render(Canvas(expected), WIDTH, HEIGHT, rainy.copy(precipitation = null), time)

		val reversed = bitmapWithBackground(background)
		val reversedEffects = SceneScreenEffects(SceneRenderer(resources))
		reversedEffects.render(Canvas(reversed), WIDTH, HEIGHT, rainy.copy(precipitation = null), time)
		reversedEffects.render(Canvas(reversed), WIDTH, HEIGHT, rainy.copy(overlayLabels = null), time)

		assertTrue("The combined pass must equal real glass-then-label compositing", combined.sameAs(expected))
		assertFalse("Reversing the two real passes must change their overlap pixels", combined.sameAs(reversed))

		combined.recycle()
		expected.recycle()
		reversed.recycle()
	}

	@Test
	fun disabledPreferenceLeavesRainGlassUndrawn() {
		val effects = SceneScreenEffects(SceneRenderer(resources))
		val background = Color.rgb(78, 92, 108)
		val bitmap = bitmapWithBackground(background)
		val untouched = bitmapWithBackground(background)

		effects.render(
			Canvas(bitmap),
			WIDTH,
			HEIGHT,
			rainParams(overlayLabels = null).copy(glassDropletsEnabled = false),
			TIME_SECONDS
		)

		assertTrue("Rain-on-glass must be opt-in", bitmap.sameAs(untouched))

		bitmap.recycle()
		untouched.recycle()
	}

	@Test
	fun weatherTransitionClearsScreenGlass() {
		val effects = SceneScreenEffects(SceneRenderer(resources))
		val rain = rainParams(overlayLabels = null)
		val background = Color.rgb(78, 92, 108)
		val bitmap = bitmapWithBackground(background)
		val untouched = bitmapWithBackground(background)

		effects.render(Canvas(bitmap), WIDTH, HEIGHT, rain, TIME_SECONDS)

		val nonRain = arrayOf(
			rain.copy(precipitation = null),
			rain.copy(precipitation = rain.precipitation?.copy(kind = PrecipitationKind.SNOW)),
			rain.copy(precipitation = rain.precipitation?.copy(kind = PrecipitationKind.SLEET))
		)
		for (params in nonRain) {
			bitmap.eraseColor(background)
			effects.render(Canvas(bitmap), WIDTH, HEIGHT, params, TIME_SECONDS)
			assertTrue("Dry, snow, and sleet screen passes must leave no stale glass behind", bitmap.sameAs(untouched))
		}

		bitmap.recycle()
		untouched.recycle()
	}

	private fun rainParams(overlayLabels: OverlayLabels?) = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 1f,
		fogDensity = 0f,
		precipitation = Precipitation(PrecipitationKind.RAIN, SEVERITY_STORM, observed = 1f),
		thunder = true,
		windFactor = 1f,
		overlayLabels = overlayLabels,
		precipitationScale = 2f,
		glassDropletsEnabled = true
	)

	private fun renderScreenEffects(effects: SceneScreenEffects, params: SceneParams, timeSeconds: Float): Bitmap {
		val bitmap = bitmapWithBackground(Color.rgb(34, 44, 56))
		effects.render(Canvas(bitmap), WIDTH, HEIGHT, params, timeSeconds)

		return bitmap
	}

	private fun findLabelOverlapTime(params: SceneParams): Float {
		val frame = GlassDropletFrame()
		val count = glassDropletCount(params, WIDTH.toFloat(), HEIGHT.toFloat())
		for (step in 0..192) {
			val time = step * 0.25f
			for (slot in 0 until count) {
				frame.sample(slot, WIDTH.toFloat(), HEIGHT.toFloat(), time)
				if (frame.opacity < 0.8f) {
					continue
				}

				val top = frame.centerY - frame.radius * frame.verticalStretch
				val bottom = frame.centerY + frame.radius * frame.verticalStretch
				val horizontallyInsideText = frame.centerX in WIDTH * 0.2f..WIDTH * 0.8f
				val verticallyInsideText = bottom >= HEIGHT * 0.065f && top <= HEIGHT * 0.095f
				if (horizontallyInsideText && verticallyInsideText) {
					return time
				}
			}
		}

		error("No deterministic droplet overlaps the weather-label band")
	}

	private fun bitmapWithBackground(color: Int): Bitmap = createBitmap(WIDTH, HEIGHT)
		.also { it.eraseColor(color) }

	private companion object {
		const val WIDTH = 360
		const val WIDE_WIDTH = 540
		const val HEIGHT = 780
		const val TIME_SECONDS = 11.25f
	}
}
