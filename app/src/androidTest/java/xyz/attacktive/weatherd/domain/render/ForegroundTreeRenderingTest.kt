package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.TreeStyle
import xyz.attacktive.weatherd.domain.model.TreeSeason
import xyz.attacktive.weatherd.service.wallpaperSceneWidth
import xyz.attacktive.weatherd.service.wallpaperViewportLeft

@RunWith(AndroidJUnit4::class)
class ForegroundTreeRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun seasonalFoliagePreservesTreeGeometryAndOriginalRendering() {
		val layer = ForegroundTreeLayer(resources)
		for (style in TreeStyle.entries) {
			layer.prewarm(style)
			val original = renderTrees(layer, clearParams(style))
			val defaultParams = clearParams(style).copy(treeSeason = TreeSeason.SPRING)
			val defaultRender = renderTrees(ForegroundTreeLayer(resources), defaultParams)
			assertTrue("The Spring $style artwork must match the original bitmaps", original.sameAs(defaultRender))
			defaultRender.recycle()

			for (season in TreeSeason.entries.filter { it != TreeSeason.SPRING && it != TreeSeason.AUTO }) {
				layer.prewarm(style, season)
				val variant = renderTrees(layer, clearParams(style).copy(treeSeason = season))
				var changedPixels = 0
				for (y in 0 until HEIGHT) {
					for (x in 0 until WIDTH) {
						val before = original.getPixel(x, y)
						val after = variant.getPixel(x, y)
						assertEquals("Season $season must preserve $style alpha at ($x, $y)", Color.alpha(before), Color.alpha(after))
						if (before != after) {
							changedPixels++
						}
					}
				}

				assertTrue("$style $season must visibly change foliage", changedPixels > WIDTH * HEIGHT / 500)
				variant.recycle()
			}

			val restored = renderTrees(layer, clearParams(style))
			assertTrue("Switching back must restore original $style pixels", original.sameAs(restored))
			original.recycle()
			restored.recycle()
		}
	}

	@Test
	fun lateObsoletePrewarmCannotEvictSelectedSeason() {
		val selectedLayer = ForegroundTreeLayer(resources)
		val obsoleteWorker = ForegroundTreeLayer(resources)

		for (style in TreeStyle.entries) {
			selectedLayer.prewarm(style)
			val original = renderTrees(selectedLayer, clearParams(style))
			selectedLayer.prewarm(style, TreeSeason.WINTER)
			val selected = clearParams(style).copy(treeSeason = TreeSeason.WINTER)
			val expected = renderTrees(selectedLayer, selected)
			assertFalse("Winter $style must differ from Spring", original.sameAs(expected))

			// Simulate an older preview request resuming after the active wallpaper season is ready.
			obsoleteWorker.prewarm(style, TreeSeason.AUTUMN)
			obsoleteWorker.prewarm(style, TreeSeason.SUMMER)

			for (season in TreeSeason.entries) {
				assertTrue("$style $season must remain ready after obsolete prewarms", selectedLayer.isTextureReady(style, season))
			}

			val actual = renderTrees(selectedLayer, selected)
			assertTrue("Obsolete prewarms must not revert $style from Winter to Spring", expected.sameAs(actual))
			original.recycle()
			expected.recycle()
			actual.recycle()
		}
	}

	@Test
	fun treesLeaveTheUpperSkyUntouchedAndDisableCleanlyWithEveryBackdrop() {
		for (style in TreeStyle.entries) {
			for (backdrop in BackdropScene.entries) {
				val params = clearParams(style).copy(backdropScene = backdrop)
				val renderer = SceneRenderer(resources)
				renderer.prewarmTreeTextures(style)
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
							assertTrue("Trees must not cover the upper sky for $style / $backdrop", !changed)
						} else if (changed) {
							changedPixels++
						}
					}
				}

				if (style == TreeStyle.EVERGREEN) {
					assertTrue("Evergreen must retain its existing foreground presence for $backdrop: $changedPixels pixels", changedPixels > WIDTH * HEIGHT / 10)
				}

				val disabledAgain = renderForeground(renderer, params.copy(treesEnabled = false))
				val expectedDisabled = renderForeground(control, params.copy(treesEnabled = false))
				assertTrue("Turning $style trees off must restore the existing $backdrop foreground exactly", expectedDisabled.sameAs(disabledAgain))
				withoutTrees.recycle()
				withTrees.recycle()
				disabledAgain.recycle()
				expectedDisabled.recycle()
			}
		}
	}

	@Test
	fun broadleafKeepsTheLargerCrownVisibleAndTheUpperCenterOpenAcrossViewportsAndScrolling() {
		val layer = ForegroundTreeLayer(resources)
		layer.prewarm(TreeStyle.BROADLEAF)
		val viewports = listOf(360 to 780, 780 to 360, 800 to 1280, 1280 to 800, 1200 to 540, 1920 to 540)
		for ((width, height) in viewports) {
			for (scrolling in listOf(false, true)) {
				val sceneWidth = wallpaperSceneWidth(width, scrolling)
				val offsets = if (scrolling) {
					listOf(0f, 0.5f, 1f)
				} else {
					listOf(0.5f)
				}

				for (offset in offsets) {
					val bitmap = createBitmap(width, height)
					val canvas = Canvas(bitmap)
					val viewportLeft = wallpaperViewportLeft(width, sceneWidth, offset, scrolling)
					canvas.translate(-viewportLeft, 0f)
					layer.draw(canvas, sceneWidth.toFloat(), height.toFloat(), clearParams(TreeStyle.BROADLEAF))

					val left = alphaCoverage(bitmap, 0, width / 3)
					val upperCenter = alphaCoverage(bitmap, width * 2 / 5, width * 3 / 5, endY = height / 2)
					val right = alphaCoverage(bitmap, width * 3 / 4, width)
					val context = "${width}x$height scrolling=$scrolling offset=$offset"

					assertTrue("The larger Broadleaf tree must retain its left-side presence: $context ($left)", left > 0.015)
					assertTrue("Right Broadleaf tree must frame the edge: $context ($right)", right > 0.015)
					assertEquals("The larger Broadleaf crown must not clip the left boundary: $context", 0.0, alphaCoverage(bitmap, 0, 1), 0.0)
					assertEquals("Broadleaf must leave the upper middle sky open: $context", 0.0, upperCenter, 0.0)

					bitmap.recycle()
				}
			}
		}
	}

	@Test
	fun phaseLightingDarkensFoliageWithoutChangingItsSilhouette() {
		val layer = ForegroundTreeLayer(resources)
		for (style in TreeStyle.entries) {
			layer.prewarm(style)
			val day = renderTrees(layer, clearParams(style))
			val night = renderTrees(layer, clearParams(style).copy(dayPhase = DayPhase.NIGHT))
			val storm = renderTrees(layer, clearParams(style).copy(cloudiness = 1f, thunder = true))
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

			assertTrue("Night $style trees must not retain daylight brightness", nightBrightness < dayBrightness * 0.55f)
			assertTrue("Storm $style trees must belong to the darkened scene", stormBrightness < dayBrightness * 0.75f)
			day.recycle()
			night.recycle()
			storm.recycle()
		}
	}

	@Test
	fun resizingAndReturningToPortraitPreservesTheDeterministicComposition() {
		val layer = ForegroundTreeLayer(resources)
		for (style in TreeStyle.entries) {
			layer.prewarm(style)
			val original = renderTrees(layer, clearParams(style))
			val landscape = renderTrees(layer, clearParams(style), width = HEIGHT, height = WIDTH)
			val restored = renderTrees(layer, clearParams(style))

			assertTrue("A viewport change must not reseed or deform the restored $style composition", original.sameAs(restored))
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
			assertTrue("Landscape $style trees must retain substantial lower-screen coverage", lowerCoverage > landscape.width * landscape.height * 255L / 30)
			original.recycle()
			landscape.recycle()
			restored.recycle()
		}
	}

	@Test
	fun switchingStyleAtTheSameSizeUsesItsNaturalGeometryAndRestoresEvergreensExactly() {
		val layer = ForegroundTreeLayer(resources)
		layer.prewarm(TreeStyle.EVERGREEN)
		layer.prewarm(TreeStyle.BROADLEAF)
		val evergreen = renderTrees(layer, clearParams())
		val broadleaf = renderTrees(layer, clearParams(TreeStyle.BROADLEAF))
		val freshBroadleaf = renderTrees(ForegroundTreeLayer(resources), clearParams(TreeStyle.BROADLEAF))
		val restored = renderTrees(layer, clearParams())
		var silhouetteChanges = 0

		for (y in 0 until HEIGHT) {
			for (x in 0 until WIDTH) {
				if (Color.alpha(evergreen.getPixel(x, y)) != Color.alpha(broadleaf.getPixel(x, y))) {
					silhouetteChanges++
				}
			}
		}

		assertTrue("Broadleaf must change morphology, not merely foliage color", silhouetteChanges > WIDTH * HEIGHT / 20)
		assertTrue("Style switching must discard the previous sprite's aspect-ratio geometry", freshBroadleaf.sameAs(broadleaf))
		assertTrue("Returning to Evergreen must restore the existing composition exactly", evergreen.sameAs(restored))
		evergreen.recycle()
		broadleaf.recycle()
		freshBroadleaf.recycle()
		restored.recycle()
	}

	@Test
	fun fogRainAndSnowCompositeInFrontOfTheTrees() {
		val layer = ForegroundTreeLayer(resources)
		for (style in TreeStyle.entries) {
			layer.prewarm(style)
			val weatherScenes = listOf(
				clearParams(style).copy(fogDensity = 0.7f),
				clearParams(style).copy(precipitation = Precipitation(PrecipitationKind.RAIN, severity = 1f, observed = 1f)),
				clearParams(style).copy(precipitation = Precipitation(PrecipitationKind.SNOW, severity = 1f, observed = 1f))
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

	private fun alphaCoverage(bitmap: Bitmap, startX: Int, endX: Int, endY: Int = bitmap.height): Double {
		var alpha = 0L
		for (y in 0 until endY) {
			for (x in startX until endX) {
				alpha += Color.alpha(bitmap.getPixel(x, y))
			}
		}

		return alpha.toDouble() / ((endX - startX) * endY * 255L)
	}

	private fun brightness(pixel: Int) = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) * Color.alpha(pixel).toLong()

	private fun clearParams(style: TreeStyle = TreeStyle.EVERGREEN) = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f,
		sunVisible = false,
		moonVisible = false,
		treesEnabled = true,
		treeStyle = style
	)

	private companion object {
		const val WIDTH = 360
		const val HEIGHT = 780
		const val TIME_SECONDS = 11f
	}
}
