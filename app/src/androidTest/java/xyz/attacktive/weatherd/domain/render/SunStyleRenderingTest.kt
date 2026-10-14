package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.roundToInt
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
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.model.SunColorPreset
import xyz.attacktive.weatherd.domain.model.SunStyle

@RunWith(AndroidJUnit4::class)
class SunStyleRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun radiantChangesTheVisibleSunWithoutChangingTheBackdrop() {
		for (phase in listOf(DayPhase.DAY, DayPhase.DAWN, DayPhase.DUSK)) {
			val classicParams = clearParams().copy(dayPhase = phase, celestialProgress = 0.25f)
			val radiantParams = classicParams.copy(sunStyle = SunStyle.RADIANT)
			val classic = render(classicParams)
			val radiant = render(radiantParams)
			val classicBackdrop = renderBackdrop(classicParams)
			val radiantBackdrop = renderBackdrop(radiantParams)

			assertFalse("Radiant must change the visible $phase sun", classic.sameAs(radiant))
			assertTrue("Sun style must not change the cached $phase backdrop", classicBackdrop.sameAs(radiantBackdrop))

			classic.recycle()
			radiant.recycle()
			classicBackdrop.recycle()
			radiantBackdrop.recycle()
		}
	}

	@Test
	fun radiantNeverLeaksIntoHiddenNightOrWeatherVeiledScenes() {
		val base = clearParams()
		val suppressed = listOf(
			base.copy(sunVisible = false),
			base.copy(dayPhase = DayPhase.NIGHT),
			base.copy(dayPhase = DayPhase.DUSK, celestialProgress = 1f),
			base.copy(fogDensity = 0.4f),
			base.copy(cloudiness = 0.65f),
			base.copy(cloudiness = 0.9f),
			base.copy(precipitation = Precipitation(PrecipitationKind.RAIN, 0.7f, 0.7f)),
			base.copy(thunder = true)
		)

		for (params in suppressed) {
			val classic = render(params)
			val radiant = render(params.copy(sunStyle = SunStyle.RADIANT))
			assertTrue("Sun style must not alter hidden or weather-veiled light: $params", classic.sameAs(radiant))
			classic.recycle()
			radiant.recycle()
		}
	}

	@Test
	fun radiantCoreMatchesClassicPositionAndCompactSizeInBothOrientations() {
		for ((width, height) in listOf(360 to 780, 780 to 360)) {
			val params = clearParams().copy(sunColorPreset = SunColorPreset.WHITE)
			val classic = render(params, width = width, height = height)
			val radiant = render(params.copy(sunStyle = SunStyle.RADIANT), width = width, height = height)
			val classicCore = whiteCoreBounds(classic)
			val radiantCore = whiteCoreBounds(radiant)
			val expectedX = width * 0.72f
			val expectedY = height * 0.17f

			assertTrue("Radiant must have a compact bright solar core", radiantCore.width > 0 && radiantCore.height > 0)
			assertTrue("The off-center source core must land at the solar x coordinate", abs(radiantCore.centerX - expectedX) <= 2f)
			assertTrue("The off-center source core must land at the solar y coordinate", abs(radiantCore.centerY - expectedY) <= 2f)
			assertTrue("Radiant must keep a comparable core size, not use the whole source sheet as its disc", radiantCore.width.toFloat() / classicCore.width in 0.6f..1.8f)

			classic.recycle()
			radiant.recycle()
		}
	}

	@Test
	fun radiantSizePreferenceResizesTheCoreWithoutMovingIt() {
		val params = clearParams().copy(sunStyle = SunStyle.RADIANT, sunColorPreset = SunColorPreset.WHITE)
		val small = render(params.copy(sunSizeScale = 0.5f))
		val large = render(params.copy(sunSizeScale = 1.5f))
		val smallCore = whiteCoreBounds(small)
		val largeCore = whiteCoreBounds(large)

		assertTrue("A larger sun size must enlarge the submitted solar core", largeCore.width > smallCore.width * 2)
		assertTrue("Resizing must keep the core anchored horizontally", abs(largeCore.centerX - smallCore.centerX) <= 2f)
		assertTrue("Resizing must keep the core anchored vertically", abs(largeCore.centerY - smallCore.centerY) <= 2f)

		small.recycle()
		large.recycle()
	}

	@Test
	fun radiantColorPreferenceWarmsTheArtworkWithoutChangingItsOpacity() {
		val params = clearParams().copy(sunStyle = SunStyle.RADIANT)
		val white = render(params.copy(sunColorPreset = SunColorPreset.WHITE))
		val orange = render(params.copy(sunColorPreset = SunColorPreset.ORANGE))
		val x = (WIDTH * 0.72f).roundToInt()
		val y = (HEIGHT * 0.17f).roundToInt()
		val whitePixel = white.getPixel(x, y)
		val orangePixel = orange.getPixel(x, y)

		assertTrue("Orange must warm the actual submitted artwork", Color.red(orangePixel) - Color.blue(orangePixel) > Color.red(whitePixel) - Color.blue(whitePixel) + 30)
		for (row in 0 until HEIGHT) {
			for (column in 0 until WIDTH) {
				assertEquals("Changing color must preserve texture opacity", Color.alpha(white.getPixel(column, row)), Color.alpha(orange.getPixel(column, row)))
			}
		}

		white.recycle()
		orange.recycle()
	}

	@Test
	fun radiantFadesContinuouslyAtSunset() {
		val alphas = listOf(0.15f, 0.65f, 0.93f).map { progress ->
			val params = clearParams().copy(sunStyle = SunStyle.RADIANT, dayPhase = DayPhase.DUSK, celestialProgress = progress)
			val bitmap = render(params)
			val x = (WIDTH * 0.72f).roundToInt()
			val y = (HEIGHT * (0.26f + (0.58f - 0.26f) * progress)).roundToInt()
			val alpha = Color.alpha(bitmap.getPixel(x, y))
			bitmap.recycle()
			alpha
		}

		assertTrue("Radiant must be visible before the sunset fade", alphas[0] >= 245)
		assertTrue("Radiant must fade with the existing solar visibility", alphas[0] > alphas[1] && alphas[1] > alphas[2])
		assertTrue("Radiant must disappear by late dusk", alphas[2] <= 2)
	}

	@Test
	fun switchingStylesAndColorsDoesNotLeaveStaleTextureOrPaint() {
		val switched = renderer()
		val classicControl = renderer()
		val radiantControl = renderer()
		val sequence = listOf(
			clearParams(),
			clearParams().copy(sunStyle = SunStyle.RADIANT, sunColorPreset = SunColorPreset.ORANGE),
			clearParams().copy(sunColorPreset = SunColorPreset.WHITE),
			clearParams().copy(sunStyle = SunStyle.RADIANT, sunColorPreset = SunColorPreset.WHITE),
			clearParams()
		)

		for (params in sequence) {
			val actual = render(params, switched)
			val classic = render(params.copy(sunStyle = SunStyle.CLASSIC), classicControl)
			val radiant = render(params.copy(sunStyle = SunStyle.RADIANT), radiantControl)
			val expected = if (params.sunStyle == SunStyle.CLASSIC) {
				classic
			} else {
				radiant
			}

			assertTrue("Switching must match an isolated renderer with matching color/frame history", actual.sameAs(expected))
			actual.recycle()
			classic.recycle()
			radiant.recycle()
		}
	}

	@Test
	fun radiantKeepsProceduralLensFlarePreferenceAndIndependentMotion() {
		val params = clearParams().copy(sunStyle = SunStyle.RADIANT, lensFlareMotionEnabled = true)
		val disabled = render(params)
		val enabled = render(params.copy(lensFlareEnabled = true))
		val movedRenderer = renderer().apply {
			lensFlareOffsetX = 0.7f
			lensFlareOffsetY = -0.5f
		}

		val moved = render(params.copy(lensFlareEnabled = true), movedRenderer)
		val disabledMovedRenderer = renderer().apply {
			lensFlareOffsetX = 0.7f
			lensFlareOffsetY = -0.5f
		}

		val disabledMoved = render(params, disabledMovedRenderer)

		assertFalse("Radiant must keep the separate procedural lens flare preference", disabled.sameAs(enabled))
		assertFalse("Sensor offsets must still move the separate procedural reflections", enabled.sameAs(moved))
		assertTrue("Offsets must not move the submitted art or its baked ghosts when flare is disabled", disabled.sameAs(disabledMoved))

		disabled.recycle()
		enabled.recycle()
		moved.recycle()
		disabledMoved.recycle()
	}

	@Test
	fun radiantGlowFadesBeforeTheTopTextureBoundary() {
		for ((width, height) in listOf(360 to 780, 780 to 360)) {
			val bitmap = render(clearParams().copy(sunStyle = SunStyle.RADIANT), width = width, height = height)
			val span = minOf(width, height)
			val centerX = width * 0.72f
			// The submitted sheet's top border lands here at the default core-matched size.
			val top = (height * 0.17f - span * 0.109f).roundToInt()
			val left = (centerX - span * 0.06f).roundToInt()
			val right = (centerX + span * 0.06f).roundToInt()

			try {
				for (y in top - 1..top + 2) {
					for (x in left..right) {
						assertTrue("The outer glow must disappear before its rectangular texture boundary at $x,$y", Color.alpha(bitmap.getPixel(x, y)) <= 2)
					}
				}
			} finally {
				bitmap.recycle()
			}
		}
	}

	@Test
	fun radiantOuterHazeAndBakedTailStaySubtleWithoutDimmingTheCore() {
		val bitmap = render(clearParams().copy(sunStyle = SunStyle.RADIANT))
		val centerX = WIDTH * 0.72f
		val centerY = HEIGHT * 0.17f
		val outerHazeX = (centerX + WIDTH * 0.08f).roundToInt()
		val tailX = (centerX + WIDTH * 0.07f).roundToInt()
		val tailY = (centerY + WIDTH * 0.135f).roundToInt()

		try {
			assertTrue("The solar core must retain its overexposed opacity", Color.alpha(bitmap.getPixel(centerX.roundToInt(), centerY.roundToInt())) >= 245)
			assertTrue("The outer haze must not form an opaque pale annulus", Color.alpha(bitmap.getPixel(outerHazeX, centerY.roundToInt())) <= 32)
			assertTrue("The distant baked reflection must not form a separate glowing blob", Color.alpha(bitmap.getPixel(tailX, tailY)) <= 2)
		} finally {
			bitmap.recycle()
		}
	}

	@Test
	fun radiantLensHaloDoesNotWashOutTheDistantClearSky() {
		val bitmap = render(clearParams().copy(sunStyle = SunStyle.RADIANT, lensFlareEnabled = true))
		var strongestAlpha = 0
		try {
			// This band crosses the broad optical halo, away from the solar core and the individual lens ghosts.
			for (y in 190..280) {
				for (x in 95..150) {
					strongestAlpha = maxOf(strongestAlpha, Color.alpha(bitmap.getPixel(x, y)))
				}
			}

			assertTrue("The wide optical haze must stay subtle over a clear sky, but reached alpha $strongestAlpha", strongestAlpha <= 8)
		} finally {
			bitmap.recycle()
		}
	}

	private fun renderer() = SceneRenderer(resources).apply {
		prewarmCloudTextures()
		prewarmSunTexture()
	}

	private fun render(params: SceneParams, renderer: SceneRenderer = renderer(), width: Int = WIDTH, height: Int = HEIGHT): Bitmap {
		val bitmap = createBitmap(width, height)
		renderer.renderForeground(Canvas(bitmap), width, height, params, 0f)

		return bitmap
	}

	private fun renderBackdrop(params: SceneParams): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		renderer().renderBackdrop(Canvas(bitmap), WIDTH, HEIGHT, params)

		return bitmap
	}

	private fun whiteCoreBounds(bitmap: Bitmap): CoreBounds {
		var left = bitmap.width
		var right = -1
		var top = bitmap.height
		var bottom = -1
		for (y in 0 until bitmap.height) {
			for (x in 0 until bitmap.width) {
				val pixel = bitmap.getPixel(x, y)
				if (Color.alpha(pixel) >= 245 && Color.red(pixel) >= 245 && Color.green(pixel) >= 245 && Color.blue(pixel) >= 240) {
					left = minOf(left, x)
					right = maxOf(right, x)
					top = minOf(top, y)
					bottom = maxOf(bottom, y)
				}
			}
		}

		return CoreBounds(left, right, top, bottom)
	}

	private fun clearParams() = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0.2f,
		lensFlareEnabled = false,
		celestialProgress = 0.5f
	)

	private data class CoreBounds(val left: Int, val right: Int, val top: Int, val bottom: Int) {
		val width = (right - left + 1).coerceAtLeast(0)
		val height = (bottom - top + 1).coerceAtLeast(0)
		val centerX = (left + right) / 2f
		val centerY = (top + bottom) / 2f
	}

	private companion object {
		const val WIDTH = 360
		const val HEIGHT = 780
	}
}
