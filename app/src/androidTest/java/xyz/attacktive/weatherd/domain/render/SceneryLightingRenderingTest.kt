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
import xyz.attacktive.weatherd.domain.model.TreeStyle

@RunWith(AndroidJUnit4::class)
class SceneryLightingRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
	private val clear = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f,
		backdropScene = BackdropScene.CLOUD_MOUNTAINS,
		treesEnabled = true
	)

	@Test
	fun overcastDesaturatesMountainArtworkInsteadOfOnlyDarkeningIt() {
		val layer = CloudMountainLayer(resources)
		layer.prewarm()
		val day = render { layer.draw(it, WIDTH.toFloat(), HEIGHT.toFloat(), clear) }
		val overcast = render { layer.draw(it, WIDTH.toFloat(), HEIGHT.toFloat(), clear.copy(cloudiness = 1f)) }
		try {
			assertTrue("Overcast must visibly gray the blue artwork", saturation(overcast) < saturation(day) * 0.6)
			assertSameAlpha(day, overcast)
		} finally {
			day.recycle()
			overcast.recycle()
		}
	}

	@Test
	fun twilightMountainArtworkHasWarmHighlightsAndCoolShadows() {
		val layer = CloudMountainLayer(resources)
		layer.prewarm()
		val day = render { layer.draw(it, WIDTH.toFloat(), HEIGHT.toFloat(), clear) }
		try {
			for (phase in listOf(DayPhase.DAWN, DayPhase.DUSK)) {
				val twilight = render { layer.draw(it, WIDTH.toFloat(), HEIGHT.toFloat(), clear.copy(dayPhase = phase, celestialProgress = 0.5f)) }
				try {
					var highlightWarmth = 0L
					var shadowWarmth = 0L
					for (y in 0 until HEIGHT) {
						for (x in 0 until WIDTH) {
							val source = day.getPixel(x, y)
							if (Color.alpha(source) < 240) {
								continue
							}

							val luminance = Color.red(source) * 0.2126 + Color.green(source) * 0.7152 + Color.blue(source) * 0.0722
							val color = twilight.getPixel(x, y)
							val warmth = Color.red(color) - Color.blue(color)
							if (luminance > 190) {
								highlightWarmth += warmth
							} else if (luminance in 100.0..130.0) {
								shadowWarmth += warmth
							}
						}
					}

					assertTrue("$phase must warm the artwork's highlights", highlightWarmth > 0)
					assertTrue("$phase must keep the artwork's shadows cool", shadowWarmth < 0)
					assertSameAlpha(day, twilight)
				} finally {
					twilight.recycle()
				}
			}
		} finally {
			day.recycle()
		}
	}

	@Test
	fun bothTreeStylesRespectNightBrightnessWithoutChangingTheirSilhouette() {
		val layer = ForegroundTreeLayer(resources)
		for (style in TreeStyle.entries) {
			layer.prewarm(style)
			val night = clear.copy(dayPhase = DayPhase.NIGHT, treeStyle = style)
			val normal = render { layer.draw(it, WIDTH.toFloat(), HEIGHT.toFloat(), night) }
			val dimmed = render { layer.draw(it, WIDTH.toFloat(), HEIGHT.toFloat(), night.copy(nightBrightnessScale = 0.2f)) }
			val black = render { layer.draw(it, WIDTH.toFloat(), HEIGHT.toFloat(), night.copy(nightBrightnessScale = 0f)) }
			try {
				assertTrue("Night brightness must dim $style foliage", brightness(dimmed) < brightness(normal) * 0.35)
				assertEquals("Zero night brightness must remove emitted scenery light", 0.0, brightness(black), 0.0)
				assertSameAlpha(normal, dimmed)
				assertSameAlpha(normal, black)
			} finally {
				normal.recycle()
				dimmed.recycle()
				black.recycle()
			}
		}
	}

	@Test
	fun weatherAndPhaseChangesPreserveArtworkAlphaAndRestoreCachedDaylight() {
		val mountain = CloudMountainLayer(resources)
		mountain.prewarm()
		val trees = ForegroundTreeLayer(resources)
		val scenes = listOf(
			clear.copy(dayPhase = DayPhase.DAWN),
			clear.copy(dayPhase = DayPhase.DUSK),
			clear.copy(dayPhase = DayPhase.NIGHT),
			clear.copy(fogDensity = 1f),
			clear.copy(cloudiness = 1f, thunder = true),
			clear.copy(precipitation = Precipitation(PrecipitationKind.RAIN, 1f, 1f)),
			clear.copy(precipitation = Precipitation(PrecipitationKind.SNOW, 1f, 1f))
		)

		val draws = mutableListOf<(Canvas, SceneParams) -> Unit>(
			{ canvas, params -> mountain.draw(canvas, WIDTH.toFloat(), HEIGHT.toFloat(), params) }
		)
		for (style in TreeStyle.entries) {
			trees.prewarm(style)
			draws.add { canvas, params -> trees.draw(canvas, WIDTH.toFloat(), HEIGHT.toFloat(), params.copy(treeStyle = style)) }
		}

		for (draw in draws) {
			val original = render { draw(it, clear) }
			try {
				for (scene in scenes) {
					val changed = render { draw(it, scene) }
					try {
						assertSameAlpha(original, changed)
					} finally {
						changed.recycle()
					}
				}

				val restored = render { draw(it, clear.copy()) }
				try {
					assertTrue("Returning to daylight must discard the previous lighting", original.sameAs(restored))
				} finally {
					restored.recycle()
				}
			} finally {
				original.recycle()
			}
		}
	}

	private fun render(draw: (Canvas) -> Unit): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		draw(Canvas(bitmap))

		return bitmap
	}

	private fun assertSameAlpha(expected: Bitmap, actual: Bitmap) {
		for (y in 0 until HEIGHT) {
			for (x in 0 until WIDTH) {
				assertEquals("Artwork alpha at $x,$y", Color.alpha(expected.getPixel(x, y)), Color.alpha(actual.getPixel(x, y)))
			}
		}
	}

	private fun saturation(bitmap: Bitmap): Double {
		var chroma = 0.0
		var weight = 0.0
		for (y in 0 until HEIGHT) {
			for (x in 0 until WIDTH) {
				val color = bitmap.getPixel(x, y)
				val maximum = maxOf(Color.red(color), Color.green(color), Color.blue(color))
				if (maximum > 0) {
					chroma += (maximum - minOf(Color.red(color), Color.green(color), Color.blue(color))).toDouble() / maximum * Color.alpha(color)
					weight += Color.alpha(color)
				}
			}
		}

		return chroma / weight
	}

	private fun brightness(bitmap: Bitmap): Double {
		var sum = 0.0
		for (y in 0 until HEIGHT) {
			for (x in 0 until WIDTH) {
				val color = bitmap.getPixel(x, y)
				sum += (Color.red(color) * 0.2126 + Color.green(color) * 0.7152 + Color.blue(color) * 0.0722) * Color.alpha(color)
			}
		}

		return sum
	}

	private companion object {
		const val WIDTH = 240
		const val HEIGHT = 480
	}
}
