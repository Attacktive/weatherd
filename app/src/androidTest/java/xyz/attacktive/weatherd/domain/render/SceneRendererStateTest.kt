package xyz.attacktive.weatherd.domain.render

import java.io.File
import android.graphics.Bitmap
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.CustomSkyGradient
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SkyGradientDirection

import kotlin.math.abs
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class SceneRendererStateTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun phaseSwitchBackdropStaysOpaqueAfterNightForeground() {
		val renderer = SceneRenderer(resources)
		val night = SceneParams(
			dayPhase = DayPhase.NIGHT,
			cloudiness = 0f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			moonVisible = false
		)

		val foreground = createBitmap(WIDTH, HEIGHT)
		renderer.renderForeground(Canvas(foreground), WIDTH, HEIGHT, night, timeSeconds = 0f)
		foreground.recycle()

		val backdrop = createBitmap(WIDTH, HEIGHT)
		renderer.renderBackdrop(Canvas(backdrop), WIDTH, HEIGHT, night.copy(dayPhase = DayPhase.DAY))
		val pixel = backdrop.getPixel(WIDTH / 2, HEIGHT / 2)

		assertEquals(
			"Rebuilding a backdrop after a translucent foreground pass must still produce an opaque sky",
			255,
			Color.alpha(pixel)
		)

		backdrop.recycle()
	}

	@Test
	fun blackNightBrightnessDarkensPhotoBackdropWithoutChangingDay() {
		val renderer = SceneRenderer(resources)
		val photo = createBitmap(WIDTH, HEIGHT)
		photo.eraseColor(Color.WHITE)
		renderer.backgroundPhoto = photo

		val nightParams = SceneParams(
			dayPhase = DayPhase.NIGHT,
			cloudiness = 0f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			backdropScene = BackdropScene.PHOTO,
			nightBrightnessScale = 0f,
			moonVisible = false
		)

		val nightBackdrop = createBitmap(WIDTH, HEIGHT)
		renderer.renderBackdrop(Canvas(nightBackdrop), WIDTH, HEIGHT, nightParams)
		val nightPixel = nightBackdrop.getPixel(WIDTH / 2, HEIGHT / 2)

		assertEquals(Color.BLACK, nightPixel)

		val dayBackdrop = createBitmap(WIDTH, HEIGHT)
		renderer.renderBackdrop(Canvas(dayBackdrop), WIDTH, HEIGHT, nightParams.copy(dayPhase = DayPhase.DAY))
		val dayPixel = dayBackdrop.getPixel(WIDTH / 2, HEIGHT / 2)

		assertEquals(Color.WHITE, dayPixel)

		renderer.backgroundPhoto = null
		photo.recycle()
		nightBackdrop.recycle()
		dayBackdrop.recycle()
	}

	@Test
	fun blackNightBrightnessDarkensStaticWeatherLayers() {
		val renderer = SceneRenderer(resources)
		val clearNight = SceneParams(
			dayPhase = DayPhase.NIGHT,
			cloudiness = 0f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			nightBrightnessScale = 0f,
			moonVisible = false
		)

		val rainyNight = clearNight.copy(
			cloudiness = 0.75f,
			precipitation = Precipitation(
				kind = PrecipitationKind.RAIN,
				severity = 1f,
				observed = 1f
			)
		)

		assertBackdropIsBlack(renderer, clearNight)
		assertBackdropIsBlack(renderer, clearNight.copy(cloudiness = 0.85f))
		assertBackdropIsBlack(renderer, rainyNight)
	}

	@Test
	fun customSkyGradientRendersVerticalHorizontalAndDiagonalWithStopInterpolation() {
		val renderer = SceneRenderer(resources)
		assertThreeColorCustomSkyGradients(renderer)
		assertTwoColorCustomSkyGradients(renderer)
	}

	private fun assertThreeColorCustomSkyGradients(renderer: SceneRenderer) {
		val colorStart = 0xFFFF0000.toInt()
		val colorMiddle = 0xFF00FF00.toInt()
		val colorEnd = 0xFF0000FF.toInt()

		val baseParams = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = 0f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			skyColorPreset = SkyColorPreset.CUSTOM,
			customSkyGradient = CustomSkyGradient(
				startColor = colorStart,
				middleColor = colorMiddle,
				endColor = colorEnd,
				threeColors = true,
				startPosition = 0f,
				middlePosition = 0.5f,
				endPosition = 1f,
				direction = SkyGradientDirection.VERTICAL
			)
		)

		val verticalBitmap = renderSky(renderer, baseParams, "custom-sky-3-color-vertical.png")
		val topPixel = verticalBitmap.getPixel(WIDTH / 2, 2)
		val midPixel = verticalBitmap.getPixel(WIDTH / 2, HEIGHT / 2)
		val bottomPixel = verticalBitmap.getPixel(WIDTH / 2, HEIGHT - 3)

		assertEquals(255, Color.alpha(topPixel))
		assertRedDominated("Top pixel should be dominated by red", topPixel)
		assertGreenDominated("Mid pixel should be dominated by green", midPixel)
		assertBlueDominated("Bottom pixel should be dominated by blue", bottomPixel)
		verticalBitmap.recycle()

		val horizontalParams = baseParams.copy(
			customSkyGradient = baseParams.customSkyGradient.copy(direction = SkyGradientDirection.HORIZONTAL)
		)

		val horizontalBitmap = renderSky(renderer, horizontalParams, "custom-sky-3-color-horizontal.png")
		val leftPixel = horizontalBitmap.getPixel(2, HEIGHT / 2)
		val centerPixel = horizontalBitmap.getPixel(WIDTH / 2, HEIGHT / 2)
		val rightPixel = horizontalBitmap.getPixel(WIDTH - 3, HEIGHT / 2)

		assertRedDominated("Left pixel should be dominated by red", leftPixel)
		assertGreenDominated("Center pixel should be dominated by green", centerPixel)
		assertBlueDominated("Right pixel should be dominated by blue", rightPixel)
		horizontalBitmap.recycle()

		val diagonalParams = baseParams.copy(
			customSkyGradient = baseParams.customSkyGradient.copy(direction = SkyGradientDirection.DIAGONAL)
		)

		val diagonalBitmap = renderSky(renderer, diagonalParams, "custom-sky-3-color-diagonal.png")
		val topLeftPixel = diagonalBitmap.getPixel(2, 2)
		val diagCenterPixel = diagonalBitmap.getPixel(WIDTH / 2, HEIGHT / 2)
		val bottomRightPixel = diagonalBitmap.getPixel(WIDTH - 3, HEIGHT - 3)

		assertRedDominated("Top-left pixel should be dominated by red", topLeftPixel)
		assertGreenDominated("Diag center pixel should be dominated by green", diagCenterPixel)
		assertBlueDominated("Bottom-right pixel should be dominated by blue", bottomRightPixel)
		diagonalBitmap.recycle()
	}

	private fun assertTwoColorCustomSkyGradients(renderer: SceneRenderer) {
		val colorStart = 0xFFFF0000.toInt()
		val colorEnd = 0xFF0000FF.toInt()

		val baseParams = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = 0f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			skyColorPreset = SkyColorPreset.CUSTOM,
			customSkyGradient = CustomSkyGradient(
				startColor = colorStart,
				endColor = colorEnd,
				threeColors = false,
				startPosition = 0f,
				endPosition = 1f,
				direction = SkyGradientDirection.VERTICAL
			)
		)

		val verticalBitmap = renderSky(renderer, baseParams, "custom-sky-2-color-vertical.png")
		val twoTopPixel = verticalBitmap.getPixel(WIDTH / 2, 2)
		val twoBottomPixel = verticalBitmap.getPixel(WIDTH / 2, HEIGHT - 3)

		assertTwoColorRed("2-color vertical top should be red", twoTopPixel)
		assertTwoColorBlue("2-color vertical bottom should be blue", twoBottomPixel)
		verticalBitmap.recycle()

		val horizontalParams = baseParams.copy(
			customSkyGradient = baseParams.customSkyGradient.copy(direction = SkyGradientDirection.HORIZONTAL)
		)

		val horizontalBitmap = renderSky(renderer, horizontalParams, "custom-sky-2-color-horizontal.png")
		val twoLeftPixel = horizontalBitmap.getPixel(2, HEIGHT / 2)
		val twoRightPixel = horizontalBitmap.getPixel(WIDTH - 3, HEIGHT / 2)

		assertTwoColorRed("2-color horizontal left should be red", twoLeftPixel)
		assertTwoColorBlue("2-color horizontal right should be blue", twoRightPixel)
		horizontalBitmap.recycle()

		val diagonalParams = baseParams.copy(
			customSkyGradient = baseParams.customSkyGradient.copy(direction = SkyGradientDirection.DIAGONAL)
		)

		val diagonalBitmap = renderSky(renderer, diagonalParams, "custom-sky-2-color-diagonal.png")
		val twoTopLeftPixel = diagonalBitmap.getPixel(2, 2)
		val twoBottomRightPixel = diagonalBitmap.getPixel(WIDTH - 3, HEIGHT - 3)

		assertTwoColorRed("2-color diagonal top-left should be red", twoTopLeftPixel)
		assertTwoColorBlue("2-color diagonal bottom-right should be blue", twoBottomRightPixel)
		diagonalBitmap.recycle()
	}

	private fun renderSky(renderer: SceneRenderer, params: SceneParams, filename: String): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		renderer.renderBackdrop(Canvas(bitmap), WIDTH, HEIGHT, params)
		saveCapture(bitmap, filename)
		return bitmap
	}

	private fun assertRedDominated(message: String, pixel: Int) {
		assertTrue(message, Color.red(pixel) > 180)
		assertTrue(message, Color.green(pixel) < 60)
		assertTrue(message, Color.blue(pixel) < 60)
	}

	private fun assertGreenDominated(message: String, pixel: Int) {
		assertTrue(message, Color.green(pixel) > 180)
		assertTrue(message, Color.red(pixel) < 60)
		assertTrue(message, Color.blue(pixel) < 60)
	}

	private fun assertBlueDominated(message: String, pixel: Int) {
		assertTrue(message, Color.blue(pixel) > 180)
		assertTrue(message, Color.red(pixel) < 60)
		assertTrue(message, Color.green(pixel) < 60)
	}

	private fun assertTwoColorRed(message: String, pixel: Int) {
		assertTrue(message, Color.red(pixel) > 180)
		assertTrue(message, Color.blue(pixel) < 60)
	}

	private fun assertTwoColorBlue(message: String, pixel: Int) {
		assertTrue(message, Color.blue(pixel) > 180)
		assertTrue(message, Color.red(pixel) < 60)
	}

	@Test
	fun cachedSkyGradientUpdatesWhenPaletteRelevantParamsChangeAndReusesInstanceWhenUnchanged() {
		val renderer = SceneRenderer(resources)
		val initialParams = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = 0f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			skyColorPreset = SkyColorPreset.CUSTOM,
			customSkyGradient = CustomSkyGradient(
				startColor = 0xFF112233.toInt(),
				middleColor = 0xFF445566.toInt(),
				endColor = 0xFF778899.toInt(),
				threeColors = true,
				middlePosition = 0.5f
			)
		)

		val first = renderer.cachedSkyGradient(initialParams)
		val second = renderer.cachedSkyGradient(initialParams)
		assertSame("Identical reference must return cached instance", first, second)

		val unchangedPaletteMutatedOtherField = renderer.cachedSkyGradient(initialParams.copy(windScale = 2f))
		assertSame("Unchanged palette fields must return cached instance without allocation", first, unchangedPaletteMutatedOtherField)

		val middleChanged = renderer.cachedSkyGradient(
			initialParams.copy(
				customSkyGradient = initialParams.customSkyGradient.copy(middleColor = 0xFFAABBCC.toInt())
			)
		)
		assertNotEquals(first, middleChanged)
		assertEquals(0xFFAABBCC.toInt(), middleChanged.middleColor)

		val endpointsChanged = renderer.cachedSkyGradient(
			initialParams.copy(
				customSkyGradient = initialParams.customSkyGradient.copy(startColor = 0xFFFF0000.toInt(), endColor = 0xFF0000FF.toInt())
			)
		)
		assertNotEquals(first, endpointsChanged)
		assertEquals(0xFFFF0000.toInt(), endpointsChanged.topColor)
		assertEquals(0xFF0000FF.toInt(), endpointsChanged.bottomColor)

		val nightChanged = renderer.cachedSkyGradient(initialParams.copy(dayPhase = DayPhase.NIGHT))
		assertNotEquals(first, nightChanged)

		val overcastChanged = renderer.cachedSkyGradient(initialParams.copy(cloudiness = 0.85f))
		assertNotEquals(first, overcastChanged)
		assertEquals(overcastChanged.topColor, overcastChanged.bottomColor)
	}

	@Test
	fun customSkyGradientHandlesDuplicateAndCoincidentStopsSafely() {
		val renderer = SceneRenderer(resources)
		val testCases = listOf(
			CustomSkyGradient(
				startColor = 0xFFFF0000.toInt(),
				middleColor = 0xFF00FF00.toInt(),
				endColor = 0xFF0000FF.toInt(),
				threeColors = true,
				startPosition = 0.3f,
				middlePosition = 0.3f,
				endPosition = 0.8f
			),
			CustomSkyGradient(
				startColor = 0xFFFF0000.toInt(),
				middleColor = 0xFF00FF00.toInt(),
				endColor = 0xFF0000FF.toInt(),
				threeColors = true,
				startPosition = 0.3f,
				middlePosition = 0.8f,
				endPosition = 0.8f
			),
			CustomSkyGradient(
				startColor = 0xFFFF0000.toInt(),
				middleColor = 0xFF00FF00.toInt(),
				endColor = 0xFF0000FF.toInt(),
				threeColors = true,
				startPosition = 0.5f,
				middlePosition = 0.5f,
				endPosition = 0.5f
			),
			CustomSkyGradient(
				startColor = 0xFFFF0000.toInt(),
				endColor = 0xFF0000FF.toInt(),
				threeColors = false,
				startPosition = 0.5f,
				endPosition = 0.5f
			)
		)

		for (custom in testCases) {
			val params = SceneParams(
				dayPhase = DayPhase.DAY,
				cloudiness = 0f,
				fogDensity = 0f,
				precipitation = null,
				thunder = false,
				windFactor = 0f,
				skyColorPreset = SkyColorPreset.CUSTOM,
				customSkyGradient = custom
			)
			val bitmap = createBitmap(WIDTH, HEIGHT)
			renderer.renderBackdrop(Canvas(bitmap), WIDTH, HEIGHT, params)
			val pixel = bitmap.getPixel(WIDTH / 2, HEIGHT / 2)
			assertEquals(255, Color.alpha(pixel))
			bitmap.recycle()
		}
	}

	@Test
	fun photoBackdropTakesPrecedenceOverCustomSkyGradient() {
		val renderer = SceneRenderer(resources)
		val photo = createBitmap(WIDTH, HEIGHT)
		photo.eraseColor(Color.MAGENTA)
		renderer.backgroundPhoto = photo

		val params = SceneParams(
			dayPhase = DayPhase.DAY,
			cloudiness = 0f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0f,
			backdropScene = BackdropScene.PHOTO,
			skyColorPreset = SkyColorPreset.CUSTOM,
			customSkyGradient = CustomSkyGradient(
				startColor = Color.CYAN,
				endColor = Color.YELLOW
			)
		)
		val backdrop = createBitmap(WIDTH, HEIGHT)
		renderer.renderBackdrop(Canvas(backdrop), WIDTH, HEIGHT, params)
		val pixel = backdrop.getPixel(WIDTH / 2, HEIGHT / 2)

		assertEquals(Color.MAGENTA, pixel)

		renderer.backgroundPhoto = null
		photo.recycle()
		backdrop.recycle()
	}

	@Test
	fun customSkyGradientMatchesNaturalBackdropAtNightAndZeroCustomTwilight() {
		val renderer = SceneRenderer(resources)
		val testPhases = listOf(
			DayPhase.NIGHT to 0.5f,
			DayPhase.DAWN to 0f,
			DayPhase.DAWN to 0.5f,
			DayPhase.DUSK to 0.5f,
			DayPhase.DUSK to 1f
		)

		for ((phase, progress) in testPhases) {
			val naturalParams = clearSceneParams(phase, progress)
			val naturalBitmap = renderSky(
				renderer,
				naturalParams,
				"natural-backdrop-${phase.name.lowercase()}-p$progress.png"
			)

			for ((configName, custom) in customSkyFixtures()) {
				val customParams = naturalParams.copy(
					skyColorPreset = SkyColorPreset.CUSTOM,
					customSkyGradient = custom
				)
				val customBitmap = renderSky(
					renderer,
					customParams,
					"custom-backdrop-${phase.name.lowercase()}-p$progress-$configName.png"
				)

				assertBitmapsEqual(
					naturalBitmap,
					customBitmap,
					"Custom sky ($configName) must match Natural backdrop at $phase (progress=$progress)"
				)

				customBitmap.recycle()
			}

			naturalBitmap.recycle()
		}
	}

	@Test
	fun customSkyGradientTransitionsContinuouslyNearTwilightSwitchBoundariesWithoutVisibleJump() {
		val renderer = SceneRenderer(resources)
		val testSamples = listOf(
			ContinuitySample("boundary-dawn1.0-day0.0", DayPhase.DAWN to 1.0f, DayPhase.DAY to 0.0f, SKY_HEIGHT, 5),
			ContinuitySample("boundary-day1.0-dusk0.0", DayPhase.DAY to 1.0f, DayPhase.DUSK to 0.0f, SKY_HEIGHT, 5),
			ContinuitySample("boundary-dusk1.0-night0.0", DayPhase.DUSK to 1.0f, DayPhase.NIGHT to 0.0f, SKY_HEIGHT, 5),
			ContinuitySample("transition-dawn-0.50-0.52", DayPhase.DAWN to 0.50f, DayPhase.DAWN to 0.52f, HEIGHT, 15),
			ContinuitySample("transition-dawn-0.50-0.55", DayPhase.DAWN to 0.50f, DayPhase.DAWN to 0.55f, HEIGHT, 35),
			ContinuitySample("transition-dawn-0.83-0.85", DayPhase.DAWN to 0.83f, DayPhase.DAWN to 0.85f, HEIGHT, 15),
			ContinuitySample("transition-dusk-0.15-0.18", DayPhase.DUSK to 0.15f, DayPhase.DUSK to 0.18f, HEIGHT, 15),
			ContinuitySample("transition-dusk-0.15-0.20", DayPhase.DUSK to 0.15f, DayPhase.DUSK to 0.20f, HEIGHT, 30),
			ContinuitySample("transition-dusk-0.48-0.50", DayPhase.DUSK to 0.48f, DayPhase.DUSK to 0.50f, HEIGHT, 15),
			ContinuitySample("transition-dusk-0.95-1.00", DayPhase.DUSK to 0.95f, DayPhase.DUSK to 1.00f, HEIGHT, 20)
		)

		for ((name, custom) in customSkyFixtures()) {
			for (sample in testSamples) {
				val (phase1, prog1) = sample.first
				val (phase2, prog2) = sample.second
				val params1 = clearSceneParams(phase1, prog1).copy(
					skyColorPreset = SkyColorPreset.CUSTOM,
					customSkyGradient = custom
				)
				val params2 = clearSceneParams(phase2, prog2).copy(
					skyColorPreset = SkyColorPreset.CUSTOM,
					customSkyGradient = custom
				)

				val bitmap1 = renderSky(
					renderer,
					params1,
					"custom-${sample.label}-a-$name.png"
				)
				val bitmap2 = renderSky(
					renderer,
					params2,
					"custom-${sample.label}-b-$name.png"
				)
				val delta = maxChannelDifference(bitmap1, bitmap2, sample.maxY)

				assertTrue(
					"${sample.label} should transition smoothly without visible jump (bound ${sample.maxAllowedDelta}, observed $delta for $name)",
					delta <= sample.maxAllowedDelta
				)

				bitmap1.recycle()
				bitmap2.recycle()
			}
		}
	}

	@Test
	fun customSkyCrossfadeUsesCurrentPhaseWeatherLighting() {
		val renderer = SceneRenderer(resources)
		for ((phase, progress) in listOf(DayPhase.DAWN to 0.7f, DayPhase.DUSK to 0.3f)) {
			val naturalParams = clearSceneParams(phase, progress).copy(cloudiness = 0.85f, thunder = true, skyBrightnessScale = 1.2f)
			val natural = renderSky(renderer, naturalParams, "natural-overcast-${phase.name.lowercase()}.png")
			for ((name, custom) in customSkyFixtures()) {
				val selected = naturalParams.copy(skyColorPreset = SkyColorPreset.CUSTOM, customSkyGradient = custom)
				val actual = renderSky(renderer, selected, "custom-overcast-${phase.name.lowercase()}-$name.png")
				val delta = maxChannelDifference(natural, actual)

				assertTrue("$phase weather colors must not switch to daytime gray for $name (delta=$delta)", delta <= 1)
				actual.recycle()
			}

			natural.recycle()
		}
	}

	private fun customSkyFixtures(): List<Pair<String, CustomSkyGradient>> {
		val baseGradients = listOf(
			"3color" to CustomSkyGradient(
				startColor = 0xFFFF2200.toInt(),
				middleColor = 0xFF00FF88.toInt(),
				endColor = 0xFF2244FF.toInt(),
				threeColors = true,
				startPosition = 0.2f,
				middlePosition = 0.4f,
				endPosition = 0.8f
			),
			"2color" to CustomSkyGradient(
				startColor = 0xFFFF0055.toInt(),
				endColor = 0xFF00EEFF.toInt(),
				threeColors = false,
				startPosition = 0.15f,
				endPosition = 0.75f
			)
		)

		return baseGradients.flatMap { (label, base) ->
			listOf(
				"$label-horizontal" to base.copy(direction = SkyGradientDirection.HORIZONTAL),
				"$label-diagonal" to base.copy(direction = SkyGradientDirection.DIAGONAL)
			)
		}
	}

	private fun clearSceneParams(dayPhase: DayPhase, celestialProgress: Float = 0.5f) = SceneParams(
		dayPhase = dayPhase,
		celestialProgress = celestialProgress,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f,
		skyColorPreset = SkyColorPreset.NATURAL
	)

	private fun assertBitmapsEqual(expected: Bitmap, actual: Bitmap, message: String) {
		if (expected.sameAs(actual)) {
			return
		}

		val width = expected.width
		val height = expected.height
		val count = width * height
		val expPixels = IntArray(count)
		val actPixels = IntArray(count)
		expected.getPixels(expPixels, 0, width, 0, 0, width, height)
		actual.getPixels(actPixels, 0, width, 0, 0, width, height)

		for (i in 0 until count) {
			if (expPixels[i] != actPixels[i]) {
				val x = i % width
				val y = i / width
				val exp = expPixels[i]
				val act = actPixels[i]
				assertEquals(
					"$message: pixel mismatch at ($x, $y) - expected 0x${Integer.toHexString(exp)} but was 0x${Integer.toHexString(act)}",
					exp,
					act
				)
			}
		}
	}

	private fun maxChannelDifference(bitmapA: Bitmap, bitmapB: Bitmap, maxY: Int = bitmapA.height): Int {
		val width = bitmapA.width
		val count = width * maxY
		val pixelsA = IntArray(count)
		val pixelsB = IntArray(count)
		bitmapA.getPixels(pixelsA, 0, width, 0, 0, width, maxY)
		bitmapB.getPixels(pixelsB, 0, width, 0, 0, width, maxY)

		var maxDiff = 0
		for (i in 0 until count) {
			val pA = pixelsA[i]
			val pB = pixelsB[i]
			val diffR = abs(Color.red(pA) - Color.red(pB))
			val diffG = abs(Color.green(pA) - Color.green(pB))
			val diffB = abs(Color.blue(pA) - Color.blue(pB))
			val diff = max(diffR, max(diffG, diffB))
			if (diff > maxDiff) {
				maxDiff = diff
			}
		}

		return maxDiff
	}

	private data class ContinuitySample(
		val label: String,
		val first: Pair<DayPhase, Float>,
		val second: Pair<DayPhase, Float>,
		val maxY: Int,
		val maxAllowedDelta: Int
	)

	private fun assertBackdropIsBlack(renderer: SceneRenderer, params: SceneParams) {
		val backdrop = createBitmap(WIDTH, HEIGHT)
		renderer.renderBackdrop(Canvas(backdrop), WIDTH, HEIGHT, params)

		for (y in intArrayOf(HEIGHT / 4, HEIGHT / 2, HEIGHT * 3 / 4)) {
			assertEquals(Color.BLACK, backdrop.getPixel(WIDTH / 2, y))
		}

		backdrop.recycle()
	}

	private fun saveCapture(bitmap: Bitmap, filename: String) {
		val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
		File(cacheDir, filename).outputStream().use { stream ->
			bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
		}
	}

	private companion object {
		const val WIDTH = 360
		const val HEIGHT = 780
		const val SKY_HEIGHT = HEIGHT / 2
	}
}
