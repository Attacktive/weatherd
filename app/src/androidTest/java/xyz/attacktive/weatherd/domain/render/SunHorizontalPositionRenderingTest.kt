package xyz.attacktive.weatherd.domain.render

import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import xyz.attacktive.weatherd.domain.model.DEFAULT_SUN_HORIZONTAL_POSITION
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.SunColorPreset
import xyz.attacktive.weatherd.domain.model.SunStyle

@RunWith(AndroidJUnit4::class)
class SunHorizontalPositionRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun sunCoreMovesWithHorizontalPositionInPortraitAndLandscape() {
		for ((width, height) in listOf(PORTRAIT_WIDTH to PORTRAIT_HEIGHT, LANDSCAPE_WIDTH to LANDSCAPE_HEIGHT)) {
			val expectedY = height * celestialHeightFraction(DayPhase.DAY, 0.5f)

			for (position in listOf(0.25f, 0.72f, 0.90f)) {
				val params = clearParams().copy(
					sunHorizontalPosition = position,
					sunColorPreset = SunColorPreset.WHITE
				)
				val foreground = renderForeground(params, width = width, height = height)
				try {
					val core = whiteCoreBounds(foreground)
					assertTrue(
						"White core x should follow sun horizontal position $position, but centerX was ${core.centerX} (expected ${width * position})",
						abs(core.centerX - width * position) <= 3f
					)
					assertTrue(
						"White core y should remain at expected celestial height $expectedY, but centerY was ${core.centerY}",
						abs(core.centerY - expectedY) <= 3f
					)

					if (position != 0.72f) {
						val oldAnchorX = (width * 0.72f).roundToInt()
						val oldAnchorY = expectedY.roundToInt()
						val pixelAtOldAnchor = foreground.getPixel(oldAnchorX, oldAnchorY)
						assertTrue(
							"Pixel at old anchor (0.72) should be cleared when position is $position, but alpha was ${Color.alpha(pixelAtOldAnchor)}",
							Color.alpha(pixelAtOldAnchor) < 60
						)
					}

					if (width == PORTRAIT_WIDTH) {
						val percent = (position * 100f).roundToInt()
						maybeSaveEvidence("sun_position_${percent}_day") { renderCompleteScene(params, width, height) }
					}
				} finally {
					foreground.recycle()
				}
			}
		}
	}

	@Test
	fun nightRenderingIsIdenticalAcrossDifferentSunHorizontalPositions() {
		val params72 = clearParams().copy(
			dayPhase = DayPhase.NIGHT,
			sunHorizontalPosition = DEFAULT_SUN_HORIZONTAL_POSITION
		)
		val params25 = params72.copy(sunHorizontalPosition = 0.25f)
		val params90 = params72.copy(sunHorizontalPosition = 0.90f)

		val r = renderer()
		runBlocking(Dispatchers.Default) { r.prewarmMoonPhase(0.5f) }
		val night72 = renderForeground(params72, renderer = r)
		val night25 = renderForeground(params25, renderer = r)
		val night90 = renderForeground(params90, renderer = r)
		try {
			val moonX = (PORTRAIT_WIDTH * 0.72f).roundToInt()
			val moonY = (PORTRAIT_HEIGHT * celestialHeightFraction(DayPhase.NIGHT, 0.5f)).roundToInt()
			val moonPixel = night72.getPixel(moonX, moonY)
			assertTrue(
				"A visible prepared Moon must be rendered at night anchor (0.72), alpha was ${Color.alpha(moonPixel)}",
				Color.alpha(moonPixel) >= 200
			)
			assertTrue("Night rendering with sun horizontal position 0.25f must match default 0.72f", night72.sameAs(night25))
			assertTrue("Night rendering with sun horizontal position 0.90f must match default 0.72f", night72.sameAs(night90))

			maybeSaveEvidence("sun_position_night_control") { renderCompleteScene(params72, renderer = r) }
		} finally {
			night72.recycle()
			night25.recycle()
			night90.recycle()
		}
	}

	@Test
	fun twilightSkyGlowRelocatesToNewHorizontalPositionInBackdrop() {
		for (phase in listOf(DayPhase.DAWN, DayPhase.DUSK)) {
			val params72 = clearParams().copy(
				dayPhase = phase,
				celestialProgress = 0.5f,
				sunHorizontalPosition = DEFAULT_SUN_HORIZONTAL_POSITION
			)
			val params25 = params72.copy(sunHorizontalPosition = 0.25f)
			val params90 = params72.copy(sunHorizontalPosition = 0.90f)

			val backdrop72 = renderBackdrop(params72)
			val backdrop25 = renderBackdrop(params25)
			val backdrop90 = renderBackdrop(params90)
			try {
				assertFalse("Backdrop at 0.25f must differ from 0.72f during $phase", backdrop72.sameAs(backdrop25))
				assertFalse("Backdrop at 0.90f must differ from 0.72f during $phase", backdrop72.sameAs(backdrop90))

				val glowY = (PORTRAIT_HEIGHT * celestialHeightFraction(phase, 0.5f)).roundToInt()
				val sampleX25 = (PORTRAIT_WIDTH * 0.25f).roundToInt()

				val pixel25At25 = backdrop25.getPixel(sampleX25, glowY)
				val pixel72At25 = backdrop72.getPixel(sampleX25, glowY)
				val brightness25At25 = Color.red(pixel25At25) + Color.green(pixel25At25)
				val brightness72At25 = Color.red(pixel72At25) + Color.green(pixel72At25)

				assertTrue(
					"Twilight glow at x=0.25 should be brighter when sun is at 0.25 than at 0.72 ($brightness25At25 vs $brightness72At25)",
					brightness25At25 > brightness72At25
				)

				if (phase == DayPhase.DAWN) {
					maybeSaveEvidence("sun_position_25_dawn") { renderCompleteScene(params25) }
				} else {
					maybeSaveEvidence("sun_position_90_dusk") { renderCompleteScene(params90) }
				}
			} finally {
				backdrop72.recycle()
				backdrop25.recycle()
				backdrop90.recycle()
			}
		}
	}

	@Test
	fun allSunStylesFollowHorizontalPosition() {
		val position = 0.35f
		val expectedX = PORTRAIT_WIDTH * position
		val expectedY = PORTRAIT_HEIGHT * celestialHeightFraction(DayPhase.DAY, 0.5f)

		for (style in listOf(SunStyle.CLASSIC, SunStyle.RADIANT, SunStyle.STARBURST)) {
			val params = clearParams().copy(
				sunStyle = style,
				sunHorizontalPosition = position,
				sunColorPreset = SunColorPreset.WHITE
			)
			val foreground = renderForeground(params)
			try {
				val core = whiteCoreBounds(foreground)
				assertTrue(
					"Style $style white core x should center at $expectedX, but was ${core.centerX}",
					abs(core.centerX - expectedX) <= 3f
				)
				assertTrue(
					"Style $style white core y should center at $expectedY, but was ${core.centerY}",
					abs(core.centerY - expectedY) <= 3f
				)
			} finally {
				foreground.recycle()
			}
		}
	}

	@Test
	fun endpointsClampedOrClippedWithoutCrashing() {
		val expectedY = PORTRAIT_HEIGHT * celestialHeightFraction(DayPhase.DAY, 0.5f)
		val oldAnchorX = (PORTRAIT_WIDTH * 0.72f).roundToInt()
		val oldAnchorY = expectedY.roundToInt()

		val paramsLeft = clearParams().copy(sunHorizontalPosition = 0f, sunColorPreset = SunColorPreset.WHITE)
		val foregroundLeft = renderForeground(paramsLeft)
		try {
			val core = whiteCoreBounds(foregroundLeft)
			assertTrue("Left endpoint 0.0 must have non-empty visible core width", core.width > 0)
			assertTrue("Left endpoint 0.0 must have non-empty visible core height", core.height > 0)
			assertEquals("Left endpoint 0.0 must touch left edge of screen", 0, core.left)
			assertTrue("Left endpoint 0.0 core y should match expected celestial height", abs(core.centerY - expectedY) <= 3f)
			val pixelAtOldAnchor = foregroundLeft.getPixel(oldAnchorX, oldAnchorY)
			assertTrue("Pixel at old anchor must be clear when sun is at left endpoint", Color.alpha(pixelAtOldAnchor) < 60)
		} finally {
			foregroundLeft.recycle()
		}

		val paramsRight = clearParams().copy(sunHorizontalPosition = 1f, sunColorPreset = SunColorPreset.WHITE)
		val foregroundRight = renderForeground(paramsRight)
		try {
			val core = whiteCoreBounds(foregroundRight)
			assertTrue("Right endpoint 1.0 must have non-empty visible core width", core.width > 0)
			assertTrue("Right endpoint 1.0 must have non-empty visible core height", core.height > 0)
			assertEquals("Right endpoint 1.0 must touch right edge of screen", PORTRAIT_WIDTH - 1, core.right)
			assertTrue("Right endpoint 1.0 core y should match expected celestial height", abs(core.centerY - expectedY) <= 3f)
			val pixelAtOldAnchor = foregroundRight.getPixel(oldAnchorX, oldAnchorY)
			assertTrue("Pixel at old anchor must be clear when sun is at right endpoint", Color.alpha(pixelAtOldAnchor) < 60)
		} finally {
			foregroundRight.recycle()
		}
	}

	private fun renderer() = SceneRenderer(resources).apply {
		prewarmCloudTextures()
		SunStyle.entries.forEach { prewarmSunTexture(it) }
	}

	private fun renderForeground(params: SceneParams, renderer: SceneRenderer = renderer(), width: Int = PORTRAIT_WIDTH, height: Int = PORTRAIT_HEIGHT): Bitmap {
		val bitmap = createBitmap(width, height)
		renderer.renderForeground(Canvas(bitmap), width, height, params, 0f)

		return bitmap
	}

	private fun renderBackdrop(params: SceneParams, renderer: SceneRenderer = renderer(), width: Int = PORTRAIT_WIDTH, height: Int = PORTRAIT_HEIGHT): Bitmap {
		val bitmap = createBitmap(width, height)
		renderer.renderBackdrop(Canvas(bitmap), width, height, params)

		return bitmap
	}

	private fun renderCompleteScene(params: SceneParams, width: Int = PORTRAIT_WIDTH, height: Int = PORTRAIT_HEIGHT, renderer: SceneRenderer = renderer()): Bitmap {
		val bitmap = createBitmap(width, height)
		val canvas = Canvas(bitmap)
		renderer.renderBackdrop(canvas, width, height, params)
		renderer.renderForeground(canvas, width, height, params, 0f)

		return bitmap
	}

	private fun maybeSaveEvidence(name: String, render: () -> Bitmap) {
		val args = InstrumentationRegistry.getArguments()
		val captureRequested = args.getString("sunPositionEvidence")?.toBoolean() == true || args.containsKey("sunPositionEvidence")
		if (!captureRequested) {
			return
		}

		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val dir = context.getExternalFilesDir(null) ?: return
		val file = File(dir, "$name.png")
		val bitmap = render()
		try {
			FileOutputStream(file).use { out ->
				bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
			}
		} finally {
			bitmap.recycle()
		}
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
		const val PORTRAIT_WIDTH = 360
		const val PORTRAIT_HEIGHT = 780
		const val LANDSCAPE_WIDTH = 780
		const val LANDSCAPE_HEIGHT = 360
	}
}
