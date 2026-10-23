package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase

@RunWith(AndroidJUnit4::class)
class MoonOcclusionLayerTest {
	private val params = SceneParams(DayPhase.NIGHT, 0.7f, 0f, null, false, 0.2f)
	private val sky = Color.rgb(14, 20, 40)
	private val white = Paint().apply { color = Color.WHITE }

	@Test
	fun denseCloudRevealsSkyWithoutLeakingABackgroundStar() {
		val layer = MoonOcclusionLayer()
		val sprite = createBitmap(100, 100).apply { eraseColor(Color.WHITE) }
		layer.configure(prepared(sprite), 50f, 50f, 40f)
		checkNotNull(layer.beginCloudMask(100f, 100f, params, 1f, 20_000L)).drawColor(Color.WHITE)
		layer.finishCloudMask()
		val output = createBitmap(100, 100)
		val canvas = Canvas(output)
		canvas.drawColor(sky)
		val star = layer.beginStar(canvas, 22f, 47f, 28f, 53f)
		canvas.drawCircle(25f, 50f, 3f, white)
		layer.endStar(canvas, star)
		layer.drawDisc(canvas)
		assertEquals("Covered moon must reveal the original sky, not a black disc or star", sky, output.getPixel(25, 50))
		sprite.recycle()
		output.recycle()
	}

	@Test
	fun clearGapsStayBrightWhileThinCloudsTransmitAndDenseCloudsObscure() {
		val layer = MoonOcclusionLayer()
		val sprite = createBitmap(100, 100).apply { eraseColor(Color.WHITE) }
		layer.configure(prepared(sprite), 50f, 50f, 40f)
		val clouds = checkNotNull(layer.beginCloudMask(100f, 100f, params, 1f, 20_000L))
		clouds.drawRect(35f, 10f, 65f, 90f, Paint().apply { color = Color.argb(110, 255, 255, 255) })
		clouds.drawRect(65f, 10f, 90f, 90f, white)
		layer.finishCloudMask()
		val output = render(layer)
		assertEquals("An unobstructed lunar pixel must retain its source brightness", Color.WHITE, output.getPixel(25, 50))
		assertTrue("Thin cloud must retain partial lunar light", Color.red(output.getPixel(50, 50)) in 30..235)
		assertEquals("Dense cloud must remove lunar detail completely", sky, output.getPixel(75, 50))
		sprite.recycle()
		output.recycle()
	}

	@Test
	fun movingCloudReplacesTheOldMaskAndClearingCloudsRestoresTheMoon() {
		val layer = MoonOcclusionLayer()
		val sprite = createBitmap(100, 100).apply { eraseColor(Color.WHITE) }
		layer.configure(prepared(sprite), 50f, 50f, 40f)
		checkNotNull(layer.beginCloudMask(100f, 100f, params, 1f, 20_000L)).drawRect(10f, 10f, 50f, 90f, white)
		layer.finishCloudMask()
		val before = render(layer)
		assertEquals(sky, before.getPixel(25, 50))
		assertEquals(Color.WHITE, before.getPixel(75, 50))
		checkNotNull(layer.beginCloudMask(100f, 100f, params, 2f, 20_000L)).drawRect(50f, 10f, 90f, 90f, white)
		layer.finishCloudMask()
		val after = render(layer)
		assertEquals("Cloud motion must expose the previous overlap", Color.WHITE, after.getPixel(25, 50))
		assertEquals("Cloud motion must cover the new overlap", sky, after.getPixel(75, 50))
		layer.clearCloudMask()
		val clear = render(layer)
		assertEquals("Disabling clouds must discard the old occlusion", Color.WHITE, clear.getPixel(75, 50))
		checkNotNull(layer.beginCloudMask(100f, 100f, params, 2f, 20_000L)).drawColor(Color.WHITE)
		layer.finishCloudMask()
		val restored = render(layer)
		assertEquals("Re-enabling identical cloud inputs must rebuild their mask", sky, restored.getPixel(75, 50))
		listOf(sprite, before, after, clear, restored).forEach { it.recycle() }
	}

	@Test
	fun starMaskDoesNotEraseTheSkyOrClipStarsOutsideTheMoon() {
		val layer = MoonOcclusionLayer()
		val sprite = createBitmap(100, 100)
		Canvas(sprite).drawCircle(50f, 50f, 50f, white)
		layer.configure(prepared(sprite), 50f, 50f, 30f)
		checkNotNull(layer.beginCloudMask(100f, 100f, params, 1f, 20_000L)).drawColor(Color.WHITE)
		layer.finishCloudMask()
		val output = createBitmap(100, 100)
		val canvas = Canvas(output)
		canvas.drawColor(sky)
		val star = layer.beginStar(canvas, 5f, 5f, 95f, 95f)
		canvas.drawRect(5f, 5f, 95f, 95f, white)
		layer.endStar(canvas, star)
		layer.drawDisc(canvas)
		assertEquals("The portion of a celestial light behind the moon must disappear", sky, output.getPixel(50, 50))
		assertEquals("The same light outside the lunar silhouette must remain", Color.WHITE, output.getPixel(25, 25))
		sprite.recycle()
		output.recycle()
	}

	@Test
	fun changedGeometryAndSettingsCannotReuseAnOldCloudMask() {
		val layer = MoonOcclusionLayer()
		val sprite = createBitmap(100, 100).apply { eraseColor(Color.WHITE) }
		val phase = prepared(sprite)
		layer.configure(phase, 50f, 50f, 40f)
		checkNotNull(layer.beginCloudMask(100f, 100f, params, 0f, 20_000L)).drawColor(Color.WHITE)
		layer.finishCloudMask()
		layer.configure(phase, 70f, 50f, 20f)
		checkNotNull(layer.beginCloudMask(100f, 100f, params, 0f, 20_000L))
		layer.finishCloudMask()
		val moved = render(layer)
		assertEquals("Moving/resizing the moon must discard an unrelated mask", Color.WHITE, moved.getPixel(70, 50))
		checkNotNull(layer.beginCloudMask(100f, 100f, params.copy(cloudScale = 0.5f), 0f, 20_000L)).drawColor(Color.WHITE)
		layer.finishCloudMask()
		val changed = render(layer)
		assertEquals("Changing cloud settings must invalidate a cached clear region", sky, changed.getPixel(70, 50))
		layer.configure(null, 70f, 50f, 20f)
		val hidden = render(layer)
		assertEquals("An unprepared or hidden moon must not reuse its prior sprite", sky, hidden.getPixel(70, 50))
		listOf(sprite, moved, changed, hidden).forEach { it.recycle() }
	}

	@Test
	fun cloudElsewhereDoesNotDimAStarAtAnUncoveredTranslucentLunarEdge() {
		val layer = MoonOcclusionLayer()
		val sprite = createBitmap(100, 100).apply { eraseColor(Color.argb(128, 255, 0, 0)) }
		layer.configure(prepared(sprite), 50f, 50f, 40f)
		checkNotNull(layer.beginCloudMask(100f, 100f, params, 1f, 20_000L)).drawRect(60f, 10f, 90f, 90f, white)
		layer.finishCloudMask()
		val output = createBitmap(100, 100)
		val canvas = Canvas(output)
		canvas.drawColor(Color.BLACK)
		val star = layer.beginStar(canvas, 22f, 47f, 28f, 53f)
		canvas.drawCircle(25f, 50f, 3f, white)
		layer.endStar(canvas, star)
		layer.drawDisc(canvas)
		assertEquals("A half-alpha red lunar edge over a white star must composite exactly once", Color.rgb(255, 127, 127), output.getPixel(25, 50))
		sprite.recycle()
		output.recycle()
	}

	@Test
	fun partialLunarAlphaBlocksTheSameStarLightAsCloudTransmissionChanges() {
		val layer = MoonOcclusionLayer()
		val sprite = createBitmap(100, 100).apply { eraseColor(Color.argb(128, 255, 0, 0)) }
		layer.configure(prepared(sprite), 50f, 50f, 40f)
		var previousRed = 256
		for (cloudAlpha in listOf(0, 110, 255)) {
			checkNotNull(layer.beginCloudMask(100f, 100f, params, cloudAlpha.toFloat(), 20_000L)).drawColor(Color.argb(cloudAlpha, 255, 255, 255))
			layer.finishCloudMask()
			val output = createBitmap(100, 100)
			val canvas = Canvas(output)
			canvas.drawColor(Color.BLACK)
			val star = layer.beginStar(canvas, 47f, 47f, 53f, 53f)
			canvas.drawCircle(50f, 50f, 3f, white)
			layer.endStar(canvas, star)
			layer.drawDisc(canvas)
			val color = output.getPixel(50, 50)
			assertEquals("Clouds may remove red lunar light, but must not change the star's green contribution", 127f, Color.green(color).toFloat(), 1f)
			assertTrue("Increasing cloud opacity must reduce lunar light", Color.red(color) < previousRed)
			previousRed = Color.red(color)
			output.recycle()
		}

		sprite.recycle()
	}

	private fun prepared(sprite: Bitmap): MoonLayer.PreparedPhase {
		val size = MoonOcclusionLayer.MASK_SIZE
		val scaled = Bitmap.createScaledBitmap(sprite, size, size, true)
		val pixels = IntArray(size * size)
		scaled.getPixels(pixels, 0, size, 0, 0, size, size)
		scaled.recycle()

		return MoonLayer.PreparedPhase(sprite, FloatArray(0), ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() })
	}

	private fun render(layer: MoonOcclusionLayer): Bitmap {
		val output = createBitmap(100, 100)
		val canvas = Canvas(output)
		canvas.drawColor(sky)
		layer.drawDisc(canvas)

		return output
	}
}
