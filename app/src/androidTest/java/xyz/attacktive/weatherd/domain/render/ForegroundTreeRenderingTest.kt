package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

@RunWith(AndroidJUnit4::class)
class ForegroundTreeRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun treesRemainProminentWithEveryBackdropAndLeaveTheUpperSkyUntouched() {
		for (backdrop in BackdropScene.entries) {
			val params = clearParams().copy(backdropScene = backdrop)
			val renderer = SceneRenderer(resources)
			renderer.prewarmTreeTextures()
			val control = SceneRenderer(resources)
			// Compare matching frame histories because the existing scenery retains paint and cache state between draws.
			renderForeground(control, params.copy(treesEnabled = false)).recycle()
			renderForeground(renderer, params.copy(treesEnabled = false)).recycle()
			val withoutTrees = renderForeground(control, params.copy(treesEnabled = false))
			val withTrees = renderForeground(renderer, params)
			var changedPixels = 0

			for (y in 0 until HEIGHT) {
				for (x in 0 until WIDTH) {
					val changed = withoutTrees.getPixel(x, y) != withTrees.getPixel(x, y)
					if (y < HEIGHT * 2 / 5) {
						assertTrue("Trees must not cover the upper sky for $backdrop", !changed)
					} else if (changed) {
						changedPixels++
					}
				}
			}

			assertTrue("Trees must be foreground scenery, not a small glyph for $backdrop: $changedPixels pixels", changedPixels > WIDTH * HEIGHT / 10)
			val disabledAgain = renderForeground(renderer, params.copy(treesEnabled = false))
			val expectedDisabled = renderForeground(control, params.copy(treesEnabled = false))
			assertTrue("Turning trees off must restore the existing $backdrop foreground exactly", expectedDisabled.sameAs(disabledAgain))
			withoutTrees.recycle()
			withTrees.recycle()
			disabledAgain.recycle()
			expectedDisabled.recycle()
		}
	}

	@Test
	fun phaseLightingDarkensFoliageWithoutChangingItsSilhouette() {
		val layer = ForegroundTreeLayer(resources)
		layer.prewarm()
		val day = renderTrees(layer, clearParams())
		val night = renderTrees(layer, clearParams().copy(dayPhase = DayPhase.NIGHT))
		val storm = renderTrees(layer, clearParams().copy(cloudiness = 1f, thunder = true))
		var dayBrightness = 0L
		var nightBrightness = 0L
		var stormBrightness = 0L

		for (y in 0 until HEIGHT) {
			for (x in 0 until WIDTH) {
				val dayPixel = day.getPixel(x, y)
				val nightPixel = night.getPixel(x, y)
				val stormPixel = storm.getPixel(x, y)
				assertEquals(Color.alpha(dayPixel), Color.alpha(nightPixel))
				assertEquals(Color.alpha(dayPixel), Color.alpha(stormPixel))
				dayBrightness += brightness(dayPixel)
				nightBrightness += brightness(nightPixel)
				stormBrightness += brightness(stormPixel)
			}
		}

		assertTrue("Night trees must not retain daylight brightness", nightBrightness < dayBrightness * 0.55f)
		assertTrue("Storm trees must belong to the darkened scene", stormBrightness < dayBrightness * 0.75f)
		day.recycle()
		night.recycle()
		storm.recycle()
	}

	@Test
	fun resizingAndReturningToPortraitPreservesTheDeterministicComposition() {
		val layer = ForegroundTreeLayer(resources)
		layer.prewarm()
		val original = renderTrees(layer, clearParams())
		val landscape = renderTrees(layer, clearParams(), width = HEIGHT, height = WIDTH)
		val restored = renderTrees(layer, clearParams())

		assertTrue("A viewport change must not reseed or deform the restored tree composition", original.sameAs(restored))
		var upperCoverage = 0L
		var lowerCoverage = 0L
		for (y in 0 until landscape.height) {
			for (x in 0 until landscape.width) {
				if (y < landscape.height * 2 / 5) {
					upperCoverage += Color.alpha(landscape.getPixel(x, y))
				} else {
					lowerCoverage += Color.alpha(landscape.getPixel(x, y))
				}
			}
		}

		assertEquals(0L, upperCoverage)
		assertTrue("Landscape trees must retain substantial lower-screen coverage", lowerCoverage > landscape.width * landscape.height * 255L / 30)
		original.recycle()
		landscape.recycle()
		restored.recycle()
	}

	@Test
	fun fogRainAndSnowCompositeInFrontOfTheTrees() {
		val layer = ForegroundTreeLayer(resources)
		layer.prewarm()
		val weatherScenes = listOf(
			clearParams().copy(fogDensity = 0.7f),
			clearParams().copy(precipitation = Precipitation(PrecipitationKind.RAIN, severity = 1f, observed = 1f)),
			clearParams().copy(precipitation = Precipitation(PrecipitationKind.SNOW, severity = 1f, observed = 1f))
		)

		for (params in weatherScenes) {
			val actual = renderForeground(SceneRenderer(resources), params)
			val expected = renderTrees(layer, params)
			SceneRenderer(resources).renderForeground(Canvas(expected), WIDTH, HEIGHT, params.copy(treesEnabled = false), TIME_SECONDS)

			assertTrue("Fog and precipitation must composite above foliage for $params", expected.sameAs(actual))
			actual.recycle()
			expected.recycle()
		}
	}

	private fun renderForeground(renderer: SceneRenderer, params: SceneParams): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		renderer.renderForeground(Canvas(bitmap), WIDTH, HEIGHT, params, TIME_SECONDS)

		return bitmap
	}

	private fun renderTrees(layer: ForegroundTreeLayer, params: SceneParams, width: Int = WIDTH, height: Int = HEIGHT): Bitmap {
		val bitmap = createBitmap(width, height)
		layer.draw(Canvas(bitmap), width.toFloat(), height.toFloat(), params)

		return bitmap
	}

	private fun brightness(pixel: Int) = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) * Color.alpha(pixel).toLong()

	private fun clearParams() = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f,
		sunVisible = false,
		moonVisible = false,
		treesEnabled = true
	)

	private companion object {
		const val WIDTH = 360
		const val HEIGHT = 780
		const val TIME_SECONDS = 11f
	}
}
