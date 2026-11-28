package xyz.attacktive.weatherd.domain.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
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

	@Test
	fun reflectedCloudsMoveSmoothlyWithinHalfSecondInterval() {
		val renderer = SceneRenderer(InstrumentationRegistry.getInstrumentation().targetContext.resources, epochDay = 20_000L)
		renderer.prewarmSceneryTexture(BackdropScene.LAKE)
		renderer.prewarmCloudTextures()

		val params = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = 0.55f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			sunVisible = false,
			backdropScene = BackdropScene.LAKE
		)

		val first = frame(renderer, params, 1.0f)
		val next = frame(renderer, params, 1.15f)
		var changedWaterPixels = 0

		for (y in (first.height * 0.72f).toInt() until first.height) {
			for (x in 0 until first.width) {
				if (first.getPixel(x, y) != next.getPixel(x, y)) {
					changedWaterPixels++
				}
			}
		}

		assertTrue("Reflected clouds stayed frozen within 0.5 s bucket: $changedWaterPixels changed pixels", changedWaterPixels > 50)
	}

	@Test
	fun reflectionInvalidatesWhenStormCloudPrewarmCompletesAfterLegacyBanks() {
		val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
		var failDecode = true
		val prototype = StormCloudPrototype(resources, enabled = true) { _, _ ->
			if (failDecode) {
				throw IllegalStateException("Simulated in-progress storm cloud prewarm")
			}

			createBitmap(64, 64)
				.apply { eraseColor(Color.argb(255, 220, 40, 220)) }
		}

		val renderer = SceneRenderer(resources, prototype, epochDay = 20_000L)
		renderer.prewarmSceneryTexture(BackdropScene.LAKE)
		runCatching { renderer.prewarmCloudTextures() }

		val params = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = 1f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			sunVisible = false,
			backdropScene = BackdropScene.LAKE
		)

		val before = frame(renderer, params, 1.0f)
		failDecode = false
		renderer.prewarmCloudTextures()

		val after = frame(renderer, params, 1.0f)
		var changedWaterPixels = 0

		for (y in (before.height * 0.72f).toInt() until before.height) {
			for (x in 0 until before.width) {
				if (before.getPixel(x, y) != after.getPixel(x, y)) {
					changedWaterPixels++
				}
			}
		}

		assertTrue("Lake reflection stayed stale after StormCloudPrototype prewarm completed", changedWaterPixels > 100)
	}

	@Test
	fun skyReflectionGuardsNonPositiveDimensionsAndNonFiniteTime() {
		val reflection = LakeSkyReflection()
		val params = debugSceneParams(SCENE_PRESETS.first(), DayPhase.DAY).copy(backdropScene = BackdropScene.LAKE)
		var drew = false
		reflection.sample(0, 400, params, 1f, 31, true, { drew = true }, { drew = true })
		reflection.sample(240, 0, params, 1f, 31, true, { drew = true }, { drew = true })
		reflection.sample(240, 400, params, Float.NaN, 31, true, { drew = true }, { drew = true })
		reflection.sample(240, 400, params, Float.POSITIVE_INFINITY, 31, true, { drew = true }, { drew = true })

		assertFalse("sample should not draw for invalid dimensions or non-finite time", drew)
	}

	@Test
	fun skyReflectionCachesStaticBackdropAcrossTimeTicks() {
		val reflection = LakeSkyReflection()
		val params = debugSceneParams(SCENE_PRESETS[2], DayPhase.DAY).copy(backdropScene = BackdropScene.LAKE)
		var backdropDraws = 0
		var cloudDraws = 0
		reflection.sample(240, 400, params, 1.0f, 31, true, { backdropDraws++ }, { cloudDraws++ })
		reflection.sample(240, 400, params, 1.1f, 31, true, { backdropDraws++ }, { cloudDraws++ })
		reflection.sample(240, 400, params, 1.1f, 31, true, { backdropDraws++ }, { cloudDraws++ })

		assertEquals(1, backdropDraws)
		assertEquals(2, cloudDraws)

		val clearParams = params.copy(cloudiness = 0f)
		reflection.sample(240, 400, clearParams, 1.2f, 31, false, { backdropDraws++ }, { cloudDraws++ })
		reflection.sample(240, 400, clearParams, 1.3f, 31, false, { backdropDraws++ }, { cloudDraws++ })

		assertEquals(2, backdropDraws)
		assertEquals(2, cloudDraws)
	}

	@Test
	fun reflectedSkyInterpolatesVerticallyWithoutDuplicatingAdjacentBands() {
		val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
		val layer = LakeLayer(resources)
		layer.prewarm()

		val sky = createBitmap(256, 128)
		val skyPaint = Paint().apply {
			shader = LinearGradient(0f, 0f, 0f, sky.height.toFloat(), Color.rgb(20, 40, 240), Color.rgb(240, 220, 40), Shader.TileMode.CLAMP)
		}

		Canvas(sky).drawRect(0f, 0f, sky.width.toFloat(), sky.height.toFloat(), skyPaint)

		val width = 120
		val height = 1800
		val output = createBitmap(width, height)
		val params = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = 0f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			sunVisible = false,
			backdropScene = BackdropScene.LAKE
		)

		layer.draw(Canvas(output), width.toFloat(), height.toFloat(), params, 1f, sky)

		val waterTop = height * LAKE_WATER_START
		val waterHeight = height - waterTop
		val bandTop = (waterTop + waterHeight * (47f / 64f)).toInt() + 1
		val bandBottom = (waterTop + waterHeight * (48f / 64f)).toInt() - 1

		assertTrue("Expected multi-pixel band height", bandBottom - bandTop >= 5)
		assertNotEquals("Vertical bilinear filtering should vary smoothly within a single band", output.getPixel(width / 2, bandTop), output.getPixel(width / 2, bandBottom))

		val band47Mid = (waterTop + waterHeight * (47.5f / 64f)).toInt()
		val band48Mid = (waterTop + waterHeight * (48.5f / 64f)).toInt()

		assertNotEquals("Adjacent bands 47 and 48 should not duplicate the same sky row", output.getPixel(width / 2, band47Mid), output.getPixel(width / 2, band48Mid))
	}

	@Test
	fun skyReflectionReusesBackdropSignatureWithoutCopyingOnUnchangedInput() {
		val reflection = LakeSkyReflection()
		val clear = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = 0f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0.2f,
			backdropScene = BackdropScene.LAKE
		)

		var backdropDraws = 0
		reflection.sample(240, 400, clear, 1.0f, 31, false, { backdropDraws++ }, {})

		val cached = reflection.resolvedBackdropSignature(clear)
		val repeated = reflection.resolvedBackdropSignature(clear)
		assertSame(cached, repeated)

		val windier = clear.copy(windFactor = 0.9f)
		reflection.sample(240, 400, windier, 1.1f, 31, false, { backdropDraws++ }, {})

		assertEquals(1, backdropDraws)
		assertSame(cached, reflection.resolvedBackdropSignature(windier))
	}

	@Test
	fun windDisplacesReflectedSkyHorizontallyAcrossWaterBands() {
		val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
		val layer = LakeLayer(resources)
		layer.prewarm()

		val sky = createBitmap(256, 128)
		val skyPaint = Paint().apply {
			shader = LinearGradient(0f, 0f, sky.width.toFloat(), 0f, Color.rgb(20, 40, 240), Color.rgb(240, 220, 40), Shader.TileMode.CLAMP)
		}

		Canvas(sky).drawRect(0f, 0f, sky.width.toFloat(), sky.height.toFloat(), skyPaint)

		val width = 240
		val height = 400
		val params = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = 0f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 1f,
			sunVisible = false,
			backdropScene = BackdropScene.LAKE
		)

		val first = createBitmap(width, height).also {
			layer.draw(Canvas(it), width.toFloat(), height.toFloat(), params, 1f, sky)
		}

		val later = createBitmap(width, height).also {
			layer.draw(Canvas(it), width.toFloat(), height.toFloat(), params, 3f, sky)
		}

		var shiftedPixels = 0

		for (y in (height * 0.78f).toInt() until height) {
			for (x in 0 until width) {
				if (first.getPixel(x, y) != later.getPixel(x, y)) {
					shiftedPixels++
				}
			}
		}

		assertTrue("Expected wind to displace reflected sky bands horizontally: $shiftedPixels changed pixels", shiftedPixels > 500)
	}

	private fun frame(renderer: SceneRenderer, params: SceneParams, seconds: Float) = createBitmap(240, 400)
		.also { renderer.render(Canvas(it), it.width, it.height, params, seconds) }
}
