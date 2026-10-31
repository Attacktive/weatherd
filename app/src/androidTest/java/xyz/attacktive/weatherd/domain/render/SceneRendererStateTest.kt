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
		val colorStart = 0xFFFF0000.toInt()
		val colorMiddle = 0xFF00FF00.toInt()
		val colorEnd = 0xFF0000FF.toInt()

		val threeColorParams = SceneParams(
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

		val verticalBitmap = createBitmap(WIDTH, HEIGHT)
		renderer.renderBackdrop(Canvas(verticalBitmap), WIDTH, HEIGHT, threeColorParams)
		saveCapture(verticalBitmap, "custom-sky-3-color-vertical.png")
		val topPixel = verticalBitmap.getPixel(WIDTH / 2, 2)
		val midPixel = verticalBitmap.getPixel(WIDTH / 2, HEIGHT / 2)
		val bottomPixel = verticalBitmap.getPixel(WIDTH / 2, HEIGHT - 3)

		assertEquals(255, Color.alpha(topPixel))
		assertTrue("Top pixel should be dominated by red", Color.red(topPixel) > 180 && Color.green(topPixel) < 60 && Color.blue(topPixel) < 60)
		assertTrue("Mid pixel should be dominated by green", Color.green(midPixel) > 180 && Color.red(midPixel) < 60 && Color.blue(midPixel) < 60)
		assertTrue("Bottom pixel should be dominated by blue", Color.blue(bottomPixel) > 180 && Color.red(bottomPixel) < 60 && Color.green(bottomPixel) < 60)
		verticalBitmap.recycle()

		val horizontalParams = threeColorParams.copy(
			customSkyGradient = threeColorParams.customSkyGradient.copy(direction = SkyGradientDirection.HORIZONTAL)
		)
		val horizontalBitmap = createBitmap(WIDTH, HEIGHT)
		renderer.renderBackdrop(Canvas(horizontalBitmap), WIDTH, HEIGHT, horizontalParams)
		saveCapture(horizontalBitmap, "custom-sky-3-color-horizontal.png")
		val leftPixel = horizontalBitmap.getPixel(2, HEIGHT / 2)
		val centerPixel = horizontalBitmap.getPixel(WIDTH / 2, HEIGHT / 2)
		val rightPixel = horizontalBitmap.getPixel(WIDTH - 3, HEIGHT / 2)

		assertTrue("Left pixel should be dominated by red", Color.red(leftPixel) > 180 && Color.green(leftPixel) < 60 && Color.blue(leftPixel) < 60)
		assertTrue("Center pixel should be dominated by green", Color.green(centerPixel) > 180 && Color.red(centerPixel) < 60 && Color.blue(centerPixel) < 60)
		assertTrue("Right pixel should be dominated by blue", Color.blue(rightPixel) > 180 && Color.red(rightPixel) < 60 && Color.green(rightPixel) < 60)
		horizontalBitmap.recycle()

		val diagonalParams = threeColorParams.copy(
			customSkyGradient = threeColorParams.customSkyGradient.copy(direction = SkyGradientDirection.DIAGONAL)
		)
		val diagonalBitmap = createBitmap(WIDTH, HEIGHT)
		renderer.renderBackdrop(Canvas(diagonalBitmap), WIDTH, HEIGHT, diagonalParams)
		saveCapture(diagonalBitmap, "custom-sky-3-color-diagonal.png")
		val topLeftPixel = diagonalBitmap.getPixel(2, 2)
		val diagCenterPixel = diagonalBitmap.getPixel(WIDTH / 2, HEIGHT / 2)
		val bottomRightPixel = diagonalBitmap.getPixel(WIDTH - 3, HEIGHT - 3)

		assertTrue("Top-left pixel should be dominated by red", Color.red(topLeftPixel) > 180 && Color.green(topLeftPixel) < 60 && Color.blue(topLeftPixel) < 60)
		assertTrue("Diag center pixel should be dominated by green", Color.green(diagCenterPixel) > 180 && Color.red(diagCenterPixel) < 60 && Color.blue(diagCenterPixel) < 60)
		assertTrue("Bottom-right pixel should be dominated by blue", Color.blue(bottomRightPixel) > 180 && Color.red(bottomRightPixel) < 60 && Color.green(bottomRightPixel) < 60)
		diagonalBitmap.recycle()

		val twoColorParams = threeColorParams.copy(
			customSkyGradient = CustomSkyGradient(
				startColor = colorStart,
				endColor = colorEnd,
				threeColors = false,
				startPosition = 0f,
				endPosition = 1f,
				direction = SkyGradientDirection.VERTICAL
			)
		)
		val twoColorVerticalBitmap = createBitmap(WIDTH, HEIGHT)
		renderer.renderBackdrop(Canvas(twoColorVerticalBitmap), WIDTH, HEIGHT, twoColorParams)
		saveCapture(twoColorVerticalBitmap, "custom-sky-2-color-vertical.png")
		val twoTopPixel = twoColorVerticalBitmap.getPixel(WIDTH / 2, 2)
		val twoBottomPixel = twoColorVerticalBitmap.getPixel(WIDTH / 2, HEIGHT - 3)
		assertTrue("2-color vertical top should be red", Color.red(twoTopPixel) > 180 && Color.blue(twoTopPixel) < 60)
		assertTrue("2-color vertical bottom should be blue", Color.blue(twoBottomPixel) > 180 && Color.red(twoBottomPixel) < 60)
		twoColorVerticalBitmap.recycle()

		val twoColorHorizontalBitmap = createBitmap(WIDTH, HEIGHT)
		renderer.renderBackdrop(Canvas(twoColorHorizontalBitmap), WIDTH, HEIGHT, twoColorParams.copy(customSkyGradient = twoColorParams.customSkyGradient.copy(direction = SkyGradientDirection.HORIZONTAL)))
		saveCapture(twoColorHorizontalBitmap, "custom-sky-2-color-horizontal.png")
		val twoLeftPixel = twoColorHorizontalBitmap.getPixel(2, HEIGHT / 2)
		val twoRightPixel = twoColorHorizontalBitmap.getPixel(WIDTH - 3, HEIGHT / 2)
		assertTrue("2-color horizontal left should be red", Color.red(twoLeftPixel) > 180 && Color.blue(twoLeftPixel) < 60)
		assertTrue("2-color horizontal right should be blue", Color.blue(twoRightPixel) > 180 && Color.red(twoRightPixel) < 60)
		twoColorHorizontalBitmap.recycle()

		val twoColorDiagonalBitmap = createBitmap(WIDTH, HEIGHT)
		renderer.renderBackdrop(Canvas(twoColorDiagonalBitmap), WIDTH, HEIGHT, twoColorParams.copy(customSkyGradient = twoColorParams.customSkyGradient.copy(direction = SkyGradientDirection.DIAGONAL)))
		saveCapture(twoColorDiagonalBitmap, "custom-sky-2-color-diagonal.png")
		val twoTopLeftPixel = twoColorDiagonalBitmap.getPixel(2, 2)
		val twoBottomRightPixel = twoColorDiagonalBitmap.getPixel(WIDTH - 3, HEIGHT - 3)
		assertTrue("2-color diagonal top-left should be red", Color.red(twoTopLeftPixel) > 180 && Color.blue(twoTopLeftPixel) < 60)
		assertTrue("2-color diagonal bottom-right should be blue", Color.blue(twoBottomRightPixel) > 180 && Color.red(twoBottomRightPixel) < 60)
		twoColorDiagonalBitmap.recycle()
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
	}
}
