package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.annotation.DrawableRes
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.R

@RunWith(AndroidJUnit4::class)
class CloudLayerTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun negativeOffsetMatchesTheSamePositionAfterAFullWrap() {
		val layer = CloudLayer(resources, R.drawable.cloud_sheet_near)
		val before = render(layer, offset = -17.25f)
		val after = render(layer, offset = 540f * CLOUD_TEXTURE_VIEWPORTS - 17.25f)
		assertTrue("Crossing the texture boundary must not change the sampled cloud", before.sameAs(after))
		before.recycle()
		after.recycle()
	}

	@Test
	fun cloudSheetDissolvesBeforeItsLowerEdge() {
		val layer = CloudLayer(resources, R.drawable.cloud_sheet_far)
		val bitmap = render(layer)
		val bottom = IntArray(bitmap.width)
		bitmap.getPixels(bottom, 0, bitmap.width, 0, bitmap.height - 1, bitmap.width, 1)
		assertTrue("The sheet must not end in a visible horizontal band", bottom.all { Color.alpha(it) == 0 })
		bitmap.recycle()
	}

	@Test
	fun cloudSheetsRenderWithBroadInternalShading() {
		for (texture in SHEET_TEXTURES) {
			val layer = CloudLayer(resources, texture)
			var darkest = 255
			var lightest = 0
			for (viewport in 0 until CLOUD_TEXTURE_VIEWPORTS.toInt()) {
				val bitmap = render(layer, offset = -540f * viewport)
				val pixels = IntArray(bitmap.width * bitmap.height)
				bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
				for (pixel in pixels) {
					if (Color.alpha(pixel) < 64) {
						continue
					}

					val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
					darkest = minOf(darkest, brightness)
					lightest = maxOf(lightest, brightness)
				}

				bitmap.recycle()
			}

			assertTrue("${name(texture)} must render visible self-shading, saw brightness $darkest..$lightest", lightest - darkest >= 30)
		}
	}

	@Test
	fun tintAndOpacityReturnToTheirPreviousAppearanceAfterAWeatherChange() {
		val layer = CloudLayer(resources, R.drawable.cloud_sheet_near)
		val day = render(layer, tint = Color.WHITE, alpha = 180)
		val night = render(layer, tint = Color.rgb(64, 72, 90), alpha = 70)
		val dayAgain = render(layer, tint = Color.WHITE, alpha = 180)
		assertFalse("Night should not retain the daylight tint", day.sameAs(night))
		assertTrue("Returning to daylight must restore the cloud appearance", day.sameAs(dayAgain))
		day.recycle()
		night.recycle()
		dayAgain.recycle()
	}

	@Test
	fun aDeckWrapsOnItsOwnRepeatSpan() {
		val layer = CloudLayer(resources, R.drawable.cloud_cumulus_far)
		val before = render(layer, offset = -17.25f, viewports = 2f)
		val after = render(layer, offset = 540f * 2f - 17.25f, viewports = 2f)

		// A deck that asks for its own span has to wrap on that span, not on the default one, or its drift jumps every time it crosses the seam.
		assertTrue("A two-viewport deck must repeat every two viewport widths", before.sameAs(after))
		before.recycle()
		after.recycle()
	}

	@Test
	fun aShorterRepeatSpanSamplesFinerDetail() {
		val layer = CloudLayer(resources, R.drawable.cloud_cumulus_far)
		val wide = render(layer, viewports = 4f)
		val narrow = render(layer, viewports = 2f)

		/*
		 * Halving the span halves the horizontal scale, so the same screen covers twice as much texture and the masses come out smaller.
		 * Measuring how much neighboring columns differ says that without depending on where bilinear sampling lands a given pixel.
		 */
		val wideDetail = horizontalDetail(wide)
		val narrowDetail = horizontalDetail(narrow)
		assertTrue(
			"A shorter span must sample finer detail, saw $wideDetail at four viewports and $narrowDetail at two",
			narrowDetail > wideDetail
		)

		wide.recycle()
		narrow.recycle()
	}

	@Test
	fun largerCumulusScaleOccupiesMorePixels() {
		val layer = CloudLayer(resources, R.drawable.cloud_cumulus_sparse)
		val small = render(layer, sizeScale = 0.5f)
		val large = render(layer, sizeScale = 2f)

		assertTrue("Larger cloud bodies must cover more pixels", opaqueArea(large) > opaqueArea(small))

		small.recycle()
		large.recycle()
	}

	@Test
	fun cumulusShadowDarkensCloudsWithoutChangingTheirAlphaMask() {
		val destination = CloudLayer(resources, R.drawable.cloud_cumulus_far)
		val blocker = CloudLayer(resources, R.drawable.cloud_cumulus_far)
		val baseline = render(destination)
		val blockerSampler = checkNotNull(
			blocker.opacitySampler(
				540f,
				320f,
				0f,
				0f,
				CLOUD_TEXTURE_VIEWPORTS,
				255
			)
		)

		val shadowed = render(
			destination,
			shadow = CloudLayer.CumulusShadow(
				lower = blockerSampler,
				upper = null,
				sourceOffsetX = 0f,
				sourceOffsetY = 0f,
				strength = 0.35f
			)
		)

		val before = IntArray(baseline.width * baseline.height)
		val after = IntArray(shadowed.width * shadowed.height)
		baseline.getPixels(before, 0, baseline.width, 0, 0, baseline.width, baseline.height)
		shadowed.getPixels(after, 0, shadowed.width, 0, 0, shadowed.width, shadowed.height)
		var changedCloudPixels = 0
		for (index in before.indices) {
			assertEquals("A cast shadow must not change the far cloud alpha mask", Color.alpha(before[index]), Color.alpha(after[index]))
			if (Color.alpha(before[index]) > 0 && before[index] != after[index]) {
				changedCloudPixels++
			}
		}

		assertTrue("The projected blocker must darken at least part of the far cloud deck", changedCloudPixels > 0)
		baseline.recycle()
		shadowed.recycle()
	}

	@Test
	fun everyCumulusDeckReachesFullyOpaqueCloud() {
		/*
		 * The guard on the whole texture set.
		 * A deck whose densest pixel is translucent can never paint a sunlit crown white, however the renderer tints or composites it, which is exactly how these decks used to wash out.
		 */
		for (texture in CUMULUS_TEXTURES) {
			val alpha = alphaHistogram(texture)
			val opaque = alpha.drop(250).sum()
			assertTrue("${name(texture)} must contain fully opaque cloud, not just a dense veil", opaque > 0)
			assertTrue("${name(texture)} must keep many partial alpha levels for its edges", alpha.count { it > 0 } > 32)
		}
	}

	@Test
	fun coverageGrowsAcrossTheCumulusSteps() {
		val covered = CUMULUS_COVERAGE_STEPS.map { texture ->
			alphaHistogram(texture).drop(128).sum()
		}

		/*
		 * Coverage lives in the textures rather than in a paint alpha, so the steps themselves have to differ.
		 * If they ever stop growing, a cloudier sky silently becomes the same sky drawn less transparently.
		 */
		assertEquals("Expected one measurement per coverage step", CUMULUS_COVERAGE_STEPS.size, covered.size)
		for (step in 1 until covered.size) {
			assertTrue(
				"Coverage step $step must cover more sky than step ${step - 1}, saw ${covered[step - 1]} then ${covered[step]}",
				covered[step] > covered[step - 1]
			)
		}
	}

	@Test
	fun everyCumulusDeckDissolvesAtBothEdges() {
		for (texture in CUMULUS_TEXTURES) {
			val bitmap = decode(texture)
			val row = IntArray(bitmap.width)
			bitmap.getPixels(row, 0, bitmap.width, 0, 0, bitmap.width, 1)
			assertTrue("${name(texture)} must not start on a visible horizontal line", row.all { Color.alpha(it) == 0 })
			bitmap.getPixels(row, 0, bitmap.width, 0, bitmap.height - 1, bitmap.width, 1)
			assertTrue("${name(texture)} must not end on a visible horizontal line", row.all { Color.alpha(it) == 0 })
			bitmap.recycle()
		}
	}

	private fun opaqueArea(bitmap: Bitmap): Int {
		val pixels = IntArray(bitmap.width * bitmap.height)
		bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
		return pixels.count { Color.alpha(it) > 0 }
	}

	private fun alphaHistogram(@DrawableRes texture: Int): IntArray {
		val bitmap = decode(texture)
		val pixels = IntArray(bitmap.width * bitmap.height)
		bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
		val histogram = IntArray(256)
		for (pixel in pixels) {
			histogram[Color.alpha(pixel)]++
		}

		bitmap.recycle()

		return histogram
	}

	private fun decode(@DrawableRes texture: Int) =
		checkNotNull(BitmapFactory.decodeResource(resources, texture, BitmapFactory.Options().apply { inScaled = false }))

	private fun name(@DrawableRes texture: Int) = resources.getResourceEntryName(texture)

	/** Mean absolute difference between horizontally adjacent pixels: higher means the deck is resolving smaller features. */
	private fun horizontalDetail(bitmap: Bitmap): Double {
		val pixels = IntArray(bitmap.width * bitmap.height)
		bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
		var total = 0L
		for (y in 0 until bitmap.height) {
			for (x in 1 until bitmap.width) {
				total += channelDistance(pixels[y * bitmap.width + x], pixels[y * bitmap.width + x - 1])
			}
		}

		return total.toDouble() / (bitmap.height * (bitmap.width - 1))
	}

	private fun channelDistance(a: Int, b: Int) =
		abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b)) + abs(Color.alpha(a) - Color.alpha(b))

	private fun render(
		layer: CloudLayer,
		offset: Float = 0f,
		tint: Int = Color.WHITE,
		alpha: Int = 255,
		viewports: Float = CLOUD_TEXTURE_VIEWPORTS,
		shadow: CloudLayer.CumulusShadow? = null,
		sizeScale: Float = 1f
	): Bitmap {
		val bitmap = createBitmap(540, 320)
		if (shadow == null) {
			layer.draw(Canvas(bitmap), bitmap.width.toFloat(), bitmap.height.toFloat(), offset, tint, alpha, 0f, viewports, sizeScale)
		} else {
			layer.drawShadowed(
				Canvas(bitmap),
				CloudLayer.CumulusShadowDraw(
					width = bitmap.width.toFloat(),
					height = bitmap.height.toFloat(),
					offset = offset,
					tint = tint,
					alpha = alpha,
					top = 0f,
					viewports = viewports,
					shadow = shadow,
					sizeScale = sizeScale
				)
			)
		}

		return bitmap
	}

	private companion object {
		val SHEET_TEXTURES = listOf(R.drawable.cloud_sheet_far, R.drawable.cloud_sheet_near)

		/** The near deck's coverage steps, in the order the renderer cross-fades them. */
		val CUMULUS_COVERAGE_STEPS = listOf(R.drawable.cloud_cumulus_sparse, R.drawable.cloud_cumulus_scattered, R.drawable.cloud_cumulus_broken)

		val CUMULUS_TEXTURES = CUMULUS_COVERAGE_STEPS + R.drawable.cloud_cumulus_far
	}

}
