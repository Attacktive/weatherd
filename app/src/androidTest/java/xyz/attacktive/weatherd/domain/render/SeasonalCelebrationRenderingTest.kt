package xyz.attacktive.weatherd.domain.render

import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import android.content.Context
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
import xyz.attacktive.weatherd.domain.model.DEFAULT_LABEL_VERTICAL_POSITION
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.HolidayCelebration
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

@RunWith(AndroidJUnit4::class)
class SeasonalCelebrationRenderingTest {
	private val context: Context
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun halloweenRendersWitchSilhouetteDuringActivePassAndClearsWhenInactive() {
		val startMillis = SeasonalCelebrationFrame.passStartMillis(
			BASE_EPOCH_MILLIS / SeasonalCelebrationFrame.HALLOWEEN_SLOT_MILLIS,
			SeasonalCelebrationFrame.HALLOWEEN_SLOT_MILLIS,
			SeasonalCelebrationFrame.HALLOWEEN_DURATION_MILLIS,
			SeasonalCelebrationFrame.HALLOWEEN_SALT
		)
		val activeMillis = startMillis + 5000L
		val inactiveMillis = startMillis + SeasonalCelebrationFrame.HALLOWEEN_DURATION_MILLIS + 1000L

		val activeParams = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.HALLOWEEN)
		val layer = SeasonalCelebrationLayer()

		val activeBitmap = renderLayer(layer, activeParams, activeMillis)
		try {
			val count = nonTransparentPixelCount(activeBitmap)
			assertTrue("Witch silhouette must render non-transparent pixels during active pass, got $count", count > 50)

			val topPixels = countPixelsInRegion(activeBitmap, 0, (PORTRAIT_HEIGHT * 0.20f).toInt())
			assertEquals("Witch flyby must leave upper sky status area clear", 0, topPixels)
		} finally {
			activeBitmap.recycle()
		}

		val inactiveBitmap = renderLayer(layer, activeParams, inactiveMillis)
		try {
			val count = nonTransparentPixelCount(inactiveBitmap)
			assertEquals("Witch silhouette must render 0 pixels outside active pass", 0, count)
		} finally {
			inactiveBitmap.recycle()
		}

		val daytimeParams = activeParams.copy(dayPhase = DayPhase.DAY)
		val daytimeBitmap = renderLayer(layer, daytimeParams, activeMillis)
		try {
			assertEquals("Witch must render 0 pixels during daylight", 0, nonTransparentPixelCount(daytimeBitmap))
		} finally {
			daytimeBitmap.recycle()
		}
	}

	@Test
	fun christmasRendersSleighAndReindeerWithRudolphNose() {
		val startMillis = SeasonalCelebrationFrame.passStartMillis(
			BASE_EPOCH_MILLIS / SeasonalCelebrationFrame.CHRISTMAS_SLOT_MILLIS,
			SeasonalCelebrationFrame.CHRISTMAS_SLOT_MILLIS,
			SeasonalCelebrationFrame.CHRISTMAS_DURATION_MILLIS,
			SeasonalCelebrationFrame.CHRISTMAS_SALT
		)
		val activeMillis = startMillis + 6000L
		val params = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.CHRISTMAS)
		val layer = SeasonalCelebrationLayer()

		val bitmap = renderLayer(layer, params, activeMillis)
		try {
			val count = nonTransparentPixelCount(bitmap)
			assertTrue("Santa sleigh and reindeer must render visible pixels, got $count", count > 100)

			val topPixels = countPixelsInRegion(bitmap, 0, (PORTRAIT_HEIGHT * 0.20f).toInt())
			assertEquals("Santa flyby must leave upper sky status area clear", 0, topPixels)

			val pixels = IntArray(PORTRAIT_WIDTH * PORTRAIT_HEIGHT)
			bitmap.getPixels(pixels, 0, PORTRAIT_WIDTH, 0, 0, PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
			val hasRudolphGlow = pixels.any { pixel ->
				val a = Color.alpha(pixel)
				val r = Color.red(pixel)
				val g = Color.green(pixel)
				val b = Color.blue(pixel)
				a > 80 && r > 180 && r > g * 2 && r > b * 2
			}

			assertTrue("Lead reindeer must carry a glowing red nose accent", hasRudolphGlow)
		} finally {
			bitmap.recycle()
		}
	}

	@Test
	fun newYearRendersFireworksDuringAscentAndBurstPhases() {
		val startMillis = SeasonalCelebrationFrame.fireworkStartMillis(
			BASE_EPOCH_MILLIS / SeasonalCelebrationFrame.FIREWORK_SLOT_MILLIS
		)
		val ascentMillis = startMillis + 400L
		val burstMillis = startMillis + 1500L
		val params = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.NEW_YEAR)
		val layer = SeasonalCelebrationLayer()

		val ascentBitmap = renderLayer(layer, params, ascentMillis)
		try {
			val count = nonTransparentPixelCount(ascentBitmap)
			assertTrue("Ascending rocket must draw visible pixels, got $count", count > 0)

			val statusBarPixels = countPixelsInRegion(ascentBitmap, 0, (PORTRAIT_HEIGHT * 0.10f).toInt())
			assertEquals("Ascending rocket must stay clear of status bar", 0, statusBarPixels)
		} finally {
			ascentBitmap.recycle()
		}

		val burstBitmap = renderLayer(layer, params, burstMillis)
		try {
			val count = nonTransparentPixelCount(burstBitmap)
			assertTrue("Fireworks burst must draw multiple sparks, got $count", count > 40)

			val statusBarPixels = countPixelsInRegion(burstBitmap, 0, (PORTRAIT_HEIGHT * 0.10f).toInt())
			assertEquals("Fireworks burst must stay clear of status bar", 0, statusBarPixels)
		} finally {
			burstBitmap.recycle()
		}
	}

	@Test
	fun newYearFireworksAscentAndBurstAttenuateWithNightBrightness() {
		val startMillis = SeasonalCelebrationFrame.fireworkStartMillis(
			BASE_EPOCH_MILLIS / SeasonalCelebrationFrame.FIREWORK_SLOT_MILLIS
		)
		val ascentMillis = startMillis + 400L
		val burstMillis = startMillis + 1500L
		val layer = SeasonalCelebrationLayer()

		// 1. Ascent dimming comparison
		val brightAscentParams = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.NEW_YEAR, nightBrightnessScale = 1f)
		val dimAscentParams = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.NEW_YEAR, nightBrightnessScale = 0f)

		val brightAscent = renderLayer(layer, brightAscentParams, ascentMillis)
		val dimAscent = renderLayer(layer, dimAscentParams, ascentMillis)
		try {
			val brightAscentPixels = nonTransparentPixelCount(brightAscent)
			val dimAscentPixels = nonTransparentPixelCount(dimAscent)
			assertTrue("Ascent rocket must render at full brightness", brightAscentPixels > 0)
			assertEquals("Ascent rocket must attenuate to 0 alpha when nightBrightnessScale is zero", 0, dimAscentPixels)
		} finally {
			brightAscent.recycle()
			dimAscent.recycle()
		}

		// 2. Burst dimming comparison
		val brightBurstParams = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.NEW_YEAR, nightBrightnessScale = 1f)
		val dimBurstParams = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.NEW_YEAR, nightBrightnessScale = 0f)

		val brightBurst = renderLayer(layer, brightBurstParams, burstMillis)
		val dimBurst = renderLayer(layer, dimBurstParams, burstMillis)
		try {
			val brightBurstPixels = nonTransparentPixelCount(brightBurst)
			val dimBurstPixels = nonTransparentPixelCount(dimBurst)
			assertTrue("Burst sparks must render at full brightness", brightBurstPixels > 40)
			assertEquals("Burst sparks must attenuate to 0 alpha when nightBrightnessScale is zero", 0, dimBurstPixels)
		} finally {
			brightBurst.recycle()
			dimBurst.recycle()
		}
	}

	@Test
	fun celebrationPreservesProtectedMoonAndDefaultAndCustomLabelPixels() {
		val renderer = SceneRenderer(context.resources)
		renderer.prewarmMoonPhase(0.5f)
		renderer.prewarmCloudTextures()
		val cases = listOf(
			Triple(HolidayCelebration.HALLOWEEN, HALLOWEEN_ACTIVE_MILLIS, DEFAULT_LABEL_VERTICAL_POSITION),
			Triple(HolidayCelebration.CHRISTMAS, CHRISTMAS_ACTIVE_MILLIS, 0.35f),
			Triple(HolidayCelebration.NEW_YEAR, NEW_YEAR_BURST_MILLIS, 0.75f),
			Triple(HolidayCelebration.NEW_YEAR, NEW_YEAR_ASCENT_MILLIS, 0.35f),
			Triple(HolidayCelebration.HALLOWEEN, HALLOWEEN_ACTIVE_MILLIS, 0f),
			Triple(HolidayCelebration.CHRISTMAS, CHRISTMAS_ACTIVE_MILLIS, 1f)
		)

		for ((width, height) in listOf(PORTRAIT_WIDTH to PORTRAIT_HEIGHT, LANDSCAPE_WIDTH to LANDSCAPE_HEIGHT)) {
			for ((holiday, epochMillis, position) in cases) {
				val params = testParams(
					holidayCelebration = holiday,
					labelVerticalPosition = position,
					overlayLabels = OverlayLabels(weather = "Rain · 10°", location = "Tokyo")
				).copy(moonSizeScale = 2f)

				val captureEpoch = if (holiday == HolidayCelebration.NEW_YEAR) {
					epochMillis
				} else {
					moonTransitEpoch(params, epochMillis, width, height)
				}

				assertProtectedPixels(renderer, params, captureEpoch, width, height)
			}
		}
	}

	private fun moonTransitEpoch(params: SceneParams, midpoint: Long, width: Int, height: Int): Long {
		val frame = SeasonalCelebrationFrame()
		for (epoch in midpoint - 10_000L..midpoint + 10_000L step 100L) {
			if (frame.sample(width.toFloat(), height.toFloat(), params, epoch) && abs(frame.flybyX - width * 0.72f) < width * 0.01f) {
				return epoch
			}
		}

		error("The flyby fixture must cross the Moon's horizontal sector")
	}

	private fun assertProtectedPixels(renderer: SceneRenderer, params: SceneParams, epochMillis: Long, width: Int, height: Int) {
		val withHoliday = renderCompleteScene(renderer, params, epochMillis, width, height)
		val withoutHoliday = renderCompleteScene(renderer, params.copy(holidayCelebration = HolidayCelebration.NONE), epochMillis, width, height)
		val labels = createBitmap(width, height)
		renderer.renderOverlayLabels(Canvas(labels), width, height, params)
		val moonRadius = minOf(width, height) * 0.20f

		try {
			val changedPixels = countPixelDifferences(withHoliday, withoutHoliday)
			assertTrue("${params.holidayCelebration} must remain a small visible sky detail at ${width}x$height, position ${params.labelVerticalPosition}: $changedPixels changed pixels", changedPixels in 8..(width * height / 40))
			for (y in 0 until height) {
				for (x in 0 until width) {
					val dx = x - width * 0.72f
					val dy = y - height * 0.24f
					val protected = dx * dx + dy * dy <= moonRadius * moonRadius || Color.alpha(labels.getPixel(x, y)) > 0
					if (protected) {
						assertEquals(
							"${params.holidayCelebration} must preserve Moon/label pixel ($x,$y) at ${width}x$height, position ${params.labelVerticalPosition}",
							withoutHoliday.getPixel(x, y),
							withHoliday.getPixel(x, y)
						)
					}
				}
			}
		} finally {
			withHoliday.recycle()
			withoutHoliday.recycle()
			labels.recycle()
		}
	}

	@Test
	fun seasonalCelebrationsRenderAcrossClearCloudyRainyFoggyAndCityScenes() {
		val celebrations = listOf(
			HolidayCelebration.HALLOWEEN to HALLOWEEN_ACTIVE_MILLIS,
			HolidayCelebration.CHRISTMAS to CHRISTMAS_ACTIVE_MILLIS,
			HolidayCelebration.NEW_YEAR to NEW_YEAR_BURST_MILLIS
		)

		val scenes = listOf(
			"clear_portrait" to { testParams(dayPhase = DayPhase.NIGHT) },
			"clouds" to { testParams(dayPhase = DayPhase.NIGHT, cloudiness = 0.65f) },
			"rain" to { testParams(dayPhase = DayPhase.NIGHT, precipitation = Precipitation(PrecipitationKind.RAIN, 0.7f, 0.7f)) },
			"fog" to { testParams(dayPhase = DayPhase.NIGHT, fogDensity = 0.5f) },
			"city" to { testParams(dayPhase = DayPhase.NIGHT, backdropScene = BackdropScene.METROPOLIS) }
		)

		val renderer = SceneRenderer(context.resources)
		renderer.prewarmMoonPhase(0.5f)
		renderer.prewarmCloudTextures()

		for ((celebration, epochMillis) in celebrations) {
			val celebrationName = celebration.name.lowercase()

			for ((sceneName, sceneParamsBuilder) in scenes) {
				val baseParams = sceneParamsBuilder()
				val paramsWithCelebration = baseParams.copy(holidayCelebration = celebration)
				val paramsWithoutCelebration = baseParams.copy(holidayCelebration = HolidayCelebration.NONE)

				val fullSceneBitmap = renderCompleteScene(renderer, paramsWithCelebration, epochMillis, PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
				val baseSceneBitmap = renderCompleteScene(renderer, paramsWithoutCelebration, epochMillis, PORTRAIT_WIDTH, PORTRAIT_HEIGHT)

				try {
					val diffPixels = countPixelDifferences(fullSceneBitmap, baseSceneBitmap)
					assertTrue(
						"Celebration $celebration in scene $sceneName must render visible changes, got diff=$diffPixels",
						diffPixels > 0
					)

					maybeSaveEvidence("${celebrationName}_${sceneName}_portrait") { fullSceneBitmap }
				} finally {
					fullSceneBitmap.recycle()
					baseSceneBitmap.recycle()
				}
			}

			val landscapeParamsWith = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = celebration)
			val landscapeParamsWithout = testParams(dayPhase = DayPhase.NIGHT, holidayCelebration = HolidayCelebration.NONE)

			val landscapeFull = renderCompleteScene(renderer, landscapeParamsWith, epochMillis, LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT)
			val landscapeBase = renderCompleteScene(renderer, landscapeParamsWithout, epochMillis, LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT)

			try {
				val diffPixels = countPixelDifferences(landscapeFull, landscapeBase)
				assertTrue(
					"Celebration $celebration in landscape must render visible changes, got diff=$diffPixels",
					diffPixels > 0
				)

				maybeSaveEvidence("${celebrationName}_clear_landscape") { landscapeFull }
			} finally {
				landscapeFull.recycle()
				landscapeBase.recycle()
			}
		}
	}

	private fun renderCompleteScene(
		renderer: SceneRenderer,
		params: SceneParams,
		epochMillis: Long,
		width: Int,
		height: Int
	): Bitmap {
		val bitmap = createBitmap(width, height)
		val canvas = Canvas(bitmap)
		renderer.render(canvas, width, height, params, timeSeconds = 0f, celebrationEpochMillis = epochMillis)

		return bitmap
	}

	private fun renderLayer(
		layer: SeasonalCelebrationLayer,
		params: SceneParams,
		epochMillis: Long,
		width: Int = PORTRAIT_WIDTH,
		height: Int = PORTRAIT_HEIGHT
	): Bitmap {
		val bitmap = createBitmap(width, height)
		val canvas = Canvas(bitmap)
		layer.draw(canvas, width.toFloat(), height.toFloat(), params, epochMillis)

		return bitmap
	}

	private fun nonTransparentPixelCount(bitmap: Bitmap): Int {
		val pixels = IntArray(bitmap.width * bitmap.height)
		bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

		return pixels.count { Color.alpha(it) > 0 }
	}

	private fun countPixelsInRegion(bitmap: Bitmap, topY: Int, bottomY: Int): Int {
		val w = bitmap.width
		val h = bottomY - topY
		val pixels = IntArray(w * h)
		bitmap.getPixels(pixels, 0, w, 0, topY, w, h)

		return pixels.count { Color.alpha(it) > 0 }
	}

	private fun countPixelDifferences(bitmapA: Bitmap, bitmapB: Bitmap): Int {
		val pixelsA = IntArray(bitmapA.width * bitmapA.height)
		val pixelsB = IntArray(bitmapB.width * bitmapB.height)
		bitmapA.getPixels(pixelsA, 0, bitmapA.width, 0, 0, bitmapA.width, bitmapA.height)
		bitmapB.getPixels(pixelsB, 0, bitmapB.width, 0, 0, bitmapB.width, bitmapB.height)

		var diff = 0
		for (i in pixelsA.indices) {
			if (pixelsA[i] != pixelsB[i]) {
				diff++
			}
		}

		return diff
	}

	private fun maybeSaveEvidence(name: String, render: () -> Bitmap) {
		val args = InstrumentationRegistry.getArguments()
		val captureRequested = args.getString(ARG_SEASONAL_EVIDENCE)?.toBoolean() == true || args.containsKey(ARG_SEASONAL_EVIDENCE)
		if (!captureRequested) {
			return
		}

		val dir = context.getExternalFilesDir("seasonal_captures") ?: context.getExternalFilesDir(null) ?: return
		val file = File(dir, "$name.png")
		val bitmap = render()
		FileOutputStream(file).use { out ->
			bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
		}
	}

	private fun testParams(
		dayPhase: DayPhase = DayPhase.NIGHT,
		holidayCelebration: HolidayCelebration = HolidayCelebration.HALLOWEEN,
		cloudiness: Float = 0f,
		fogDensity: Float = 0f,
		precipitation: Precipitation? = null,
		backdropScene: BackdropScene = BackdropScene.NONE,
		moonVisible: Boolean = true,
		nightBrightnessScale: Float = 1f,
		labelVerticalPosition: Float = DEFAULT_LABEL_VERTICAL_POSITION,
		overlayLabels: OverlayLabels? = null
	) = SceneParams(
		dayPhase = dayPhase,
		cloudiness = cloudiness,
		fogDensity = fogDensity,
		precipitation = precipitation,
		thunder = false,
		windFactor = 0f,
		moonVisible = moonVisible,
		backdropScene = backdropScene,
		nightBrightnessScale = nightBrightnessScale,
		labelVerticalPosition = labelVerticalPosition,
		overlayLabels = overlayLabels,
		holidayCelebration = holidayCelebration
	)

	companion object {
		const val PORTRAIT_WIDTH = 360
		const val PORTRAIT_HEIGHT = 780
		const val LANDSCAPE_WIDTH = 780
		const val LANDSCAPE_HEIGHT = 360

		const val BASE_EPOCH_MILLIS = 1761800000000L
		val HALLOWEEN_ACTIVE_MILLIS = flybyMidpoint(SeasonalCelebrationFrame.HALLOWEEN_SLOT_MILLIS, SeasonalCelebrationFrame.HALLOWEEN_DURATION_MILLIS, SeasonalCelebrationFrame.HALLOWEEN_SALT)
		val CHRISTMAS_ACTIVE_MILLIS = flybyMidpoint(SeasonalCelebrationFrame.CHRISTMAS_SLOT_MILLIS, SeasonalCelebrationFrame.CHRISTMAS_DURATION_MILLIS, SeasonalCelebrationFrame.CHRISTMAS_SALT)
		val NEW_YEAR_BURST_MILLIS = SeasonalCelebrationFrame.fireworkStartMillis(BASE_EPOCH_MILLIS / SeasonalCelebrationFrame.FIREWORK_SLOT_MILLIS) + 1800L
		val NEW_YEAR_ASCENT_MILLIS = SeasonalCelebrationFrame.fireworkStartMillis(BASE_EPOCH_MILLIS / SeasonalCelebrationFrame.FIREWORK_SLOT_MILLIS) + 400L

		private fun flybyMidpoint(period: Long, duration: Long, salt: Long) =
			SeasonalCelebrationFrame.passStartMillis(BASE_EPOCH_MILLIS / period, period, duration, salt) + duration / 2L

		const val ARG_SEASONAL_EVIDENCE = "seasonalCelebrationEvidence"
	}
}
