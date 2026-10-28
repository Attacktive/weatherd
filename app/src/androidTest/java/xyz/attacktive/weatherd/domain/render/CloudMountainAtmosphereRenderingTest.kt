package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.DayPhase

@RunWith(AndroidJUnit4::class)
class CloudMountainAtmosphereRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
	private val clear = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f,
		backdropScene = BackdropScene.CLOUD_MOUNTAINS,
		sunVisible = false,
		moonVisible = false,
		distantAircraftEnabled = false,
		birdFlockSize = BirdFlockSize.OFF
	)

	@Test
	fun twilightMistDriftsOnlyInsideValleysWithoutCoveringPeaks() {
		for (phase in listOf(DayPhase.DAWN, DayPhase.DUSK)) {
			val renderer = SceneRenderer(resources)
			renderer.prewarmSceneryTexture(BackdropScene.CLOUD_MOUNTAINS)
			val params = clear.copy(dayPhase = phase)
			val first = render(renderer, params, 0f)
			val later = render(renderer, params, 70f)
			val unmodified = artwork(params)
			try {
				var valleyDifference = 0L
				for (y in 0 until HEIGHT) {
					for (x in 0 until WIDTH) {
						val difference = channelDifference(first.getPixel(x, y), later.getPixel(x, y))
						if (phase == DayPhase.DAWN) {
							val decoration = channelDifference(unmodified.getPixel(x, y), first.getPixel(x, y))
							assertTrue("Mist must not wash out mountain detail at $x,$y", decoration <= 18)
							if (y !in 787..902) {
								assertEquals("The original peak detail must remain untouched", 0, decoration)
							}
						}

						if (y !in 787..902) {
							assertEquals("$phase motion must not touch the peaks or sky at $x,$y", 0, difference)
						}

						assertTrue("Mist must preserve mountain detail at $x,$y", difference <= 18)
						valleyDifference += difference
					}
				}

				assertTrue("$phase must have visible, restrained valley drift", valleyDifference > 1000)
			} finally {
				first.recycle()
				later.recycle()
				unmodified.recycle()
			}
		}
	}

	@Test
	fun daylightAndTwilightBoundariesPreserveTheUndecoratedArtwork() {
		val renderer = SceneRenderer(resources)
		renderer.prewarmSceneryTexture(BackdropScene.CLOUD_MOUNTAINS)
		for (params in listOf(clear, clear.copy(dayPhase = DayPhase.DAWN, celestialProgress = 1f), clear.copy(dayPhase = DayPhase.DUSK, celestialProgress = 0f))) {
			val expected = artwork(params)
			val actual = render(renderer, params, 70f)
			try {
				assertTrue("${params.dayPhase} must retain the original lit artwork", expected.sameAs(actual))
			} finally {
				expected.recycle()
				actual.recycle()
			}
		}
	}

	@Test
	fun nightLightsAreWarmSparseAndFixedOnTerrain() {
		val renderer = SceneRenderer(resources)
		renderer.prewarmSceneryTexture(BackdropScene.CLOUD_MOUNTAINS)
		val night = clear.copy(dayPhase = DayPhase.NIGHT)
		val unlit = artwork(night)
		val first = render(renderer, night, 0f)
		val later = render(renderer, night, 70f)
		try {
			assertOpaqueTerrainEquals(unlit, first, later)
			var litPixels = 0
			for (y in 0 until HEIGHT) {
				for (x in 0 until WIDTH) {
					val original = unlit.getPixel(x, y)
					if (Color.alpha(original) != 255) {
						continue
					}

					val lit = first.getPixel(x, y)
					if (Color.red(lit) - Color.red(original) <= 20) {
						continue
					}

					assertTrue("Lights must stay in the foothills, not the sky", y in 827..902)
					assertTrue("Distant habitation must add warm light", Color.red(lit) - Color.red(original) > (Color.blue(lit) - Color.blue(original)) * 1.5f)
					litPixels++
				}
			}

			assertTrue("Only a few small habitation lights should be visible, got $litPixels pixels", litPixels in 8..160)
		} finally {
			unlit.recycle()
			first.recycle()
			later.recycle()
		}
	}

	@Test
	fun nightTransitionsKeepTheLightsContinuousAndRespectBlackout() {
		val renderer = SceneRenderer(resources)
		renderer.prewarmSceneryTexture(BackdropScene.CLOUD_MOUNTAINS)
		val mask = artwork(clear)
		val night = render(renderer, clear.copy(dayPhase = DayPhase.NIGHT), 0f)
		try {
			for (params in listOf(clear.copy(dayPhase = DayPhase.DUSK, celestialProgress = 1f), clear.copy(dayPhase = DayPhase.DAWN, celestialProgress = 0f))) {
				val boundary = render(renderer, params, 0f)
				try {
					assertOpaqueTerrainEquals(mask, night, boundary)
				} finally {
					boundary.recycle()
				}
			}

			val blackout = clear.copy(dayPhase = DayPhase.NIGHT, nightBrightnessScale = 0f)
			val expected = artwork(blackout)
			val actual = render(renderer, blackout, 0f)
			try {
				assertOpaqueTerrainEquals(expected, expected, actual)
			} finally {
				expected.recycle()
				actual.recycle()
			}
		} finally {
			night.recycle()
			mask.recycle()
		}
	}

	@Test
	fun realFogSuppressesDecorativeAtmosphereInsteadOfStackingIt() {
		val layer = CloudMountainLayer(resources)
		layer.prewarm()
		for (phase in DayPhase.entries) {
			val params = clear.copy(dayPhase = phase, fogDensity = 1f)
			val expected = artwork(params)
			val actual = createBitmap(WIDTH, HEIGHT)
			layer.draw(Canvas(actual), WIDTH.toFloat(), HEIGHT.toFloat(), params, 70f)
			try {
				assertTrue("Actual fog must take precedence over decorative mist and lights", expected.sameAs(actual))
			} finally {
				expected.recycle()
				actual.recycle()
			}
		}
	}

	@Test
	fun mountainDecorationNeverLeaksToOtherBackdrops() {
		val layer = CloudMountainLayer(resources)
		layer.prewarm()
		val untouched = createBitmap(WIDTH, HEIGHT)
		val actual = createBitmap(WIDTH, HEIGHT)
		try {
			for (scene in BackdropScene.entries.filter { it != BackdropScene.CLOUD_MOUNTAINS }) {
				for (phase in DayPhase.entries) {
					layer.draw(Canvas(actual), WIDTH.toFloat(), HEIGHT.toFloat(), clear.copy(backdropScene = scene, dayPhase = phase), 70f)
					assertTrue("$scene must not acquire mountain decoration at $phase", untouched.sameAs(actual))
				}
			}
		} finally {
			untouched.recycle()
			actual.recycle()
		}
	}

	private fun assertOpaqueTerrainEquals(mask: Bitmap, expected: Bitmap, actual: Bitmap) {
		for (y in 0 until HEIGHT) {
			for (x in 0 until WIDTH) {
				if (Color.alpha(mask.getPixel(x, y)) == 255) {
					assertEquals("Terrain at $x,$y", expected.getPixel(x, y), actual.getPixel(x, y))
				}
			}
		}
	}

	private fun render(renderer: SceneRenderer, params: SceneParams, time: Float): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		renderer.renderForeground(Canvas(bitmap), WIDTH, HEIGHT, params, time)

		return bitmap
	}

	private fun artwork(params: SceneParams): Bitmap {
		val source = BitmapFactory.decodeResource(resources, R.drawable.cloud_mountains, BitmapFactory.Options().apply { inScaled = false })
		val bitmap = createBitmap(WIDTH, HEIGHT)
		val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
		SceneryLighting(SceneryPlane.FAR).applyTo(paint, params)
		Canvas(bitmap).drawBitmap(source, null, RectF(0f, 662f, 697f, 1092f), paint)
		source.recycle()

		return bitmap
	}

	private fun channelDifference(first: Int, second: Int) = maxOf(
		abs(Color.alpha(first) - Color.alpha(second)),
		abs(Color.red(first) - Color.red(second)),
		abs(Color.green(first) - Color.green(second)),
		abs(Color.blue(first) - Color.blue(second))
	)

	private companion object {
		const val WIDTH = 697
		const val HEIGHT = 1200
	}
}
