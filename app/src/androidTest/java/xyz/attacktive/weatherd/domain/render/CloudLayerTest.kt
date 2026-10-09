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
import xyz.attacktive.weatherd.domain.model.DayPhase

@RunWith(AndroidJUnit4::class)
class CloudLayerTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun negativeOffsetMatchesTheSamePositionAfterAFullWrap() {
		val layer = CloudLayer(resources, R.drawable.cloud_overcast_hero)
		val before = render(layer, offset = -17.25f)
		val after = render(layer, offset = 540f * CLOUD_TEXTURE_VIEWPORTS - 17.25f)
		assertTrue("Crossing the texture boundary must not change the sampled cloud", before.sameAs(after))
		before.recycle()
		after.recycle()
	}

	@Test
	fun overcastBankDissolvesAtBothEdges() {
		for (texture in OVERCAST_TEXTURES) {
			val bitmap = decode(texture)
			val row = IntArray(bitmap.width)
			bitmap.getPixels(row, 0, bitmap.width, 0, 0, bitmap.width, 1)
			assertTrue("${name(texture)} must start effectively transparent", row.all { Color.alpha(it) <= OVERCAST_EDGE_ALPHA_MAX })
			bitmap.getPixels(row, 0, bitmap.width, 0, bitmap.height - 1, bitmap.width, 1)
			assertTrue("${name(texture)} must end effectively transparent", row.all { Color.alpha(it) <= OVERCAST_EDGE_ALPHA_MAX })
			bitmap.recycle()
		}
	}

	@Test
	fun overcastBanksKeepBroadInternalShading() {
		for (texture in OVERCAST_TEXTURES) {
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
		val layer = CloudLayer(resources, R.drawable.cloud_overcast_hero)
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
	fun contrastChangesCloudRgbWithoutChangingItsAlphaMask() {
		val layer = CloudLayer(resources, R.drawable.cloud_cumulus_sparse)
		val soft = render(layer, contrast = 0.5f)
		val strong = render(layer, contrast = 1.5f)
		val before = IntArray(soft.width * soft.height)
		val after = IntArray(strong.width * strong.height)
		soft.getPixels(before, 0, soft.width, 0, 0, soft.width, soft.height)
		strong.getPixels(after, 0, strong.width, 0, 0, strong.width, strong.height)
		var changedCloudPixels = 0
		for (index in before.indices) {
			assertEquals("Cloud contrast must preserve alpha exactly", Color.alpha(before[index]), Color.alpha(after[index]))
			if (Color.alpha(before[index]) > 0 && before[index] != after[index]) {
				changedCloudPixels++
			}
		}

		assertTrue("Cloud contrast must change visible cloud shading", changedCloudPixels > 0)
		assertTrue("Higher contrast must widen the visible RGB range", luminanceSpread(strong) > luminanceSpread(soft))
		soft.recycle()
		strong.recycle()
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
	fun partlyCumulusPopulatesMoreOfTheLowerSkyThanScattered() {
		val scattered = render(CloudLayer(resources, R.drawable.cloud_cumulus_scattered), viewports = 1f)
		val partly = render(CloudLayer.partlyCumulus(resources), viewports = 1f)
		val scatteredLowerArea = opaqueAreaBelow(scattered, 0.55f)
		val partlyLowerArea = opaqueAreaBelow(partly, 0.55f)

		assertTrue(
			"The partly-cloudy profile must add meaningful mid/lower-sky cloud area, saw $scatteredLowerArea scattered pixels and $partlyLowerArea partly pixels",
			partlyLowerArea > scatteredLowerArea
		)

		scattered.recycle()
		partly.recycle()
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
	fun gradePreservesAlphaAcrossNearFarAndBankSourcesAndOpacityContrastExtremes() {
		val sources = listOf(
			CloudLayer(resources, R.drawable.cloud_cumulus_sparse),
			CloudLayer(resources, R.drawable.cloud_cumulus_far),
			CloudLayer(resources, R.drawable.cloud_overcast_hero)
		)
		val testGrades = listOf(
			cloudColorGradeFor(DayPhase.DAWN, 0.5f),
			cloudColorGradeFor(DayPhase.DUSK, 0.5f)
		)

		for (layer in sources) {
			for (alpha in listOf(64, 255)) {
				for (contrast in listOf(0.5f, 1.5f)) {
					val ungraded = render(layer, alpha = alpha, contrast = contrast, grade = CloudColorGrade.IDENTITY)
					val ungradedPixels = IntArray(ungraded.width * ungraded.height)
					ungraded.getPixels(ungradedPixels, 0, ungraded.width, 0, 0, ungraded.width, ungraded.height)

					for (grade in testGrades) {
						val graded = render(layer, alpha = alpha, contrast = contrast, grade = grade)
						val gradedPixels = IntArray(graded.width * graded.height)
						graded.getPixels(gradedPixels, 0, graded.width, 0, 0, graded.width, graded.height)

						for (index in ungradedPixels.indices) {
							val unA = Color.alpha(ungradedPixels[index])
							val grA = Color.alpha(gradedPixels[index])
							assertEquals("Alpha must be preserved identically at index $index", unA, grA)
						}
						graded.recycle()
					}
					ungraded.recycle()
				}
			}
		}
	}

	@Test
	fun heroCumulusSpritesKeepSolidHighlightsAndSoftEdges() {
		for (texture in HERO_CUMULUS_TEXTURES) {
			val alpha = alphaHistogram(texture)
			assertTrue("${name(texture)} must keep near-opaque highlights", alpha.drop(250).sum() > 0)
			assertTrue("${name(texture)} must keep a transparent background", alpha[0] > 0)
			assertTrue("${name(texture)} must keep many partial alpha levels for soft edges", alpha.count { it > 0 } > 32)
		}
	}

	@Test
	fun lowSunGradingProducesWarmerHighlightsAndCoolerShadows() {
		val layer = CloudLayer(resources, R.drawable.cloud_cumulus_sparse)
		val grade = cloudColorGradeFor(DayPhase.DAWN, 0.5f)
		val bitmap = render(layer, tint = cumulusTint(DayPhase.DAWN, 0.5f), grade = grade)
		val pixels = IntArray(bitmap.width * bitmap.height)
		bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

		var highlightChecked = false
		var shadowChecked = false

		for (pixel in pixels) {
			val a = Color.alpha(pixel)
			if (a < 128) {
				continue
			}
			val r = Color.red(pixel)
			val g = Color.green(pixel)
			val b = Color.blue(pixel)
			val luminance = (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt()

			if (luminance >= 220) {
				assertTrue("Highlights should be warm (R >= B): r=$r, b=$b", r >= b)
				highlightChecked = true
			} else if (luminance in 60..140) {
				assertTrue("Shadows should be cooler than highlights: r=$r, b=$b", b > r || (b.toFloat() / r.coerceAtLeast(1).toFloat()) > 0.85f)
				shadowChecked = true
			}
		}

		assertTrue("Must have verified at least one highlight pixel", highlightChecked)
		assertTrue("Must have verified at least one shadow pixel", shadowChecked)
		bitmap.recycle()
	}

	@Test
	fun returningToSamePhaseTintAndContrastRestoresIdenticalCloudPixels() {
		val layer = CloudLayer(resources, R.drawable.cloud_cumulus_sparse)
		val dawnGrade = cloudColorGradeFor(DayPhase.DAWN, 0.5f)
		val dayGrade = cloudColorGradeFor(DayPhase.DAY, 0.5f)

		val first = render(layer, tint = Color.WHITE, contrast = 1.2f, grade = dawnGrade)
		val firstPixels = IntArray(first.width * first.height)
		first.getPixels(firstPixels, 0, first.width, 0, 0, first.width, first.height)

		val intermediate = render(layer, tint = Color.rgb(86, 96, 120), contrast = 0.8f, grade = dayGrade)
		intermediate.recycle()

		val second = render(layer, tint = Color.WHITE, contrast = 1.2f, grade = dawnGrade)
		val secondPixels = IntArray(second.width * second.height)
		second.getPixels(secondPixels, 0, second.width, 0, 0, second.width, second.height)

		for (index in firstPixels.indices) {
			assertEquals("Pixels must be identical upon returning to same params", firstPixels[index], secondPixels[index])
		}
		first.recycle()
		second.recycle()
	}

	private fun opaqueArea(bitmap: Bitmap): Int {
		val pixels = IntArray(bitmap.width * bitmap.height)
		bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
		return pixels.count { Color.alpha(it) > 0 }
	}

	private fun opaqueAreaBelow(bitmap: Bitmap, topFraction: Float): Int {
		val top = (bitmap.height * topFraction).toInt().coerceIn(0, bitmap.height)
		val height = bitmap.height - top
		if (height <= 0) {
			return 0
		}

		val pixels = IntArray(bitmap.width * height)
		bitmap.getPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, height)
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
	private fun luminanceSpread(bitmap: Bitmap): Int {
		val pixels = IntArray(bitmap.width * bitmap.height)
		bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
		var minimum = 255
		var maximum = 0
		var found = false
		for (pixel in pixels) {
			if (Color.alpha(pixel) < 64) {
				continue
			}

			val luminance = (Color.red(pixel) * 2126 + Color.green(pixel) * 7152 + Color.blue(pixel) * 722) / 10_000
			minimum = minOf(minimum, luminance)
			maximum = maxOf(maximum, luminance)
			found = true
		}

		return if (found) {
			maximum - minimum
		} else {
			0
		}
	}

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
		grade: CloudColorGrade = CloudColorGrade.IDENTITY,
		viewports: Float = CLOUD_TEXTURE_VIEWPORTS,
		shadow: CloudLayer.CumulusShadow? = null,
		sizeScale: Float = 1f,
		contrast: Float = 1f
	): Bitmap {
		val bitmap = createBitmap(540, 320)
		val geometry = CloudDrawGeometry().configure(bitmap.width.toFloat(), bitmap.height.toFloat(), offset, viewports = viewports, sizeScale = sizeScale)
		layer.draw(Canvas(bitmap), geometry, tint, alpha, grade, shadow, contrast)

		return bitmap
	}

	private companion object {
		const val OVERCAST_EDGE_ALPHA_MAX = 8

		val OVERCAST_TEXTURES = listOf(
			R.drawable.cloud_overcast_hero,
			R.drawable.cloud_overcast_support,
			R.drawable.cloud_overcast_veil
		)
		val HERO_CUMULUS_TEXTURES = listOf(
			R.drawable.cloud_cumulus_hero_broad,
			R.drawable.cloud_cumulus_hero_broad_alt,
			R.drawable.cloud_cumulus_hero_soft_broad,
			R.drawable.cloud_cumulus_hero_soft_broad_alt
		)
	}
}