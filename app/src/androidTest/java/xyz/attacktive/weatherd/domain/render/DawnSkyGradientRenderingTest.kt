package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BackdropScene
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

	private fun clearDawn() = SceneParams(dayPhase = DayPhase.DAWN, cloudiness = 0.05f, fogDensity = 0f, precipitation = null, thunder = false, windFactor = 0f, celestialProgress = 0.5f)

	private fun custom() = CustomSkyGradient(startColor = 0xFF224488.toInt(), middleColor = 0xFF66AACC.toInt(), endColor = 0xFFDDEEFF.toInt(), threeColors = true, startPosition = 0.2f, middlePosition = 0.4f, endPosition = 0.8f, direction = SkyGradientDirection.HORIZONTAL)

	private fun render(renderer: SceneRenderer, width: Int, height: Int, params: SceneParams) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
		renderer.renderBackdrop(Canvas(it), width, height, params)
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
