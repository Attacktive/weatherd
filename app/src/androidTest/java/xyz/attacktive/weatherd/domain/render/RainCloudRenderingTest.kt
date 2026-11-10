package xyz.attacktive.weatherd.domain.render

import kotlin.math.floor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.BuildConfig
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

@RunWith(AndroidJUnit4::class)
class RainCloudRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
	private val rainy = SceneParams(DayPhase.DAY, 1f, 0f, Precipitation(PrecipitationKind.RAIN, 0.5f, 1f), false, 0.5f)

	@Test
	fun clippingFollowsTheSilhouetteAndRestoresTheCanvasForOverlays() {
		val bitmap = createBitmap(200, 400)
		try {
			val canvas = Canvas(bitmap)
			val clip = RainCloudClip()
			val profile = CloudAlphaProfile.fromPixels(2, 4, intArrayOf(-1, -1, -1, -1, 0, -1, 0, -1))
			clip.boundary.reset(200f, 400f)
			clip.boundary.include(profile, 0f, 20f, 200f, 200f, 255)
			val checkpoint = canvas.save()
			try {
				clip.clip(canvas)
				canvas.drawColor(Color.RED)
			} finally {
				canvas.restoreToCount(checkpoint)
			}

			assertEquals(Color.TRANSPARENT, bitmap.getPixel(50, 110))
			assertEquals(Color.RED, bitmap.getPixel(50, 140))
			assertEquals(Color.TRANSPARENT, bitmap.getPixel(150, 200))
			assertEquals(Color.RED, bitmap.getPixel(150, 240))
			canvas.drawRect(0f, 0f, 200f, 10f, Paint().apply { color = Color.BLUE })
			assertEquals(Color.BLUE, bitmap.getPixel(150, 5))
		} finally {
			bitmap.recycle()
		}
	}

	@Test
	fun theClipUsesCanvasCoordinatesUnderWallpaperTranslation() {
		val bitmap = createBitmap(200, 400)
		try {
			val canvas = Canvas(bitmap)
			val clip = RainCloudClip()
			clip.boundary.reset(200f, 400f)
			clip.boundary.include(CloudAlphaProfile.fromPixels(1, 1, intArrayOf(-1)), 0f, 0f, 200f, 100f, 255)
			val checkpoint = canvas.save()
			try {
				canvas.translate(0f, 30f)
				clip.clip(canvas)
				canvas.drawColor(Color.RED)
			} finally {
				canvas.restoreToCount(checkpoint)
			}

			assertEquals(Color.TRANSPARENT, bitmap.getPixel(100, 120))
			assertEquals(Color.RED, bitmap.getPixel(100, 140))
		} finally {
			bitmap.recycle()
		}
	}

	@Test
	fun fullyCoveredLandscapeDoesNotCollapseTheRainClip() {
		val bitmap = createBitmap(528, 240)
		try {
			val canvas = Canvas(bitmap)
			val clip = RainCloudClip()
			val profile = CloudAlphaProfile.fromPixels(1, 1, intArrayOf(-1))
			clip.boundary.reset(528f, 240f)
			clip.boundary.include(profile, 0f, -240f, 528f, 480f, 255)
			assertTrue(clip.boundary.active)
			assertTrue(clip.boundary.lowerEdges.all { it == 240f })
			val checkpoint = canvas.save()
			try {
				clip.clip(canvas)
				canvas.drawColor(Color.RED)
			} finally {
				canvas.restoreToCount(checkpoint)
			}

			assertEquals(Color.RED, bitmap.getPixel(264, 12))
			assertEquals(Color.RED, bitmap.getPixel(264, 220))
		} finally {
			bitmap.recycle()
		}
	}

	@Test
	fun landscapeMaximumCloudSizeStillRendersRain() {
		assumeTrue(BuildConfig.STORM_CLOUD_PROTOTYPE)
		val width = 528
		val height = 240
		val params = rainy.copy(cloudSizeScale = 2f)
		val renderer = SceneRenderer(resources, epochDay = 0L)
		renderer.prewarmCloudTextures()
		val withoutRain = createBitmap(width, height)
		val withRain = createBitmap(width, height)
		try {
			renderer.render(Canvas(withoutRain), width, height, params.copy(precipitationScale = 0f), 7f, celebrationEpochMillis = 0L)
			renderer.render(Canvas(withRain), width, height, params, 7f, celebrationEpochMillis = 0L)
			var changed = 0
			for (y in 0 until height) {
				for (x in 0 until width) {
					if (withoutRain.getPixel(x, y) != withRain.getPixel(x, y)) {
						changed++
					}
				}
			}

			assertTrue("Rain must remain visible with 200% clouds in landscape", changed > 0)
		} finally {
			withoutRain.recycle()
			withRain.recycle()
		}
	}

	@Test
	fun legacyCloudBankPublishesItsProfileOnlyWhenVisible() {
		val bank = CloudLayer(resources, R.drawable.cloud_overcast_hero)
		val boundary = CloudRainBoundary()
		val geometry = CloudDrawGeometry().configure(240f, 160f, 30f, 0f, 1f)
		val bitmap = createBitmap(240, 528)
		try {
			boundary.reset(240f, 528f)
			bank.draw(Canvas(bitmap), geometry, Color.WHITE, 255, CloudColorGrade.IDENTITY, rainBoundary = boundary)
			assertTrue(boundary.active)
			assertTrue(boundary.lowerEdges.any { it > 0f })
			boundary.reset(240f, 528f)
			bank.draw(Canvas(bitmap), geometry, Color.WHITE, 0, CloudColorGrade.IDENTITY, rainBoundary = boundary)
			assertFalse(boundary.active)
		} finally {
			bitmap.recycle()
		}
	}

	@Test
	fun packagedStormCloudsCoverTheUpperEdgeAcrossShapesAndSizes() {
		assumeTrue(BuildConfig.STORM_CLOUD_PROTOTYPE)
		val prototype = StormCloudPrototype(resources)
		prototype.prewarm()
		for ((width, height) in listOf(240 to 528, 528 to 240, 360 to 360)) {
			val bitmap = createBitmap(width, height)
			try {
				for (size in listOf(0.5f, 1f, 2f)) {
					for (time in listOf(0f, 60f, 600f)) {
						bitmap.eraseColor(Color.TRANSPARENT)
						assertTrue(prototype.draw(Canvas(bitmap), width.toFloat(), height.toFloat(), rainy.copy(cloudSizeScale = size), time, Color.WHITE, CloudColorGrade.IDENTITY))
						for (x in 0 until width) {
							assertTrue("Missing top coverage at $width x $height, size=$size, time=$time, x=$x", Color.alpha(bitmap.getPixel(x, 0)) > 0)
						}
					}
				}
			} finally {
				bitmap.recycle()
			}
		}
	}

	@Test
	fun rainChangesPixelsOnlyBelowTheCloudBoundaryAcrossPhasesAndOrientations() {
		assumeTrue(BuildConfig.STORM_CLOUD_PROTOTYPE)
		val renderer = SceneRenderer(resources, epochDay = 0L)
		val prototype = StormCloudPrototype(resources)
		renderer.prewarmCloudTextures()
		prototype.prewarm()
		val boundary = CloudRainBoundary()
		for ((width, height) in listOf(240 to 528, 528 to 240)) {
			val withoutRain = createBitmap(width, height)
			val withRain = createBitmap(width, height)
			try {
				for (phase in DayPhase.entries) {
					for (time in listOf(0f, 30f, 600f)) {
						val params = rainy.copy(dayPhase = phase, precipitationScale = 16f)
						boundary.reset(width.toFloat(), height.toFloat())
						assertTrue(prototype.draw(Canvas(), width.toFloat(), height.toFloat(), params, time, Color.WHITE, CloudColorGrade.IDENTITY, boundary))
						assertTrue(boundary.active)
						withoutRain.eraseColor(Color.TRANSPARENT)
						withRain.eraseColor(Color.TRANSPARENT)
						renderer.render(Canvas(withoutRain), width, height, params.copy(precipitationScale = 0f), time, celebrationEpochMillis = 0L)
						renderer.render(Canvas(withRain), width, height, params, time, celebrationEpochMillis = 0L)
						assertRainDifferenceIsBelowClouds(withoutRain, withRain, boundary, phase, time)
					}
				}
			} finally {
				withoutRain.recycle()
				withRain.recycle()
			}
		}
	}

	@Test
	fun hidingCloudsDoesNotKeepThePreviousFramesRainClip() {
		val renderer = SceneRenderer(resources, epochDay = 0L)
		renderer.prewarmCloudTextures()
		val withoutRain = createBitmap(240, 528)
		val withRain = createBitmap(240, 528)
		try {
			renderer.render(Canvas(withRain), 240, 528, rainy, 0f, celebrationEpochMillis = 0L)
			val cloudless = rainy.copy(cloudScale = 0f)
			renderer.render(Canvas(withoutRain), 240, 528, cloudless.copy(precipitationScale = 0f), 0f, celebrationEpochMillis = 0L)
			renderer.render(Canvas(withRain), 240, 528, cloudless, 0f, celebrationEpochMillis = 0L)
			var changed = 0
			for (y in 0 until 80) {
				for (x in 0 until 240) {
					if (withoutRain.getPixel(x, y) != withRain.getPixel(x, y)) {
						changed++
					}
				}
			}

			assertTrue("Rain should remain visible when clouds are disabled", changed > 0)
		} finally {
			withoutRain.recycle()
			withRain.recycle()
		}
	}

	@Test
	fun switchingFromRainToDrySnowOrSleetMatchesAFreshRenderer() {
		val reused = SceneRenderer(resources, epochDay = 0L)
		val fresh = SceneRenderer(resources, epochDay = 0L)
		reused.prewarmCloudTextures()
		fresh.prewarmCloudTextures()
		val expected = createBitmap(240, 528)
		val actual = createBitmap(240, 528)
		try {
			for (precipitation in listOf(null, Precipitation(PrecipitationKind.SNOW, 0.5f, 1f), Precipitation(PrecipitationKind.SLEET, 0.5f, 1f))) {
				reused.render(Canvas(actual), 240, 528, rainy, 0f, celebrationEpochMillis = 0L)
				val params = rainy.copy(precipitation = precipitation)
				reused.render(Canvas(actual), 240, 528, params, 0f, celebrationEpochMillis = 0L)
				fresh.render(Canvas(expected), 240, 528, params, 0f, celebrationEpochMillis = 0L)
				assertTrue("Previous rain must not affect $precipitation", expected.sameAs(actual))
			}
		} finally {
			expected.recycle()
			actual.recycle()
		}
	}

	private fun assertRainDifferenceIsBelowClouds(before: Bitmap, after: Bitmap, boundary: CloudRainBoundary, phase: DayPhase, timeSeconds: Float) {
		val clipped = boundary.hasVisibleAreaBelowClouds()
		val lowest = boundary.lowerEdges.minOrNull()
		val highest = boundary.lowerEdges.maxOrNull()
		val context = "phase=$phase, viewport=${before.width}x${before.height}, time=$timeSeconds, clipped=$clipped, cloudBase=$lowest..$highest"
		var changed = 0
		for (x in 0 until before.width) {
			val position = (x + 0.5f) / before.width * (boundary.lowerEdges.size - 1)
			val left = floor(position).toInt()
			val fraction = position - left
			val edge = boundary.lowerEdges[left] + (boundary.lowerEdges[left + 1] - boundary.lowerEdges[left]) * fraction
			for (y in 0 until before.height) {
				if (before.getPixel(x, y) != after.getPixel(x, y)) {
					if (clipped) {
						assertTrue("Rain changed pixel above cloud base at ($x, $y), boundary=$edge; $context", y + 1f >= edge)
					}

					changed++
				}
			}
		}

		assertTrue("Expected visible rain; $context", changed > 0)
	}

}
