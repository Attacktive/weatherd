package xyz.attacktive.weatherd.domain.render

import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
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
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.CustomSkyGradient
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SkyGradientDirection

@RunWith(AndroidJUnit4::class)
class DawnSkyGradientRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
	private val viewports = listOf(240 to 528, 528 to 240)

	@Test
	fun explicitStopsKeepBlueAboveAndBringWarmthIntoTheLowerSky() {
		for ((width, height) in viewports) {
			val renderer = SceneRenderer(resources, epochDay = 20_000L)
			val bitmap = render(renderer, width, height, clearDawn())
			try {
				val upper = bitmap.getPixel((width * 0.18f).toInt(), (height * 0.25f).toInt())
				val lower = bitmap.getPixel((width * 0.18f).toInt(), (height * 0.65f).toInt())
				assertTrue("$width x $height must keep a blue upper sky", Color.blue(upper) > Color.red(upper))
				assertTrue("$width x $height must show warmth above the old 72% stop; color=$lower", Color.red(lower) > Color.blue(lower) + 10)
			} finally {
				bitmap.recycle()
			}
		}
	}

	@Test
	fun naturalAndClearBlueMeetTheirDaytimeBackdropWithoutASeam() {
		for ((width, height) in viewports) {
			val renderer = SceneRenderer(resources, epochDay = 20_000L)
			for (preset in listOf(SkyColorPreset.NATURAL, SkyColorPreset.CLEAR_BLUE)) {
				val params = clearDawn().copy(skyColorPreset = preset)
				assertBackdropsNear(renderer, width, height, params.copy(celestialProgress = 0.849f), params.copy(celestialProgress = 0.85f), 3)
				assertBackdropsNear(renderer, width, height, params.copy(celestialProgress = 0.85f), params.copy(celestialProgress = 1f), 0)
				assertBackdropsNear(renderer, width, height, params.copy(celestialProgress = 1f), params.copy(dayPhase = DayPhase.DAY, celestialProgress = 0f), 0)
			}
		}
	}

	@Test
	fun customGeometryStillUsesCompleteNaturalDawnBeforeDaylight() {
		for ((width, height) in viewports) {
			val renderer = SceneRenderer(resources, epochDay = 20_000L)
			for (direction in SkyGradientDirection.entries) {
				for (threeColors in listOf(false, true)) {
					val natural = clearDawn()
					val selected = natural.copy(skyColorPreset = SkyColorPreset.CUSTOM, customSkyGradient = custom().copy(direction = direction, threeColors = threeColors))
					assertBackdropsNear(renderer, width, height, natural, selected, 0)
				}
			}
		}
	}

	@Test
	fun customDaylightCrossfadeHasNoColorOrDirectionSnap() {
		for ((width, height) in viewports) {
			val renderer = SceneRenderer(resources, epochDay = 20_000L)
			for (direction in SkyGradientDirection.entries) {
				for (threeColors in listOf(false, true)) {
					val params = clearDawn().copy(skyColorPreset = SkyColorPreset.CUSTOM, customSkyGradient = custom().copy(direction = direction, threeColors = threeColors))
					for ((before, after) in listOf(0.499f to 0.5f, 0.5f to 0.501f, 0.849f to 0.85f, 0.85f to 0.851f)) {
						assertBackdropsNear(renderer, width, height, params.copy(celestialProgress = before), params.copy(celestialProgress = after), 3)
					}
				}
			}
		}
	}

	@Test
	fun cachedBackdropDoesNotRetainThePreviousDaylightGeometry() {
		for ((width, height) in viewports) {
			val renderer = SceneRenderer(resources, epochDay = 20_000L)
			val params = clearDawn()
			val original = render(renderer, width, height, params)
			try {
				render(renderer, width, height, params.copy(celestialProgress = 0.85f)).recycle()
				val returned = render(renderer, width, height, params)
				try {
					assertTrue("Revisiting dawn must invalidate the day palette", original.sameAs(returned))
				} finally {
					returned.recycle()
				}
			} finally {
				original.recycle()
			}
		}
	}

	@Test
	fun photoBackdropsRemainIndependentOfDawnPaletteSelection() {
		for ((width, height) in viewports) {
			val renderer = SceneRenderer(resources, epochDay = 20_000L)
			val photo = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
			photo.eraseColor(Color.rgb(40, 180, 70))
			renderer.backgroundPhoto = photo
			try {
				val params = clearDawn().copy(backdropScene = BackdropScene.PHOTO)
				assertBackdropsNear(renderer, width, height, params, params.copy(skyColorPreset = SkyColorPreset.WARM), 0)
			} finally {
				renderer.backgroundPhoto = null
				photo.recycle()
			}
		}
	}

	@Test
	fun lowHorizonVeilSoftensEarlyDawnBottomWithoutTouchingUpperOrMidSky() {
		for ((width, height) in viewports) {
			val renderer = SceneRenderer(resources, epochDay = 20_000L)
			for (progress in listOf(0.25f, 0.5f)) {
				val bitmap = render(renderer, width, height, clearDawn().copy(celestialProgress = progress))
				try {
					for (xFraction in listOf(0.18f, 0.5f)) {
						val x = (width * xFraction).toInt()
						val upper = bitmap.getPixel(x, (height * 0.25f).toInt())
						val mid = bitmap.getPixel(x, (height * 0.65f).toInt())
						val stop = bitmap.getPixel(x, (height * 0.88f).toInt())
						val horizon = bitmap.getPixel(x, (height * 0.96f).toInt())

						assertTrue("$width x $height progress=$progress x=$xFraction must preserve blue upper sky", Color.blue(upper) > Color.red(upper))
						assertTrue("$width x $height progress=$progress x=$xFraction must preserve warm mid-lower sky", Color.red(mid) > Color.blue(mid) + 10)
						assertTrue("$width x $height progress=$progress x=$xFraction must keep warm white-gold ordering", Color.red(horizon) >= Color.green(horizon) && Color.green(horizon) >= Color.blue(horizon))

						val stopNeutrality = (Color.green(stop) + Color.blue(stop)) / (2f * Color.red(stop))
						val horizonNeutrality = (Color.green(horizon) + Color.blue(horizon)) / (2f * Color.red(horizon))
						assertTrue("$width x $height progress=$progress x=$xFraction bottom horizon ($horizonNeutrality) must soften toward white-gold past 88% stop ($stopNeutrality)", horizonNeutrality > stopNeutrality)
					}
				} finally {
					bitmap.recycle()
				}
			}
		}

		captureDawnProgressionIfRequested()
	}

	@Test
	fun dawnHorizonVeilDoesNotAlterDayDuskNightOrStackOntoFogAndOvercast() {
		for ((width, height) in viewports) {
			val renderer = SceneRenderer(resources, epochDay = 20_000L)
			val base = clearDawn()

			for (phase in listOf(DayPhase.DAY, DayPhase.DUSK, DayPhase.NIGHT)) {
				assertEquals(0, dawnHorizonHazeAlpha(base.copy(dayPhase = phase)))
			}

			val day = render(renderer, width, height, base.copy(dayPhase = DayPhase.DAY))
			try {
				val dayBottom = day.getPixel(width / 2, (height * 0.96f).toInt())
				assertTrue("DAY bottom must remain cool blue", Color.blue(dayBottom) > Color.red(dayBottom))
			} finally {
				day.recycle()
			}

			val quietClear = base.copy(sunVisible = false, moonVisible = false, distantAircraftEnabled = false, birdFlockSize = BirdFlockSize.OFF)
			val clearFg = renderForegroundOnly(renderer, width, height, quietClear)
			val humidFg = renderForegroundOnly(renderer, width, height, quietClear.copy(humidityHazeDensity = 0.25f))
			val fogFg = renderForegroundOnly(renderer, width, height, quietClear.copy(fogDensity = 1f))
			try {
				assertEquals(0L, foregroundAlphaSum(clearFg))
				assertTrue("Humidity haze must add foreground mist while clear dawn has none", foregroundAlphaSum(humidFg) > 0L)
				assertTrue("Reported fog must add stronger foreground mist than humidity haze", foregroundAlphaSum(fogFg) > foregroundAlphaSum(humidFg))
			} finally {
				clearFg.recycle()
				humidFg.recycle()
				fogFg.recycle()
			}

			assertEquals(0, dawnHorizonHazeAlpha(base.copy(cloudiness = 0.85f)))
			assertEquals(0, dawnHorizonHazeAlpha(base.copy(fogDensity = 1f)))
			assertEquals(0, dawnHorizonHazeAlpha(base.copy(humidityHazeDensity = 0.25f)))

			val overcast = render(renderer, width, height, base.copy(cloudiness = 0.85f))
			try {
				val bottom = overcast.getPixel(width / 2, (height * 0.96f).toInt())
				assertTrue("Overcast dawn must remain muted rather than bright white-gold", Color.red(bottom) - Color.blue(bottom) < 40)
			} finally {
				overcast.recycle()
			}

			for (scenery in listOf(BackdropScene.METROPOLIS, BackdropScene.MOUNTAINS)) {
				val sceneryParams = base.copy(backdropScene = scenery, distantAircraftEnabled = false, birdFlockSize = BirdFlockSize.OFF)
				val backdropOnly = render(renderer, width, height, sceneryParams)
				val fullScene = renderComplete(renderer, width, height, sceneryParams)
				try {
					val skySample = fullScene.getPixel(width / 2, (height * 0.65f).toInt())
					val scenerySample = fullScene.getPixel(width / 2, (height * 0.97f).toInt())
					assertTrue("$scenery must retain skyline luminance contrast over early dawn veil", luminance(skySample) > luminance(scenerySample) + 20f)
					assertTrue("$scenery must remain visibly distinct above the dawn backdrop", averageRgbDifference(backdropOnly, fullScene, (height * 0.85f).toInt(), height) > 15f)
				} finally {
					backdropOnly.recycle()
					fullScene.recycle()
				}
			}
		}
	}

	private fun clearDawn() = SceneParams(dayPhase = DayPhase.DAWN, cloudiness = 0.05f, fogDensity = 0f, precipitation = null, thunder = false, windFactor = 0f, celestialProgress = 0.5f)

	private fun custom() = CustomSkyGradient(startColor = 0xFF224488.toInt(), middleColor = 0xFF66AACC.toInt(), endColor = 0xFFDDEEFF.toInt(), threeColors = true, startPosition = 0.2f, middlePosition = 0.4f, endPosition = 0.8f, direction = SkyGradientDirection.HORIZONTAL)

	private fun render(renderer: SceneRenderer, width: Int, height: Int, params: SceneParams) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
		renderer.renderBackdrop(Canvas(it), width, height, params)
	}

	private fun renderForegroundOnly(renderer: SceneRenderer, width: Int, height: Int, params: SceneParams) = createBitmap(width, height).also {
		renderer.renderForeground(Canvas(it), width, height, params, 11f, includeOverlayLabels = false)
	}

	private fun renderComplete(renderer: SceneRenderer, width: Int, height: Int, params: SceneParams) = createBitmap(width, height).also {
		renderer.render(Canvas(it), width, height, params, 11f)
	}

	private fun luminance(color: Int) = Color.red(color) * 0.2126f + Color.green(color) * 0.7152f + Color.blue(color) * 0.0722f

	private fun foregroundAlphaSum(bitmap: Bitmap): Long {
		var sum = 0L
		for (y in 0 until bitmap.height) {
			for (x in 0 until bitmap.width) {
				sum += Color.alpha(bitmap.getPixel(x, y))
			}
		}

		return sum
	}

	private fun averageRgbDifference(first: Bitmap, second: Bitmap, top: Int, bottom: Int): Float {
		var difference = 0L
		var samples = 0
		for (y in top until bottom) {
			for (x in 0 until first.width) {
				val a = first.getPixel(x, y)
				val b = second.getPixel(x, y)
				difference += abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
				samples += 3
			}
		}

		return difference.toFloat() / samples
	}

	private fun captureDawnProgressionIfRequested() {
		val capture = InstrumentationRegistry.getArguments().getString("dawnHazeEvidence") ?: return
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val renderer = SceneRenderer(context.resources, epochDay = 20_000L).apply {
			prewarmCloudTextures()
		}

		val base = clearDawn().copy(cloudiness = 0f, distantAircraftEnabled = false, birdFlockSize = BirdFlockSize.OFF)
		val scenes = mapOf(
			"clear_015" to base.copy(celestialProgress = 0.15f),
			"clear_050" to base.copy(celestialProgress = 0.50f),
			"clear_070" to base.copy(celestialProgress = 0.70f),
			"clear_085" to base.copy(celestialProgress = 0.85f),
			"cloudy_050" to base.copy(celestialProgress = 0.50f, cloudiness = 0.45f),
			"overcast_050" to base.copy(celestialProgress = 0.50f, cloudiness = 0.85f),
			"humid_050" to base.copy(celestialProgress = 0.50f, humidityHazeDensity = 0.25f),
			"fog_050" to base.copy(celestialProgress = 0.50f, fogDensity = 1f),
			"metropolis_050" to base.copy(celestialProgress = 0.50f, backdropScene = BackdropScene.METROPOLIS),
			"mountains_050" to base.copy(celestialProgress = 0.50f, backdropScene = BackdropScene.MOUNTAINS)
		)

		for ((width, height) in listOf(360 to 780, 780 to 360)) {
			for ((name, params) in scenes) {
				val bitmap = renderComplete(renderer, width, height, params)
				try {
					val file = File(context.getExternalFilesDir(null), "dawn_haze_${capture}_${name}_${width}x$height.png")
					FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
				} finally {
					bitmap.recycle()
				}
			}
		}
	}

	private fun assertBackdropsNear(renderer: SceneRenderer, width: Int, height: Int, firstParams: SceneParams, secondParams: SceneParams, tolerance: Int) {
		val first = render(renderer, width, height, firstParams)
		try {
			val second = render(renderer, width, height, secondParams)
			try {
				var maximumDifference = 0
				for (y in 0 until height) {
					for (x in 0 until width) {
						val before = first.getPixel(x, y)
						val after = second.getPixel(x, y)
						maximumDifference = maxOf(maximumDifference, abs(Color.red(before) - Color.red(after)), abs(Color.green(before) - Color.green(after)), abs(Color.blue(before) - Color.blue(after)))
					}
				}

				assertTrue("$width x $height ${firstParams.skyColorPreset} ${firstParams.celestialProgress} -> ${secondParams.celestialProgress}: max channel difference=$maximumDifference", maximumDifference <= tolerance)
			} finally {
				second.recycle()
			}
		} finally {
			first.recycle()
		}
	}
}
