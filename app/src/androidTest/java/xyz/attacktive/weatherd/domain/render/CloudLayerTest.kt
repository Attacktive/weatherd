package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
	fun cumulusTexturesDissolveBeforeTheirLowerEdge() {
		val textures = listOf(
			R.drawable.cloud_cumulus_sparse,
			R.drawable.cloud_cumulus_near,
			R.drawable.cloud_cumulus_horizon,
		)

		for (texture in textures) {
			val layer = CloudLayer(resources, texture)
			val bitmap = render(layer)
			val bottom = IntArray(bitmap.width)
			bitmap.getPixels(bottom, 0, bitmap.width, 0, bitmap.height - 1, bitmap.width, 1)
			assertTrue("Cumulus texture must dissolve before the bottom edge", bottom.all { Color.alpha(it) == 0 })
			bitmap.recycle()
		}
	}

	@Test
	fun cumulusUniformDrawWrapsSeamlessly() {
		val layer = CloudLayer(resources, R.drawable.cloud_cumulus_near)
		val period = layer.period(320f)
		val before = renderUniform(layer, offset = -20f)
		val after = renderUniform(layer, offset = period - 20f)
		assertTrue("Uniform cumulus rendering must wrap seamlessly at period", before.sameAs(after))
		before.recycle()
		after.recycle()
	}

	private fun render(layer: CloudLayer, offset: Float = 0f, tint: Int = Color.WHITE, alpha: Int = 255): Bitmap {
		val bitmap = createBitmap(540, 320)
		layer.draw(Canvas(bitmap), bitmap.width.toFloat(), bitmap.height.toFloat(), offset, tint, alpha)
		return bitmap
	}

	private fun renderUniform(layer: CloudLayer, offset: Float = 0f, tint: Int = Color.WHITE, alpha: Int = 255): Bitmap {
		val bitmap = createBitmap(540, 320)
		layer.drawUniform(Canvas(bitmap), bitmap.width.toFloat(), bitmap.height.toFloat(), offset, tint, alpha)
		return bitmap
	}
}
