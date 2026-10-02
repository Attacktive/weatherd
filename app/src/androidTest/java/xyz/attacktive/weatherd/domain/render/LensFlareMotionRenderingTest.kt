package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase

@RunWith(AndroidJUnit4::class)
class LensFlareMotionRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun positiveHorizontalAndVerticalOffsetVisiblyMovesTheStrongestLensGhost() {
		val params = clearParams().copy(lensFlareMotionEnabled = true)
		val rendererZero = SceneRenderer(resources)
		rendererZero.lensFlareOffsetX = 0f
		rendererZero.lensFlareOffsetY = 0f
		val bitmapZero = renderForeground(rendererZero, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params)

		val rendererMoved = SceneRenderer(resources)
		rendererMoved.lensFlareOffsetX = 0.5f
		rendererMoved.lensFlareOffsetY = 0.5f
		val bitmapMoved = renderForeground(rendererMoved, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params)

		val zeroGhostCenter = lensGhostCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, LENS_GHOST_TEST_DISTANCE)
		val movedGhostCenter = lensGhostCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, LENS_GHOST_TEST_DISTANCE, offsetX = 0.5f, offsetY = 0.5f)

		val zeroPixelAtZeroCenter = bitmapZero.getPixel(zeroGhostCenter.x, zeroGhostCenter.y)
		val movedPixelAtZeroCenter = bitmapMoved.getPixel(zeroGhostCenter.x, zeroGhostCenter.y)
		val zeroPixelAtMovedCenter = bitmapZero.getPixel(movedGhostCenter.x, movedGhostCenter.y)
		val movedPixelAtMovedCenter = bitmapMoved.getPixel(movedGhostCenter.x, movedGhostCenter.y)

		assertTrue(
			"The strongest lens ghost should visibly shift away from its rest position under positive tilt",
			Color.alpha(zeroPixelAtZeroCenter) > Color.alpha(movedPixelAtZeroCenter)
		)

		assertTrue(
			"The strongest lens ghost should visibly brighten its displaced target position under positive tilt",
			Color.alpha(movedPixelAtMovedCenter) > Color.alpha(zeroPixelAtMovedCenter)
		)

		val sunCenter = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, DayPhase.DAY)
		assertEquals(
			"The solar disc core must remain anchored at the sun center despite lens flare motion",
			bitmapZero.getPixel(sunCenter.x, sunCenter.y),
			bitmapMoved.getPixel(sunCenter.x, sunCenter.y)
		)

		bitmapZero.recycle()
		bitmapMoved.recycle()
	}

	@Test
	fun disabledLensFlareMotionProducesByteIdenticalFramesDespiteOffsets() {
		val params = clearParams().copy(lensFlareMotionEnabled = false)
		val rendererZero = SceneRenderer(resources)
		rendererZero.lensFlareOffsetX = 0f
		rendererZero.lensFlareOffsetY = 0f
		val bitmapZero = renderForeground(rendererZero, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params)

		val rendererOffset = SceneRenderer(resources)
		rendererOffset.lensFlareOffsetX = 0.8f
		rendererOffset.lensFlareOffsetY = -0.6f
		val bitmapOffset = renderForeground(rendererOffset, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params)

		val rendererExtreme = SceneRenderer(resources)
		rendererExtreme.lensFlareOffsetX = 10f
		rendererExtreme.lensFlareOffsetY = Float.NaN
		val bitmapExtreme = renderForeground(rendererExtreme, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params)

		assertArrayEquals(
			"Disabled motion preference must ignore renderer offsets and produce byte-identical frames",
			pixels(bitmapZero),
			pixels(bitmapOffset)
		)

		assertArrayEquals(
			"Extreme and nonfinite renderer offsets must be clamped safely and ignored when motion is disabled",
			pixels(bitmapZero),
			pixels(bitmapExtreme)
		)

		bitmapZero.recycle()
		bitmapOffset.recycle()
		bitmapExtreme.recycle()
	}

	@Test
	fun sunCoreAndCoronaRemainUnchangedWhenLensFlareIsDisabled() {
		val params = clearParams().copy(lensFlareEnabled = false, lensFlareMotionEnabled = true)
		val rendererZero = SceneRenderer(resources)
		rendererZero.lensFlareOffsetX = 0f
		rendererZero.lensFlareOffsetY = 0f
		val bitmapZero = renderForeground(rendererZero, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params)

		val rendererOffset = SceneRenderer(resources)
		rendererOffset.lensFlareOffsetX = 0.75f
		rendererOffset.lensFlareOffsetY = 0.75f
		val bitmapOffset = renderForeground(rendererOffset, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params)

		val sunCenter = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, DayPhase.DAY)
		val centerPixel = bitmapZero.getPixel(sunCenter.x, sunCenter.y)
		assertTrue(
			"Sun core must remain rendered and visible",
			Color.alpha(centerPixel) >= 240
		)

		assertArrayEquals(
			"When lens flare is disabled, physical corona and sun core must remain byte-identical despite motion offsets",
			pixels(bitmapZero),
			pixels(bitmapOffset)
		)

		bitmapZero.recycle()
		bitmapOffset.recycle()
	}

	@Test
	fun staticBackdropRemainsUnchangedAtDifferentOffsets() {
		val params = clearParams().copy(lensFlareMotionEnabled = true)
		val rendererZero = SceneRenderer(resources)
		rendererZero.lensFlareOffsetX = 0f
		rendererZero.lensFlareOffsetY = 0f
		val backdropZero = renderBackdrop(rendererZero, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params)

		val rendererOffset = SceneRenderer(resources)
		rendererOffset.lensFlareOffsetX = 0.9f
		rendererOffset.lensFlareOffsetY = 0.9f
		val backdropOffset = renderBackdrop(rendererOffset, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params)

		val backdropDisabled = renderBackdrop(rendererZero, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params.copy(lensFlareMotionEnabled = false))

		assertArrayEquals(
			"Static backdrop must remain byte-identical at different renderer offsets",
			pixels(backdropZero),
			pixels(backdropOffset)
		)

		assertArrayEquals(
			"Static backdrop must remain byte-identical regardless of motion preference",
			pixels(backdropZero),
			pixels(backdropDisabled)
		)

		backdropZero.recycle()
		backdropOffset.recycle()
		backdropDisabled.recycle()
	}

	private fun renderForeground(renderer: SceneRenderer, width: Int, height: Int, params: SceneParams, timeSeconds: Float = 0f): Bitmap {
		val bitmap = createBitmap(width, height)

		renderer.renderForeground(Canvas(bitmap), width, height, params, timeSeconds)

		return bitmap
	}

	private fun renderBackdrop(renderer: SceneRenderer, width: Int, height: Int, params: SceneParams): Bitmap {
		val bitmap = createBitmap(width, height)

		renderer.renderBackdrop(Canvas(bitmap), width, height, params)

		return bitmap
	}

	private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
		bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
	}

	private fun lensGhostCenter(width: Int, height: Int, distance: Float, offsetX: Float = 0f, offsetY: Float = 0f): PixelPoint {
		val sun = celestialCenter(width, height, DayPhase.DAY)
		val span = minOf(width, height).toFloat()
		val displacementX = offsetX * span * 0.12f
		val displacementY = offsetY * span * 0.12f
		val opticalCenterX = width / 2f + displacementX
		val opticalCenterY = height / 2f + displacementY
		val axisX = opticalCenterX - sun.x
		val axisY = opticalCenterY - sun.y

		return PixelPoint(
			x = (sun.x + axisX * distance).roundToInt(),
			y = (sun.y + axisY * distance).roundToInt()
		)
	}

	private fun celestialCenter(width: Int, height: Int, dayPhase: DayPhase, progress: Float = 0.5f) = PixelPoint(
		x = (width * CELESTIAL_X_FRACTION).roundToInt(),
		y = (height * celestialHeightFraction(dayPhase, progress)).roundToInt()
	)

	private fun clearParams() = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0.2f
	)

	private data class PixelPoint(val x: Int, val y: Int)

	private companion object {
		const val PORTRAIT_WIDTH = 360
		const val PORTRAIT_HEIGHT = 780
		const val LENS_GHOST_TEST_DISTANCE = 1.08f
		const val CELESTIAL_X_FRACTION = 0.72f
	}
}
